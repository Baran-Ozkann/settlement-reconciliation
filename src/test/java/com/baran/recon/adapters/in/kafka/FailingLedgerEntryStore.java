package com.baran.recon.adapters.in.kafka;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.TransientDataAccessResourceException;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.domain.item.LedgerEntry;

/**
 * The application's own store, wrapped so a test can make batches fail after their insert, inside
 * the transaction, the way a crash between the write and the commit would. While armed, every batch
 * fails; the test releases it once it has seen what it needs to see.
 */
final class FailingLedgerEntryStore implements LedgerEntryStore {

    static final String FAILURE = "injected after the insert";

    private final LedgerEntryStore delegate;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicInteger failures = new AtomicInteger();

    private FailingLedgerEntryStore(LedgerEntryStore delegate) {
        this.delegate = delegate;
    }

    static FailingLedgerEntryStore of(LedgerEntryStore injected) {
        return (FailingLedgerEntryStore) injected;
    }

    void failUntilReleased() {
        failures.set(0);
        armed.set(true);
    }

    void release() {
        armed.set(false);
    }

    int failures() {
        return failures.get();
    }

    @Override
    public boolean storeIfAbsent(LedgerEntry entry) {
        return delegate.storeIfAbsent(entry);
    }

    @Override
    public List<Boolean> storeAllIfAbsent(List<LedgerEntry> entries) {
        List<Boolean> stored = delegate.storeAllIfAbsent(entries);
        if (armed.get()) {
            failures.incrementAndGet();
            throw new TransientDataAccessResourceException(FAILURE);
        }
        return stored;
    }

    @Override
    public Optional<LedgerEntry> findById(UUID id) {
        return delegate.findById(id);
    }

    /** Import into a test context to wrap its store. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Injection {

        @Bean
        static BeanPostProcessor wrapLedgerEntryStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof LedgerEntryStore store && !(bean instanceof FailingLedgerEntryStore)
                            ? new FailingLedgerEntryStore(store)
                            : bean;
                }
            };
        }
    }
}
