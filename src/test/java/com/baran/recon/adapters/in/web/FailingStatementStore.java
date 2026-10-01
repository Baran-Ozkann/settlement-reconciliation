package com.baran.recon.adapters.in.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.TransientDataAccessResourceException;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.statement.StatementFile;

/**
 * The application's own store, wrapped so a test can make ingestion fail after a write, inside the
 * ingestion's transaction, the way a crash between the write and the commit would: after the PSP
 * batch it names, or right after the file row. Disarmed, it only passes calls on.
 */
final class FailingStatementStore implements StatementStore {

    private final StatementStore delegate;
    private final AtomicInteger pspBatches = new AtomicInteger();
    private final AtomicInteger files = new AtomicInteger();
    private volatile int failingPspBatch;
    private volatile boolean failAfterFile;

    FailingStatementStore(StatementStore delegate) {
        this.delegate = delegate;
    }

    void failOnPspBatch(int batch) {
        pspBatches.set(0);
        failingPspBatch = batch;
    }

    void failAfterStoringFile() {
        files.set(0);
        failAfterFile = true;
    }

    void disarm() {
        failingPspBatch = 0;
        failAfterFile = false;
    }

    int batchesSeen() {
        return pspBatches.get();
    }

    int filesStored() {
        return files.get();
    }

    @Override
    public List<LineConflict> storePspLinesIfAbsent(List<PspLine> lines) {
        List<LineConflict> conflicts = delegate.storePspLinesIfAbsent(lines);
        if (pspBatches.incrementAndGet() == failingPspBatch) {
            throw new TransientDataAccessResourceException("injected after PSP batch " + failingPspBatch);
        }
        return conflicts;
    }

    @Override
    public void storeFile(StatementFile file) {
        delegate.storeFile(file);
        files.incrementAndGet();
        if (failAfterFile) {
            throw new TransientDataAccessResourceException("injected after the file row");
        }
    }

    @Override
    public void checkLineFilesAtCommit() {
        delegate.checkLineFilesAtCommit();
    }

    @Override
    public List<LineConflict> storeBankLinesIfAbsent(List<BankLine> lines) {
        return delegate.storeBankLinesIfAbsent(lines);
    }

    @Override
    public Optional<StatementFile> findFile(UUID id) {
        return delegate.findFile(id);
    }

    @Override
    public Optional<UUID> findIngestedFileBySha256(String sha256) {
        return delegate.findIngestedFileBySha256(sha256);
    }

    @Override
    public Optional<UUID> findIngestedFileByReference(SourceCode source, String statementReference) {
        return delegate.findIngestedFileByReference(source, statementReference);
    }

    @Override
    public Optional<PspLine> findPspLine(UUID id) {
        return delegate.findPspLine(id);
    }

    @Override
    public Optional<BankLine> findBankLine(UUID id) {
        return delegate.findBankLine(id);
    }

    /** Wraps the application's store in a context that imports it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Injection {

        @Bean
        static BeanPostProcessor failingStatementStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof StatementStore real ? new FailingStatementStore(real) : bean;
                }
            };
        }
    }
}
