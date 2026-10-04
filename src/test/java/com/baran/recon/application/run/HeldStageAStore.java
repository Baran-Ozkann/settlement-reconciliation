package com.baran.recon.application.run;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.baran.recon.application.port.StageAStore;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own Stage A, wrapped so a test can hold a run between its first statement and
 * the rest: while a source is held, the run waits right after A1 has run, inside its work
 * transaction, until the test releases it. A1 is the work transaction's first statement, so the run
 * has read the database by then. Every wait is bounded, so a test that forgets to release fails
 * instead of hanging.
 */
final class HeldStageAStore implements StageAStore {

    private static final Duration LONGEST_HOLD = Duration.ofSeconds(30);

    private final StageAStore delegate;
    private final Map<SourceCode, CountDownLatch> held = new ConcurrentHashMap<>();
    private final Semaphore entered = new Semaphore(0);

    private HeldStageAStore(StageAStore delegate) {
        this.delegate = delegate;
    }

    static HeldStageAStore of(StageAStore injected) {
        return (HeldStageAStore) injected;
    }

    /** From now on, a run on the source waits after A1 until released. */
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

    /** True once a run is waiting after A1. */
    boolean awaitEntered(Duration timeout) throws InterruptedException {
        return entered.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Starts the run on a thread of its own, holds it after A1, does and commits {@code meanwhile},
     * then lets the run go on.
     *
     * @return what the run returned
     * @throws RuntimeException what the run threw
     */
    ReconciliationRun runHeldWhile(SourceCode source, Supplier<ReconciliationRun> run, Runnable meanwhile)
            throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        hold(source);
        try {
            Future<ReconciliationRun> running = thread.submit(run::get);
            assertThat(awaitEntered(LONGEST_HOLD)).as("the run reached its hold after A1").isTrue();
            meanwhile.run();
            release(source);
            try {
                return running.get(LONGEST_HOLD.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException failed) {
                throw failed.getCause() instanceof RuntimeException thrown ? thrown : failed;
            }
        } finally {
            release(source);
            thread.shutdownNow();
        }
    }

    @Override
    public int matchByReference(StageAPass pass) {
        int matched = delegate.matchByReference(pass);
        CountDownLatch latch = held.get(pass.source());
        if (latch != null) {
            entered.release();
            awaitRelease(latch);
        }
        return matched;
    }

    @Override
    public int openReferenceBreaks(StageAPass pass) {
        return delegate.openReferenceBreaks(pass);
    }

    @Override
    public FallbackOutcome matchByAmount(StageAPass pass) {
        return delegate.matchByAmount(pass);
    }

    @Override
    public int resolveMatchedLate(UUID runId, String reason, Instant at) {
        return delegate.resolveMatchedLate(runId, reason, at);
    }

    @Override
    public int openGraceBreaks(StageAPass pass, LocalDate ledgerGraceStart, LocalDate pspGraceStart) {
        return delegate.openGraceBreaks(pass, ledgerGraceStart, pspGraceStart);
    }

    @Override
    public ItemTotals itemTotals(SourceCode source, LocalDate valueDateFrom, LocalDate valueDateTo) {
        return delegate.itemTotals(source, valueDateFrom, valueDateTo);
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

    /** Import into a test context to wrap its Stage A store. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Injection {

        @Bean
        static BeanPostProcessor holdStageAStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof StageAStore store && !(bean instanceof HeldStageAStore)
                            ? new HeldStageAStore(store)
                            : bean;
                }
            };
        }
    }
}
