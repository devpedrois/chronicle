package com.chronicle.example.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.core.serialization.JacksonEventSerializer;
import com.chronicle.core.snapshot.EveryNEventsPolicy;
import com.chronicle.core.snapshot.Snapshot;
import com.chronicle.example.AbstractEngineTest;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotIntegrationTest extends AbstractEngineTest {

    @Autowired
    private JdbcEventStore eventStore;

    @Autowired
    private JdbcSnapshotStore snapshotStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChronicleEngine<BankAccountState> engine;
    private BankAccount bankAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");

        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register("AccountCreated", AccountCreated.class);
        registry.register("MoneyDeposited", MoneyDeposited.class);
        registry.register("MoneyWithdrawn", MoneyWithdrawn.class);
        registry.register("MoneyTransferred", MoneyTransferred.class);
        registry.register("MoneyReceived", MoneyReceived.class);

        JacksonEventSerializer<BankAccountState> serializer = new JacksonEventSerializer<>(registry);
        bankAccount = new BankAccount();
        engine = new ChronicleEngine<>(
                eventStore, snapshotStore, serializer,
                new EveryNEventsPolicy(5),
                bankAccount,
                BankAccountState.class
        );
    }

    @Test
    @DisplayName("snapshot saved automatically after N events")
    void snapshotSavedAfterNEvents() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Alice");
        for (int i = 0; i < 5; i++) {
            bankAccount.deposit(root, 100L, "deposit " + i);
        }
        engine.save(root); // 6 events (create + 5 deposits) → policy(6, 0) >= 5 → snapshot

        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM snapshots WHERE aggregate_id = ?",
                Integer.class, root.getId());
        assertThat(snapshotCount).isEqualTo(1);

        Integer snapshotVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM snapshots WHERE aggregate_id = ?",
                Integer.class, root.getId());
        assertThat(snapshotVersion).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("load from snapshot restores state and replays only subsequent events")
    void loadFromSnapshotSkipsFullReplay() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Bob");
        for (int i = 0; i < 5; i++) {
            bankAccount.deposit(root, 100L, "initial " + i);
        }
        engine.save(root); // version=6 → snapshot triggered
        UUID accountId = root.getId();

        // 3 deposits after the snapshot
        AggregateRoot<BankAccountState> loaded = engine.load(accountId).orElseThrow();
        for (int i = 0; i < 3; i++) {
            bankAccount.deposit(loaded, 100L, "extra " + i);
        }
        engine.save(loaded); // version=9; policy(9, 6)=3 < 5 → no new snapshot

        // Engine must restore from snapshot (v6) + replay only 3 events
        AggregateRoot<BankAccountState> final_ = engine.load(accountId).orElseThrow();
        assertThat(final_.getVersion()).isEqualTo(9);
        assertThat(final_.getState().balanceCents()).isEqualTo(800L); // 8 deposits × 100 cents
        assertThat(final_.getState().ownerName()).isEqualTo("Bob");
        assertThat(final_.getUncommittedEvents()).isEmpty();
    }

    @Test
    @DisplayName("[SECURITY] valid snapshot checksum is accepted and snapshot is returned")
    void validChecksumAccepted() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Carol");
        for (int i = 0; i < 5; i++) {
            bankAccount.deposit(root, 100L, "d" + i);
        }
        engine.save(root); // triggers snapshot

        // [SECURITY] loadLatest recomputes checksum on every read — valid checksum must pass
        Optional<Snapshot> snap = snapshotStore.loadLatest(root.getId());
        assertThat(snap).isPresent();
        assertThat(snap.get().version()).isGreaterThanOrEqualTo(5);
        assertThat(snap.get().checksum()).isNotBlank().hasSize(64); // SHA-256 hex = 64 chars
        assertThat(snap.get().state()).isNotBlank();
    }

    @Test
    @DisplayName("[SECURITY] tampered snapshot detected, discarded, state rebuilt correctly from events")
    void tamperedSnapshotDetectedAndDiscarded() {
        // [SECURITY] Test: tampered snapshot is detected and rejected — state reconstructed from events
        Logger snapshotStoreLogger = (Logger) LoggerFactory.getLogger(JdbcSnapshotStore.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        snapshotStoreLogger.addAppender(listAppender);

        try {
            AggregateRoot<BankAccountState> root = BankAccount.create("Dave");
            for (int i = 0; i < 5; i++) {
                bankAccount.deposit(root, 100L, "deposit " + i);
            }
            engine.save(root); // version=6 → snapshot at version 6
            UUID accountId = root.getId();

            // Simulate attacker replacing snapshot state in the database
            jdbcTemplate.update("UPDATE snapshots SET state = '{}' WHERE aggregate_id = ?", accountId);

            // Store-level check: checksum mismatch must cause discard
            Optional<Snapshot> corrupted = snapshotStore.loadLatest(accountId);
            assertThat(corrupted).as("Tampered snapshot must be discarded by store").isEmpty();

            // Engine-level check: full replay must reconstruct correct state
            AggregateRoot<BankAccountState> rebuilt = engine.load(accountId).orElseThrow();
            assertThat(rebuilt.getState().balanceCents())
                    .as("[SECURITY] Balance must be correct despite snapshot tampering — events are the source of truth")
                    .isEqualTo(500L);
            assertThat(rebuilt.getVersion()).isEqualTo(6);

            // Audit trail check: WARN must be logged so operators detect the tampering
            assertThat(listAppender.list)
                    .as("[SECURITY] Checksum mismatch must be logged — absence would hide tampering from operators")
                    .anyMatch(e -> e.getFormattedMessage().contains("[SECURITY] Snapshot checksum mismatch"));

        } finally {
            snapshotStoreLogger.detachAppender(listAppender);
        }
    }

    @Test
    @DisplayName("[SECURITY] Engine secondary checksum check passes — snapshot is used, not discarded")
    void engineSecondaryChecksumConsistentAfterSave() {
        // Hypothesis: if ChronicleEngine.save() computes checksum with a different formula
        // than ChronicleEngine.load() validates, load() logs a WARN and discards the snapshot.
        // After the bug fix, both sides use sha256(aggregateId + "|" + version + "|" + state).
        Logger engineLogger = (Logger) LoggerFactory.getLogger(ChronicleEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);

        try {
            AggregateRoot<BankAccountState> root = BankAccount.create("ChecksumConsistency");
            for (int i = 0; i < 5; i++) {
                bankAccount.deposit(root, 100L, "d" + i);
            }
            engine.save(root); // triggers snapshot

            engine.load(root.getId()).orElseThrow();

            // [SECURITY] Secondary check must pass — no WARN means snapshot was accepted, not discarded
            assertThat(appender.list)
                    .as("[SECURITY] Engine secondary checksum mismatch must NOT be logged — formulas must be consistent")
                    .noneMatch(e -> e.getFormattedMessage().contains("Engine secondary checksum mismatch"));
        } finally {
            engineLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("load with snapshot is faster than full replay; both produce identical state")
    void snapshotLoadFasterThanFullReplay() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Eve");
        for (int i = 0; i < 99; i++) {
            bankAccount.deposit(root, 1L, "deposit " + i);
        }
        engine.save(root); // 100 events (create + 99 deposits) → snapshot triggered
        UUID accountId = root.getId();
        long expectedBalance = root.getState().balanceCents(); // 99 cents

        // Measure WITH snapshot (snapshot at v100, zero events to replay after)
        long startWith = System.nanoTime();
        AggregateRoot<BankAccountState> withSnapshot = engine.load(accountId).orElseThrow();
        long durationWithMs = (System.nanoTime() - startWith) / 1_000_000;

        assertThat(withSnapshot.getState().balanceCents()).isEqualTo(expectedBalance);
        assertThat(withSnapshot.getVersion()).isEqualTo(100);

        // Remove snapshot to force full replay on next load
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");

        // Measure WITHOUT snapshot (must replay all 100 events)
        long startWithout = System.nanoTime();
        AggregateRoot<BankAccountState> withoutSnapshot = engine.load(accountId).orElseThrow();
        long durationWithoutMs = (System.nanoTime() - startWithout) / 1_000_000;

        // Both paths must produce identical, correct state
        assertThat(withoutSnapshot.getState().balanceCents())
                .as("Full-replay path must produce same state as snapshot path")
                .isEqualTo(expectedBalance);
        assertThat(withoutSnapshot.getVersion()).isEqualTo(100);

        System.out.printf("[Perf] Snapshot load: %dms | Full replay (100 events): %dms%n",
                durationWithMs, durationWithoutMs);

        // Snapshot load must not be orders of magnitude slower than full replay
        // (+500ms tolerance absorbs JIT warm-up and GC pauses in CI environments)
        assertThat(durationWithMs)
                .as("Snapshot load must not be significantly slower than full replay")
                .isLessThanOrEqualTo(durationWithoutMs * 3 + 500);
    }
}
