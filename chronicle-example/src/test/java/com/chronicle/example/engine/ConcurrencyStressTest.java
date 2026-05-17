package com.chronicle.example.engine;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.projection.ProjectionEngine;
import com.chronicle.core.projection.ProjectionPositionStore;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.core.serialization.JacksonEventSerializer;
import com.chronicle.core.store.ConcurrentModificationException;
import com.chronicle.example.AbstractEngineTest;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.domain.InsufficientFundsException;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import com.chronicle.example.projection.AccountBalanceProjection;
import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcProjectionPositionStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// [SECURITY] Stress test suite — verifies system invariants under high concurrency
class ConcurrencyStressTest extends AbstractEngineTest {

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
        // [SECURITY] Snapshot disabled in stress tests — focus on optimistic lock/retry correctness
        engine = new ChronicleEngine<>(
                eventStore, snapshotStore, serializer,
                (current, last) -> false,
                bankAccount,
                BankAccountState.class
        );
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("stress: 10 threads x 100 deposits — zero data loss")
    // [SECURITY] Stress test: verifies ZERO deposits lost under high concurrency
    void tenThreadsHundredDepositsEach_zeroDataLoss() throws InterruptedException, ExecutionException {
        AggregateRoot<BankAccountState> initial = BankAccount.create("StressTestUser-1");
        engine.save(initial);
        UUID accountId = initial.getId();

        int threadCount = 10;
        int depositsPerThread = 100;
        // [SECURITY] 20 retries with exponential backoff: under 10-thread contention,
        // p(success per attempt)=1/10 → need ~log(0.001)/log(0.9) ≈ 66 attempts for 99.9%.
        // Backoff spreads retries over time, reducing effective contention to ~2-3 threads per slot.
        // Empirically, 20 retries + jitter is sufficient in all observed runs.
        int maxRetries = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < depositsPerThread; i++) {
                    boolean success = false;
                    for (int attempt = 0; attempt < maxRetries && !success; attempt++) {
                        try {
                            AggregateRoot<BankAccountState> root = engine.load(accountId).orElseThrow();
                            bankAccount.deposit(root, 1L, "Thread-" + threadId + "-deposit-" + i);
                            engine.save(root);
                            success = true;
                        } catch (ConcurrentModificationException e) {
                            if (attempt < maxRetries - 1) {
                                long jitter = (long) (Math.random() * 15);
                                Thread.sleep(3L * (1L << Math.min(attempt, 6)) + jitter);
                            }
                        }
                    }
                    assertThat(success)
                            .as("Thread %d deposit %d must succeed within %d retries", threadId, i, maxRetries)
                            .isTrue();
                }
                return true;
            }));
        }

        startLatch.countDown();
        for (Future<Boolean> f : futures) {
            f.get();
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        AggregateRoot<BankAccountState> result = engine.load(accountId).orElseThrow();
        // 10 threads × 100 deposits × 1 cent = 1000 cents
        assertThat(result.getState().balanceCents())
                .as("All 1000 deposits of 1 cent must be persisted — zero data loss")
                .isEqualTo(1000L);
        // 1 create event + 1000 deposit events = 1001 total
        assertThat(eventStore.load(accountId))
                .as("Event stream must have exactly 1001 events: 1 create + 1000 deposits")
                .hasSize(1001);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("stress: 5 threads simultaneous withdrawals — balance never negative")
    // [SECURITY] Stress test: optimistic locking prevents negative balance under concurrent withdrawals
    void fiveThreadsConcurrentWithdrawals_balanceNeverNegative() throws InterruptedException, ExecutionException {
        AggregateRoot<BankAccountState> initial = BankAccount.create("StressTestUser-2");
        bankAccount.deposit(initial, 1000L, "seed");
        engine.save(initial);
        UUID accountId = initial.getId();

        int threadCount = 5;
        int maxRetries = 15;
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int attempt = 0; attempt < maxRetries; attempt++) {
                    try {
                        AggregateRoot<BankAccountState> root = engine.load(accountId).orElseThrow();
                        bankAccount.withdraw(root, 300L, "concurrent-withdraw");
                        engine.save(root);
                        successCount.incrementAndGet();
                        break;
                    } catch (ConcurrentModificationException e) {
                        if (attempt < maxRetries - 1) {
                            long jitter = (long) (Math.random() * 20);
                            Thread.sleep(2L * (1L << Math.min(attempt, 6)) + jitter);
                        }
                    } catch (InsufficientFundsException e) {
                        // Balance exhausted — stop retrying this thread
                        break;
                    }
                }
                return null;
            }));
        }

        startLatch.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        AggregateRoot<BankAccountState> result = engine.load(accountId).orElseThrow();
        long finalBalance = result.getState().balanceCents();

        // [SECURITY] Core invariant: balance MUST NEVER be negative
        assertThat(finalBalance)
                .as("Balance must never go negative under concurrent withdrawals")
                .isGreaterThanOrEqualTo(0L);
        // 1000 cents / 300 cents per withdrawal = at most 3 successful withdrawals
        assertThat(successCount.get())
                .as("At most 3 withdrawals of 300 cents succeed against 1000-cent balance")
                .isLessThanOrEqualTo(3);
        // Exactly 3 threads MUST succeed (1000 / 300 = 3 remainder 100)
        assertThat(successCount.get()).isEqualTo(3);
        // Final balance = 1000 - 3 * 300 = 100
        assertThat(finalBalance)
                .as("Final balance must be 100 cents (1000 - 3x300)")
                .isEqualTo(100L);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("stress: projection eventual consistency under load")
    // [SECURITY] Verifies projection converges to correct state under concurrent writes
    void projectionEventualConsistencyUnderLoad() throws InterruptedException, ExecutionException {
        AggregateRoot<BankAccountState> initial = BankAccount.create("StressTestUser-3");
        engine.save(initial);
        UUID accountId = initial.getId();

        int threadCount = 5;
        int depositsPerThread = 50;
        long amountCents = 10L;
        int maxRetries = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < depositsPerThread; i++) {
                    boolean success = false;
                    for (int attempt = 0; attempt < maxRetries && !success; attempt++) {
                        try {
                            AggregateRoot<BankAccountState> root = engine.load(accountId).orElseThrow();
                            bankAccount.deposit(root, amountCents, "stress-projection-" + threadId + "-" + i);
                            engine.save(root);
                            success = true;
                        } catch (ConcurrentModificationException e) {
                            if (attempt < maxRetries - 1) {
                                long jitter = (long) (Math.random() * 20);
                                Thread.sleep(2L * (1L << Math.min(attempt, 6)) + jitter);
                            }
                        }
                    }
                    assertThat(success)
                            .as("Thread %d deposit %d must succeed within %d retries", threadId, i, maxRetries)
                            .isTrue();
                }
                return true;
            }));
        }

        startLatch.countDown();
        for (Future<Boolean> f : futures) {
            f.get();
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // 5 threads × 50 deposits × 10 cents = 2500 cents expected
        long expectedBalance = (long) threadCount * depositsPerThread * amountCents;

        ProjectionPositionStore positionStore = new JdbcProjectionPositionStore(jdbcTemplate);
        AccountBalanceProjection projection = new AccountBalanceProjection(jdbcTemplate, serializer, transactionManager);
        ProjectionEngine projectionEngine = new ProjectionEngine(eventStore, positionStore, List.of(projection), 100);
        projectionEngine.start();

        try {
            long deadline = System.currentTimeMillis() + 30_000;
            Long balance = null;
            while (System.currentTimeMillis() < deadline) {
                List<Long> rows = jdbcTemplate.queryForList(
                        "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
                if (!rows.isEmpty()) {
                    balance = rows.get(0);
                    if (balance != null && balance == expectedBalance) {
                        break;
                    }
                }
                Thread.sleep(200);
            }
            assertThat(balance)
                    .as("Projection must converge to %d cents (5 threads × 50 deposits × 10 cents)", expectedBalance)
                    .isEqualTo(expectedBalance);
        } finally {
            projectionEngine.stop();
        }
    }
}
