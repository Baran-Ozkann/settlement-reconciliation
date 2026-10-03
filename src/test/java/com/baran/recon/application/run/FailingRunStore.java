package com.baran.recon.application.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.baran.recon.application.port.RunStore;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;

/**
 * The application's own store, wrapped so a test can make a run's work fail at its very end: the
 * run is written COMPLETED, inside the work transaction, and then the failure is thrown, the way a
 * crash before the commit would. Armed per source and for one run, so other tests sharing the
 * context are not touched.
 */
final class FailingRunStore implements RunStore {

    static final String FAILURE = "injected after the run was written COMPLETED";

    private final RunStore delegate;
    private final Set<SourceCode> armed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger completionsWrittenThenFailed = new AtomicInteger();

    private FailingRunStore(RunStore delegate) {
        this.delegate = delegate;
    }

    static FailingRunStore of(RunStore injected) {
        return (FailingRunStore) injected;
    }

    void failTheNextCompletionOf(SourceCode source) {
        armed.add(source);
    }

    int completionsWrittenThenFailed() {
        return completionsWrittenThenFailed.get();
    }

    @Override
    public void recordOutcome(ReconciliationRun finished) {
        delegate.recordOutcome(finished);
        if (finished.status() == RunStatus.COMPLETED && armed.remove(finished.source())) {
            completionsWrittenThenFailed.incrementAndGet();
            throw new IllegalStateException(FAILURE);
        }
    }

    @Override
    public void insert(ReconciliationRun run) {
        delegate.insert(run);
    }

    @Override
    public Optional<ReconciliationRun> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<UUID> findRunning(SourceCode source) {
        return delegate.findRunning(source);
    }

    @Override
    public List<UUID> failAllRunning(Instant finishedAt) {
        return delegate.failAllRunning(finishedAt);
    }

    /** Import into a test context to wrap its store. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Injection {

        @Bean
        static BeanPostProcessor failRunStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof RunStore store && !(bean instanceof FailingRunStore)
                            ? new FailingRunStore(store)
                            : bean;
                }
            };
        }
    }
}
