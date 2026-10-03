package com.baran.recon.application.run;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;

/**
 * The application's own store, wrapped so a test can hold a run inside its work transaction, after
 * its RUNNING row has committed: while a source is held, the run's first count waits there until
 * the test releases it. A test can then see what a second run does while the first is running.
 * Every wait is bounded, so a test that forgets to release fails instead of hanging.
 */
final class HeldLedgerEntryStore implements LedgerEntryStore {

    private static final Duration LONGEST_HOLD = Duration.ofSeconds(30);

    private final LedgerEntryStore delegate;
    private final Map<SourceCode, CountDownLatch> held = new ConcurrentHashMap<>();
    private final Semaphore entered = new Semaphore(0);

    private HeldLedgerEntryStore(LedgerEntryStore delegate) {
        this.delegate = delegate;
    }

    static HeldLedgerEntryStore of(LedgerEntryStore injected) {
        return (HeldLedgerEntryStore) injected;
    }

    /** From now on, a run's work on the source waits inside its transaction until released. */
    void hold(SourceCode source) {
        entered.drainPermits();
        held.put(source, new CountDownLatch(1));
    }

    void release(SourceCode source) {
        CountDownLatch latch = held.remove(source);
        if (latch != null) {
            latch.countDown();
        }
    }

    /** True once this many runs are waiting inside their work, each counted once. */
    boolean awaitEntered(int runs, Duration timeout) throws InterruptedException {
        return entered.tryAcquire(runs, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public long countInScope(SourceCode source, LocalDate from, LocalDate to) {
        CountDownLatch latch = held.get(source);
        if (latch != null) {
            entered.release();
            awaitRelease(latch);
        }
        return delegate.countInScope(source, from, to);
    }

    @Override
    public long countWithoutValueDate(SourceCode source) {
        return delegate.countWithoutValueDate(source);
    }

    @Override
    public boolean storeIfAbsent(LedgerEntry entry) {
        return delegate.storeIfAbsent(entry);
    }

    @Override
    public List<Boolean> storeAllIfAbsent(List<LedgerEntry> entries) {
        return delegate.storeAllIfAbsent(entries);
    }

    @Override
    public Optional<LedgerEntry> findById(UUID id) {
        return delegate.findById(id);
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(LONGEST_HOLD.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("a held run was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while held", interrupted);
        }
    }

    /** Import into a test context to wrap its store. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Injection {

        @Bean
        static BeanPostProcessor holdLedgerEntryStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof LedgerEntryStore store && !(bean instanceof HeldLedgerEntryStore)
                            ? new HeldLedgerEntryStore(store)
                            : bean;
                }
            };
        }
    }
}
