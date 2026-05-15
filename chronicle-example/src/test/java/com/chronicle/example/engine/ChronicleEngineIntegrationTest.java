package com.chronicle.example.engine;

import com.chronicle.core.aggregate.AggregateRoot;
import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.core.serialization.JacksonEventSerializer;
import com.chronicle.core.store.ConcurrentModificationException;
import com.chronicle.example.AbstractEngineTest;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.domain.InsufficientFundsException;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChronicleEngineIntegrationTest extends AbstractEngineTest {

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

        JacksonEventSerializer<BankAccountState> serializer = new JacksonEventSerializer<>(registry);
        bankAccount = new BankAccount();
        // [SECURITY] SnapshotPolicy returns false: no snapshots taken in this suite.
        // Keeps focus on engine/optimistic-locking; snapshot path covered in PR #4.
        engine = new ChronicleEngine<>(
                eventStore, snapshotStore, serializer,
                (current, last) -> false,
                bankAccount,
                BankAccountState.class
        );
    }

    @Test
    @DisplayName("engine load and save roundtrip preserves state")
    void shouldSaveAndLoadRoundtrip() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Alice");
        bankAccount.deposit(root, 500L, "initial deposit");
        engine.save(root);
        UUID accountId = root.getId();

        AggregateRoot<BankAccountState> loaded = engine.load(accountId).orElseThrow();

        assertThat(loaded.getState().balanceCents()).isEqualTo(500L);
        assertThat(loaded.getState().ownerName()).isEqualTo("Alice");
        assertThat(loaded.getState().active()).isTrue();
        assertThat(loaded.getVersion()).isEqualTo(2); // create + deposit
        assertThat(loaded.getUncommittedEvents()).isEmpty();
    }

    @Test
    @DisplayName("multiple saves preserve sequential version and state")
    void shouldPreserveVersionAcrossMultipleSaves() {
        AggregateRoot<BankAccountState> root = BankAccount.create("Bob");
        engine.save(root);
        UUID accountId = root.getId();

        AggregateRoot<BankAccountState> v2 = engine.load(accountId).orElseThrow();
        bankAccount.deposit(v2, 1000L, "deposit");
        engine.save(v2);

        AggregateRoot<BankAccountState> v3 = engine.load(accountId).orElseThrow();
        bankAccount.withdraw(v3, 400L, "withdrawal");
        engine.save(v3);

        AggregateRoot<BankAccountState> final_ = engine.load(accountId).orElseThrow();
        assertThat(final_.getVersion()).isEqualTo(3); // create + deposit + withdraw
        assertThat(final_.getState().balanceCents()).isEqualTo(600L);
    }

    @Test
    @DisplayName("optimistic lock conflict detected when two roots diverge from same version")
    // [SECURITY] Optimistic Locking — verifies version injection from stale reads is blocked
    void shouldDetectOptimisticLockConflict() {
        AggregateRoot<BankAccountState> initial = BankAccount.create("Carol");
        bankAccount.deposit(initial, 1000L, "seed");
        engine.save(initial);
        UUID accountId = initial.getId();

        // Both rootA and rootB loaded at the same version
        AggregateRoot<BankAccountState> rootA = engine.load(accountId).orElseThrow();
        AggregateRoot<BankAccountState> rootB = engine.load(accountId).orElseThrow();

        // Thread A saves first — succeeds, DB now at version 3
        bankAccount.withdraw(rootA, 100L, "A withdraws");
        engine.save(rootA);

        // Thread B tries to save from stale version 2 — must fail
        bankAccount.deposit(rootB, 50L, "B deposits");
        assertThatThrownBy(() -> engine.save(rootB))
                .isInstanceOf(ConcurrentModificationException.class);

        // Event stream must reflect only A's write
        AggregateRoot<BankAccountState> consistent = engine.load(accountId).orElseThrow();
        assertThat(consistent.getVersion()).isEqualTo(3);
        assertThat(consistent.getState().balanceCents()).isEqualTo(900L);
    }

    @Test
    @DisplayName("10 concurrent deposits — no deposit lost under high concurrency")
    // [SECURITY] Stress test: verifies no deposits lost; optimistic lock + retry = exactly-once semantics
    void shouldNotLoseDepositsUnderHighConcurrency() throws InterruptedException {
        AggregateRoot<BankAccountState> initial = BankAccount.create("Dave");
        engine.save(initial);
        UUID accountId = initial.getId();

        int threadCount = 10;
        int maxRetries = 15;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    doneLatch.countDown();
                    return;
                }
                boolean succeeded = false;
                for (int attempt = 0; attempt < maxRetries && !succeeded; attempt++) {
                    try {
                        AggregateRoot<BankAccountState> root = engine.load(accountId).orElseThrow();
                        bankAccount.deposit(root, 100L, "concurrent deposit");
                        engine.save(root);
                        succeeded = true;
                    } catch (ConcurrentModificationException e) {
                        if (attempt < maxRetries - 1) {
                            try {
                                // Jitter prevents synchronized retry storms — without jitter
                                // all threads back off and hammer the DB at the same instant
                                long jitter = (long) (Math.random() * 15);
                                Thread.sleep(3L * (1L << Math.min(attempt, 6)) + jitter);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                doneLatch.countDown();
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        AggregateRoot<BankAccountState> result = engine.load(accountId).orElseThrow();
        assertThat(result.getState().balanceCents())
                .as("All 10 deposits of 100 cents must be persisted — no deposit lost")
                .isEqualTo(1000L);
        assertThat(result.getVersion())
                .as("1 create event + 10 deposit events = version 11")
                .isEqualTo(11);
    }

    @Test
    @DisplayName("concurrent withdrawals never drive balance negative")
    // [SECURITY] Stress test: balance invariant must hold under concurrent writes — no negative balance
    void shouldRespectBalanceLimitUnderConcurrentWithdrawals() throws InterruptedException {
        AggregateRoot<BankAccountState> initial = BankAccount.create("Eve");
        bankAccount.deposit(initial, 500L, "seed");
        engine.save(initial);
        UUID accountId = initial.getId();

        int threadCount = 5;
        int maxRetries = 15;
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    doneLatch.countDown();
                    return;
                }
                for (int attempt = 0; attempt < maxRetries; attempt++) {
                    try {
                        AggregateRoot<BankAccountState> root = engine.load(accountId).orElseThrow();
                        bankAccount.withdraw(root, 200L, "concurrent withdrawal");
                        engine.save(root);
                        successCount.incrementAndGet();
                        break;
                    } catch (ConcurrentModificationException e) {
                        if (attempt < maxRetries - 1) {
                            try {
                                long jitter = (long) (Math.random() * 15);
                                Thread.sleep(3L * (1L << Math.min(attempt, 6)) + jitter);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } catch (InsufficientFundsException e) {
                        // Balance exhausted for this thread — stop retrying
                        break;
                    }
                }
                doneLatch.countDown();
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        AggregateRoot<BankAccountState> result = engine.load(accountId).orElseThrow();
        long finalBalance = result.getState().balanceCents();

        // [SECURITY] Core invariant: balance MUST NEVER be negative — each cent accounted for
        assertThat(finalBalance)
                .as("Balance must never go negative under concurrent withdrawals")
                .isGreaterThanOrEqualTo(0L);
        assertThat(successCount.get())
                .as("At most 2 withdrawals of 200 cents can succeed against a 500-cent balance")
                .isLessThanOrEqualTo(2);
        // Consistency check: each successful withdrawal must be reflected in the final balance
        assertThat(500L - finalBalance)
                .as("Balance delta must equal exactly (successCount * 200 cents)")
                .isEqualTo((long) successCount.get() * 200L);
    }
}
