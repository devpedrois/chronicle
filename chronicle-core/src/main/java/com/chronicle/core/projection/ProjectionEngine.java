package com.chronicle.core.projection;

import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the event store and dispatches new events to registered projections.
 * Each projection tracks its own checkpoint; processing is independent.
 */
public class ProjectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ProjectionEngine.class);

    private final EventStore eventStore;
    private final ProjectionPositionStore positionStore;
    private final List<Projection> projections;
    private final ScheduledExecutorService scheduler;
    private final long pollIntervalMs;

    public ProjectionEngine(
            EventStore eventStore,
            ProjectionPositionStore positionStore,
            List<Projection> projections,
            long pollIntervalMs) {
        this.eventStore = eventStore;
        this.positionStore = positionStore;
        this.projections = List.copyOf(projections);
        this.pollIntervalMs = pollIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "projection-engine");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("ProjectionEngine started — polling every {}ms", pollIntervalMs);
    }

    public void stop() {
        scheduler.shutdown();
    }

    private void poll() {
        for (Projection projection : projections) {
            try {
                processProjection(projection);
            } catch (Exception e) {
                log.error("Error processing projection {}: {}", projection.getName(), e.getMessage(), e);
            }
        }
    }

    private void processProjection(Projection projection) {
        var position = positionStore.getPosition(projection.getName());
        int afterVersion = position.map(p -> (int) p.lastVersion()).orElse(0);

        List<StoredEvent> events = eventStore.loadAfterVersion(UUID.randomUUID(), afterVersion);

        for (StoredEvent event : events) {
            projection.handle(event);
            positionStore.savePosition(new ProjectionPosition(
                    projection.getName(),
                    event.eventId(),
                    event.version(),
                    java.time.Instant.now()
            ));
        }
    }
}
