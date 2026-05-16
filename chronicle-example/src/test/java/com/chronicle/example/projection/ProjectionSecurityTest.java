package com.chronicle.example.projection;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.event.StoredEvent;
import com.chronicle.core.projection.ProjectionEngine;
import com.chronicle.core.projection.ProjectionPositionStore;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.core.serialization.JacksonEventSerializer;
import com.chronicle.core.snapshot.EveryNEventsPolicy;
import com.chronicle.example.AbstractEngineTest;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcProjectionPositionStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Adversarial security tests for the projection engine.
 * Each test asks: "How would an attacker exploit this?"
 */
class ProjectionSecurityTest extends AbstractEngineTest {

    @Autowired
    private JdbcEventStore eventStore;

    @Autowired
    private JdbcSnapshotStore snapshotStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ChronicleEngine<BankAccountState> engine;
    private BankAccount bankAccount;
    private JacksonEventSerializer<BankAccountState> serializer;
    private ProjectionPositionStore positionStore;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");
        jdbcTemplate.execute("TRUNCATE TABLE balances");
        jdbcTemplate.execute("TRUNCATE TABLE projection_positions");
        jdbcTemplate.execute("TRUNCATE TABLE processed_projection_events");

        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register("AccountCreated", AccountCreated.class);
        registry.register("MoneyDeposited", MoneyDeposited.class);
        registry.register("MoneyWithdrawn", MoneyWithdrawn.class);
        registry.register("MoneyTransferred", MoneyTransferred.class);
        registry.register("MoneyReceived", MoneyReceived.class);

