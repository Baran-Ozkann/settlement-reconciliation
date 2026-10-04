package com.baran.recon.adapters.out.persistence;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.Transactions;

@Component
class SpringTransactions implements Transactions {

    private final TransactionTemplate template;
    private final TransactionTemplate snapshotTemplate;

    SpringTransactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.snapshotTemplate = new TransactionTemplate(transactionManager);
        this.snapshotTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }

    @Override
    public <T> T inSnapshotTransaction(Supplier<T> work) {
        return snapshotTemplate.execute(status -> work.get());
    }
}
