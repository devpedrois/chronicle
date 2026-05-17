package com.chronicle.core.projection;

import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous polling engine that distributes new events to registered projections.
 * Each projection tracks its own cursor in {@link ProjectionPositionStore}.
 * Projections are independent — an error in one does not stop others.
 *
 * <p>Lifecycle: single-use. Call {@link #start()} once, {@link #stop()} once.
 */
public class ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ProjectionEngine.class);
    private static final int BATCH_SIZE = 500;
    private static final long STOP_TIMEOUT_SECONDS = 30;
    private static final long BASE_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final EventStore eventStore;
    private final ProjectionPositionStore positionStore;
    private final List<Projection> projections;
    private final long pollIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // ConcurrentHashMap allows clearBackoffState() to be called from test threads safely
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> backedOffUntilMs = new ConcurrentHashMap<>();

    public ProjectionEngine(
            EventStore eventStore,
            ProjectionPositionStore positionStore,
            List<Projection> projections,
            long pollIntervalMs) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.positionStore = Objects.requireNonNull(positionStore, "positionStore must not be null");
        Objects.requireNonNull(projections, "projections must not be null");
        // [SECURITY] Positive poll interval enforced — zero or negative would crash start() with
        // a RejectedExecutionException that leaves running=true but no scheduler task running.
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive, got: " + pollIntervalMs);
        }
        this.projections = List.copyOf(projections);
        this.pollIntervalMs = pollIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "projection-engine");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // [SECURITY] compareAndSet guards against double-start — calling start() twice would schedule
        // two overlapping poll loops that interleave position saves and double the event processing rate.
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("ProjectionEngine is already running — call stop() before start()");
        }
        scheduler.scheduleWithFixedDelay(this::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("ProjectionEngine started — polling every {}ms with batch size {}", pollIntervalMs, BATCH_SIZE);
    }

    public void stop() {
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ProjectionEngine did not terminate within {}s — forcing shutdown", STOP_TIMEOUT_SECONDS);
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("ProjectionEngine stopped");
    }

    public boolean isRunning() {
        return running.get() && !scheduler.isShutdown();
    }

    /**
     * Clears all backoff state so the next poll cycle runs immediately for all projections.
     * Safe to call from any thread. Intended for test isolation — call in {@code @BeforeEach}
     * to prevent accumulated backoff from one test affecting the next.
     */
    public void clearBackoffState() {
        consecutiveFailures.clear();
        backedOffUntilMs.clear();
    }

    /**
     * Resets a projection and clears its position checkpoint so the next poll
     * replays all events from the beginning.
     *
     * <p>The engine must be stopped before calling this method to avoid
     * concurrent poll/reset races.
     *
     * @param projection the projection to reset
     * @throws IllegalStateException if the engine is currently running
     */
    public void resetProjection(Projection projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        if (isRunning()) {
            throw new IllegalStateException(
                    "Stop the engine before resetting projection '" + projection.getName() + "'");
        }
        projection.reset();
        positionStore.deletePosition(projection.getName());
        consecutiveFailures.remove(projection.getName());
        backedOffUntilMs.remove(projection.getName());
        log.info("Projection '{}' reset — will replay all events on next start", projection.getName());
    }

    private void poll() {
        long now = System.currentTimeMillis();
        for (Projection projection : projections) {
            Long backoffUntil = backedOffUntilMs.get(projection.getName());
            if (backoffUntil != null && now < backoffUntil) {
                continue;
            }
            try {
                processProjection(projection);
                consecutiveFailures.remove(projection.getName());
                backedOffUntilMs.remove(projection.getName());
            } catch (Exception e) {
                // [SECURITY] Log aggregateId/eventType only — never log full payload (PII or sensitive data)
                log.error("Projection '{}' failed during poll: {}", projection.getName(), e.getMessage(), e);
                int failures = consecutiveFailures.merge(projection.getName(), 1, Integer::sum);
                // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, capped at 60s
                long backoffMs = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << Math.min(failures - 1, 5));
                backedOffUntilMs.put(projection.getName(), System.currentTimeMillis() + backoffMs);
                log.warn("Projection '{}' backing off for {}ms after {} consecutive failure(s)",
                        projection.getName(), backoffMs, failures);
            }
        }
    }

    private void processProjection(Projection projection) {
        Optional<ProjectionPosition> position = positionStore.getPosition(projection.getName());
        // [SECURITY] lastEventId null = first run → load from beginning; never use a random UUID as cursor
        UUID lastEventId = position.map(ProjectionPosition::lastEventId).orElse(null);

        // [SECURITY] At-least-once delivery: handle() + savePosition() are NOT in the same transaction.
        // Projection.handle() implementations MUST be idempotent (via event_id deduplication table)
        // so that re-delivery after a partial failure does not produce double-credit/double-debit.
        List<StoredEvent> events = eventStore.loadAllAfter(lastEventId, BATCH_SIZE);

        StoredEvent lastProcessed = null;
        for (StoredEvent event : events) {
            projection.handle(event);
            lastProcessed = event;
        }

        // [SECURITY] Position saved once per batch — idempotent handle() (via processed_projection_events)
        // protects correctness on re-delivery. Per-event saves would produce up to BATCH_SIZE UPSERTs
        // per poll with no additional correctness benefit.
        if (lastProcessed != null) {
            positionStore.savePosition(new ProjectionPosition(
                    projection.getName(),
                    lastProcessed.eventId(),
                    lastProcessed.version(),
                    Instant.now()
            ));
        }
    }
}
