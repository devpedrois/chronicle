package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent registration tests for EventTypeRegistry.
 * // [SECURITY] TOCTOU — validates that putIfAbsent prevents type confusion under concurrent load
 */
class EventTypeRegistryConcurrencyTest {

    record ConcreteEvent(String data) implements DomainEvent {}
    record ImposterEvent(String data) implements DomainEvent {}

    @RepeatedTest(20)
    @DisplayName("[SECURITY] concurrent registration of same type — exactly one thread wins, no type confusion")
    void shouldAllowOnlyOneRegistrationUnderConcurrentLoad() throws Exception {
        EventTypeRegistry registry = new EventTypeRegistry();
        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // alternate between two different classes trying to register the same type name
            Future<?>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                final Class<? extends DomainEvent> clazz = (i % 2 == 0) ? ConcreteEvent.class : ImposterEvent.class;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        registry.register("SharedType", clazz);
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        failureCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            ready.await();
            go.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }

        // Exactly one thread must win — no silent overwrite, no double registration
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threads - 1);

        // The registered class must be consistently resolved — no type confusion
        Class<?> resolved = registry.resolve("SharedType");
        assertThat(resolved).isIn(ConcreteEvent.class, ImposterEvent.class);
    }

    @Test
    @DisplayName("[SECURITY] concurrent reads of registered type are consistent")
    void shouldResolveConsistentlyUnderConcurrentReads() throws Exception {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register("ReadEvent", ConcreteEvent.class);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        AtomicInteger mismatch = new AtomicInteger(0);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        Class<?> resolved = registry.resolve("ReadEvent");
                        if (resolved != ConcreteEvent.class) {
                            mismatch.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } finally {
            pool.shutdownNow();
        }

        assertThat(mismatch.get()).isZero();
    }
}