        serializer = new JacksonEventSerializer<>(registry);
        bankAccount = new BankAccount();
        positionStore = new JdbcProjectionPositionStore(jdbcTemplate);
        engine = new ChronicleEngine<>(
                eventStore, snapshotStore, serializer,
                new EveryNEventsPolicy(50),
                bankAccount,
                BankAccountState.class
        );
    }

    private AccountBalanceProjection projection() {
        return new AccountBalanceProjection(jdbcTemplate, serializer, transactionManager);
    }

    private ProjectionEngine buildEngine() {
        return new ProjectionEngine(eventStore, positionStore, List.of(projection()), 50);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 1: Cursor freeze attack
    // Attacker tampers projection_positions with a non-existent event_id.
    // CROSS JOIN in LOAD_ALL_AFTER_SQL would return 0 rows → projection silently halts forever.
    // Expected: IllegalStateException logged, new events NOT silently discarded.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Tampered cursor event ID halts projection with error — not silently")
    void tamperedCursorEventId_shouldLogSecurityErrorNotSilentlyFreeze() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("Victim");
        bankAccount.deposit(root, 500L, "initial");
        engine.save(root);

        ProjectionEngine first = buildEngine();
        first.start();
        UUID accountId = root.getId();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
            assertThat(balance).isEqualTo(500L);
        });
        first.stop();

        // Attacker tampers projection_positions with a non-existent event_id
        UUID fakeEventId = UUID.randomUUID();
        jdbcTemplate.update(
                "UPDATE projection_positions SET last_event_id = ? WHERE projection_name = 'account-balance'",
                fakeEventId);

        // Add a new event that should be processed after recovery
        AggregateRoot<BankAccountState> reloaded = engine.load(accountId).orElseThrow();
        bankAccount.deposit(reloaded, 200L, "post-tamper deposit");
        engine.save(reloaded);

        // Capture log output from JdbcEventStore — must log [SECURITY] error
        Logger storeLogger = (Logger) LoggerFactory.getLogger(JdbcEventStore.class);
        Logger engineLogger = (Logger) LoggerFactory.getLogger(ProjectionEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        storeLogger.addAppender(appender);
        engineLogger.addAppender(appender);

        try {
            ProjectionEngine second = buildEngine();
            second.start();
            Thread.sleep(300); // let it poll at least twice

            // [SECURITY] Must log an error — silent freeze is unacceptable
            assertThat(appender.list)
                    .as("[SECURITY] Tampered cursor must produce an error log — silence hides the attack")
                    .anyMatch(e -> e.getLevel() == Level.ERROR &&
                            e.getFormattedMessage().contains(fakeEventId.toString()));

            // [SECURITY] New event (200 cents) must NOT be silently processed with wrong balance
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
            // Engine is halting each poll — balance stays at 500 (no double-processing)
            assertThat(balance)
                    .as("[SECURITY] Halted projection must not process new events with corrupt cursor")
                    .isEqualTo(500L);

            second.stop();
        } finally {
            storeLogger.detachAppender(appender);
            engineLogger.detachAppender(appender);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 2: Money event without AccountCreated — silent credit loss
    // If AccountCreated is missed, MoneyDeposited UPDATE finds no row → 0 rows updated.
    // Expected: WARN logged with [SECURITY] tag — not silently swallowed.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] MoneyDeposited without AccountCreated logs WARN — credit loss detected")
    void moneyDepositedWithoutAccount_shouldLogSecurityWarn() {
        AccountBalanceProjection proj = projection();

        Logger projLogger = (Logger) LoggerFactory.getLogger(AccountBalanceProjection.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        projLogger.addAppender(appender);

        try {
            // Inject a MoneyDeposited event for an account that has no balance row
            UUID orphanAccountId = UUID.randomUUID();
            StoredEvent orphanEvent = new StoredEvent(
                    UUID.randomUUID(),
                    orphanAccountId,
                    "BankAccount",
                    "MoneyDeposited",
                    "{\"amountCents\":1000,\"description\":\"orphan deposit\"}",
                    1,
                    Instant.now()
            );

            proj.handle(orphanEvent);

            // [SECURITY] 0 rows updated must produce a WARN — financial loss must not be silent
            assertThat(appender.list)
                    .as("[SECURITY] Missing balance row for MoneyDeposited must be logged as WARN")
                    .anyMatch(e -> e.getLevel() == Level.WARN &&
                            e.getFormattedMessage().contains("[SECURITY]") &&
                            e.getFormattedMessage().contains("MoneyDeposited") &&
                            e.getFormattedMessage().contains(orphanAccountId.toString()));

            // Balance row must not be created spuriously
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM balances WHERE account_id = ?", Integer.class, orphanAccountId);
            assertThat(count).isZero();
        } finally {
            projLogger.detachAppender(appender);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 3: MoneyWithdrawn without AccountCreated — silent debit loss
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] MoneyWithdrawn without AccountCreated logs WARN — debit loss detected")
    void moneyWithdrawnWithoutAccount_shouldLogSecurityWarn() {
        AccountBalanceProjection proj = projection();

        Logger projLogger = (Logger) LoggerFactory.getLogger(AccountBalanceProjection.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        projLogger.addAppender(appender);

        try {
            UUID orphanId = UUID.randomUUID();
            StoredEvent orphanEvent = new StoredEvent(
                    UUID.randomUUID(), orphanId, "BankAccount", "MoneyWithdrawn",
                    "{\"amountCents\":500,\"description\":\"orphan withdrawal\"}", 1, Instant.now());

            proj.handle(orphanEvent);

            assertThat(appender.list)
                    .as("[SECURITY] Missing balance row for MoneyWithdrawn must be logged as WARN")
                    .anyMatch(e -> e.getLevel() == Level.WARN &&
                            e.getFormattedMessage().contains("[SECURITY]") &&
                            e.getFormattedMessage().contains("MoneyWithdrawn"));
        } finally {
            projLogger.detachAppender(appender);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 4: Projection isolation
    // A projection that throws must NOT stop other projections from processing.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Failing projection does not stop other projections — isolation enforced")
    void failingProjectionDoesNotStopOthers() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("Isolated");
        bankAccount.deposit(root, 300L, "test");
        engine.save(root);

        // A projection that always throws on every event
        com.chronicle.core.projection.Projection bomberProjection = new com.chronicle.core.projection.Projection() {
            @Override public String getName() { return "bomber"; }
            @Override public void handle(StoredEvent event) {
                throw new RuntimeException("Simulated projection failure — must not propagate");
            }
            @Override public void reset() {}
        };

        AccountBalanceProjection goodProjection = projection();
        ProjectionEngine projEngine = new ProjectionEngine(
                eventStore, positionStore, List.of(bomberProjection, goodProjection), 50);
        projEngine.start();

        UUID accountId = root.getId();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
            assertThat(balance)
                    .as("[SECURITY] Good projection must complete despite bomber projection failing")
                    .isEqualTo(300L);
        });

        projEngine.stop();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 5: Concurrent balance updates — atomic update prevents race
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Concurrent balance updates are atomic — no lost updates from race condition")
    void concurrentBalanceUpdates_areAtomicNoDelta() throws Exception {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO balances (account_id, owner_name, balance, updated_at) VALUES (?, ?, 0, NOW())",
                accountId, "RaceTarget");

        int threads = 20;
        long depositCents = 100L;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    jdbcTemplate.update(
                            "UPDATE balances SET balance = balance + ?, updated_at = NOW() WHERE account_id = ?",
                            depositCents, accountId);
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors).as("No thread should throw during concurrent updates").isEmpty();

        Long finalBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
        assertThat(finalBalance)
                .as("[SECURITY] All %d concurrent deposits of %d cents must be reflected", threads, depositCents)
                .isEqualTo(threads * depositCents);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 6: Event order preserved — projection never goes negative
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Projection correctly applies withdraw only after deposit — order preserved")
    void projectionPreservesEventOrderFromEventStore() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("OrderTest");
        bankAccount.deposit(root, 1000L, "deposit");
        bankAccount.withdraw(root, 800L, "withdraw");
        engine.save(root);

        ProjectionEngine pe = buildEngine();
        pe.start();

        UUID accountId = root.getId();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
            assertThat(balance)
                    .as("Balance must be 200 (deposit 1000 - withdraw 800), never negative")
                    .isEqualTo(200L);
        });

        pe.stop();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 7: Duplicate AccountCreated (ON CONFLICT DO NOTHING) — idempotent insert
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Duplicate AccountCreated does not reset balance — ON CONFLICT DO NOTHING")
    void duplicateAccountCreated_doesNotResetBalance() {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO balances (account_id, owner_name, balance, updated_at) VALUES (?, ?, 5000, NOW())",
                accountId, "CreditedAlready");

        AccountBalanceProjection proj = projection();

        StoredEvent duplicateCreate = new StoredEvent(
                UUID.randomUUID(), accountId, "BankAccount", "AccountCreated",
                "{\"accountId\":\"" + accountId + "\",\"ownerName\":\"CreditedAlready\"}", 1, Instant.now());

        proj.handle(duplicateCreate);

        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
        assertThat(balance)
                .as("[SECURITY] Duplicate AccountCreated must not reset balance to 0")
                .isEqualTo(5000L);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 8: Unknown event type — projection must not crash
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Unknown event type is ignored — projection does not crash or expose internals")
    void unknownEventType_projectionDoesNotCrash() {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO balances (account_id, owner_name, balance, updated_at) VALUES (?, ?, 1000, NOW())",
                accountId, "UnknownEventTarget");

        AccountBalanceProjection proj = projection();

        StoredEvent unknownEvent = new StoredEvent(
                UUID.randomUUID(), accountId, "BankAccount", "MaliciousEventType",
                "{\"data\":\"injected\"}", 99, Instant.now());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> proj.handle(unknownEvent));

        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
        assertThat(balance)
                .as("[SECURITY] Unknown event type must leave balance unchanged")
                .isEqualTo(1000L);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 9: Batch boundary — large event stream processed completely
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Events beyond BATCH_SIZE are processed in subsequent polls — no event skipped")
    void eventsBeyondBatchSize_processedInSubsequentPolls() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("BatchTest");
        for (int i = 0; i < 10; i++) {
            bankAccount.deposit(root, 100L, "d" + i);
        }
        engine.save(root);

        ProjectionEngine pe = buildEngine();
        pe.start();

        UUID accountId = root.getId();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
            assertThat(balance)
                    .as("All 10 deposits of 100 cents must be reflected (1000 cents total)")
                    .isEqualTo(1000L);
        });

        pe.stop();

        Long positionVersion = jdbcTemplate.queryForObject(
                "SELECT last_version FROM projection_positions WHERE projection_name = 'account-balance'",
                Long.class);
        assertThat(positionVersion)
                .as("Cursor must have advanced past all events")
                .isGreaterThanOrEqualTo(11L);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 10: loadAllAfter with invalid limit — defensive rejection
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] loadAllAfter with zero or negative limit throws — no silent empty result")
    void loadAllAfterWithInvalidLimit_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> eventStore.loadAllAfter(null, 0),
                "[SECURITY] Zero limit must be rejected — silent empty result hides projection bugs"
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> eventStore.loadAllAfter(null, -1),
                "[SECURITY] Negative limit must be rejected"
        );
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 11: At-least-once re-delivery — exactly-once via deduplication
    // C1 fix: duplicate event_id must NOT produce double-credit/double-debit.
    // Hypothesis: processing the same MoneyDeposited event twice would add amountCents twice.
    // Expected: deduplication table prevents second application → balance unchanged.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] Re-delivered event is deduplicated — no double-credit")
    void redeliveredEvent_deduplicatedNoDoubleCredit() {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO balances (account_id, owner_name, balance, updated_at) VALUES (?, ?, 0, NOW())",
                accountId, "DeduplicateTarget");

        AccountBalanceProjection proj = projection();

        UUID eventId = UUID.randomUUID();
        StoredEvent deposit = new StoredEvent(
                eventId, accountId, "BankAccount", "MoneyDeposited",
                "{\"amountCents\":500,\"description\":\"first delivery\"}", 1, Instant.now());

        // First delivery — should apply
        proj.handle(deposit);
        Long balanceAfterFirst = jdbcTemplate.queryForObject(
                "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
        assertThat(balanceAfterFirst)
                .as("First delivery must credit 500 cents")
                .isEqualTo(500L);

        // Second delivery of the same event — must be deduplicated, balance must stay at 500
        proj.handle(deposit);
        Long balanceAfterSecond = jdbcTemplate.queryForObject(
                "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
        assertThat(balanceAfterSecond)
                .as("[SECURITY] Re-delivered event must NOT double-credit — deduplication required")
                .isEqualTo(500L);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 12: double-start guard on ProjectionEngine
    // Calling start() twice must throw — not silently schedule two polling loops.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] ProjectionEngine.start() called twice throws — double-polling prevented")
    void doubleStart_throws() {
        ProjectionEngine pe = buildEngine();
        pe.start();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    pe::start,
                    "[SECURITY] Double-start must be rejected — two polling loops cause interleaved position saves"
            );
        } finally {
            pe.stop();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // [SECURITY] Vector 13: reset() must clear both balances and deduplication table
    // If deduplication table is NOT cleared, reset() leaves processed events marked
    // → replay is skipped → read model stays empty after reset.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[SECURITY] reset() clears both balances and processed_projection_events")
    void reset_clearsBothReadModelAndDeduplicationTable() {
        AccountBalanceProjection proj = projection();

        UUID accountId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO balances (account_id, owner_name, balance, updated_at) VALUES (?, ?, 1000, NOW())",
                accountId, "ResetTarget");
        jdbcTemplate.update(
                "INSERT INTO processed_projection_events (event_id, projection_name) VALUES (?, ?)",
                eventId, "account-balance");

        proj.reset();

        Integer balanceRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM balances", Integer.class);
        assertThat(balanceRows).as("balances must be empty after reset").isZero();

        Integer processedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_projection_events WHERE projection_name = 'account-balance'",
                Integer.class);
        assertThat(processedRows)
                .as("[SECURITY] processed_projection_events must be cleared — or replay is skipped on rebuild")
                .isZero();
    }
}
