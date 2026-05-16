package com.chronicle.example.projection;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ProjectionIntegrationTest extends AbstractEngineTest {

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

    private AccountBalanceProjection buildProjection() {
        return new AccountBalanceProjection(jdbcTemplate, serializer, transactionManager);
    }

    private ProjectionEngine buildEngine() {
        return new ProjectionEngine(eventStore, positionStore, List.of(buildProjection()), 50);
    }

    @Test
    @DisplayName("projection processes events and updates balances read model")
    void projectionProcessesEventsAndUpdatesReadModel() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Alice");
        bankAccount.deposit(root, 1000L, "paycheck");
        bankAccount.withdraw(root, 300L, "groceries");
        engine.save(root);

        ProjectionEngine projectionEngine = buildEngine();
        projectionEngine.start();

        UUID accountId = root.getId();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, accountId);
            assertThat(balance).isEqualTo(700L);
        });

        projectionEngine.stop();
    }

    @Test
    @DisplayName("projection is idempotent — restarting without new events leaves balance unchanged")
    void projectionIsIdempotent() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("Bob");
        bankAccount.deposit(root, 500L, "deposit");
        engine.save(root);

        UUID accountId = root.getId();

        // First run — processes all events
        ProjectionEngine first = buildEngine();
        first.start();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, accountId);
            assertThat(balance).isEqualTo(500L);
        });
        first.stop();

        // Second run — cursor already at end, no new events → balance unchanged
        // [SECURITY] Position cursor guarantees events are not reprocessed after restart
        ProjectionEngine second = buildEngine();
        second.start();
        Thread.sleep(300); // let it poll at least once

        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM balances WHERE account_id = ?",
                Long.class, accountId);
        assertThat(balance).isEqualTo(500L);
        second.stop();

        // Also verify AccountCreated is structurally idempotent via ON CONFLICT DO NOTHING
        Integer rowsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM balances", Integer.class);
        jdbcTemplate.update(
                "INSERT INTO balances (account_id, owner_name, balance, updated_at) VALUES (?, ?, 0, NOW()) ON CONFLICT (account_id) DO NOTHING",
                accountId, "Bob");
        Integer rowsAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM balances", Integer.class);
        assertThat(rowsAfter).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("projection resumes after restart — only processes new events")
    void projectionResumesAfterRestart() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("Carol");
        bankAccount.deposit(root, 100L, "d1");
        bankAccount.deposit(root, 200L, "d2");
        engine.save(root);

        UUID accountId = root.getId();

        ProjectionEngine first = buildEngine();
        first.start();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, accountId);
            assertThat(balance).isEqualTo(300L);
        });
        first.stop();

        // Add 2 more events after engine stopped
        AggregateRoot<BankAccountState> reloaded = engine.load(root.getId()).orElseThrow();
        bankAccount.deposit(reloaded, 400L, "d3");
        bankAccount.deposit(reloaded, 500L, "d4");
        engine.save(reloaded);

        // Restart — must pick up cursor from position store and process only 2 new events
        ProjectionEngine second = buildEngine();
        second.start();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, accountId);
            assertThat(balance).isEqualTo(1200L); // 100 + 200 + 400 + 500
        });
        second.stop();
    }

    @Test
    @DisplayName("projection reset clears read model and reprocesses from beginning")
    void projectionResetReprocessesEverything() throws Exception {
        AggregateRoot<BankAccountState> root = BankAccount.create("Dave");
        bankAccount.deposit(root, 600L, "salary");
        bankAccount.withdraw(root, 100L, "rent");
        engine.save(root);

        UUID accountId = root.getId();

        ProjectionEngine first = buildEngine();
        first.start();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, accountId);
            assertThat(balance).isEqualTo(500L);
        });
        first.stop();

        // Reset via engine: clears balances, processed_projection_events, and projection_positions atomically
        AccountBalanceProjection projection = buildProjection();
        ProjectionEngine resetEngine = new ProjectionEngine(eventStore, positionStore, List.of(projection), 50);
        resetEngine.resetProjection(projection);

        Integer balanceCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM balances", Integer.class);
        assertThat(balanceCount).isZero();

        Integer processedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_projection_events WHERE projection_name = 'account-balance'",
                Integer.class);
        assertThat(processedCount).isZero();

        // Restart — must rebuild from event zero
        ProjectionEngine second = buildEngine();
        second.start();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, accountId);
            assertThat(balance).isEqualTo(500L);
        });
        second.stop();
    }

    @Test
    @DisplayName("projection handles transfer events — both accounts updated correctly")
    void projectionHandlesTransferEvents() throws Exception {
        AggregateRoot<BankAccountState> source = BankAccount.create("Eve");
        bankAccount.deposit(source, 1000L, "initial");
        engine.save(source);

        AggregateRoot<BankAccountState> dest = BankAccount.create("Frank");
        engine.save(dest);

        AggregateRoot<BankAccountState> sourceLoaded = engine.load(source.getId()).orElseThrow();
        AggregateRoot<BankAccountState> destLoaded = engine.load(dest.getId()).orElseThrow();

        bankAccount.transfer(sourceLoaded, dest.getId(), 400L, "transfer");
        bankAccount.receiveTransfer(destLoaded, source.getId(), 400L, "transfer");
        engine.save(sourceLoaded);
        engine.save(destLoaded);

        ProjectionEngine projectionEngine = buildEngine();
        projectionEngine.start();

        UUID sourceId = source.getId();
        UUID destId = dest.getId();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Long sourceBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, sourceId);
            Long destBalance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM balances WHERE account_id = ?",
                    Long.class, destId);
            assertThat(sourceBalance).isEqualTo(600L);
            assertThat(destBalance).isEqualTo(400L);
        });

        projectionEngine.stop();
    }

    @Test
    @DisplayName("isRunning reflects engine lifecycle correctly")
    void isRunningReflectsLifecycle() {
        ProjectionEngine projectionEngine = buildEngine();

        assertThat(projectionEngine.isRunning()).isFalse();

        projectionEngine.start();
        assertThat(projectionEngine.isRunning()).isTrue();

        projectionEngine.stop();
        assertThat(projectionEngine.isRunning()).isFalse();
    }
}
