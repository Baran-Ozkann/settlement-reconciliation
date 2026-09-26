package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("The Transactions port commits work together or not at all")
class SpringTransactionsTest {

    private static final AtomicLong IDS = new AtomicLong(9_200_000_000L);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private Transactions transactions;

    @Autowired
    private LedgerEntryStore store;

    @Test
    @DisplayName("work that returns is committed")
    void completedWorkCommits() {
        LedgerEntry entry = entry();

        assertThat(transactions.inTransaction(() -> store.storeIfAbsent(entry))).isTrue();
        assertThat(store.findById(entry.id())).contains(entry);
    }

    @Test
    @DisplayName("work that throws is rolled back, and the exception reaches the caller unchanged")
    void failedWorkRollsBack() {
        LedgerEntry entry = entry();
        IllegalStateException failure = new IllegalStateException("after the insert");

        assertThatThrownBy(() -> transactions.inTransaction(() -> {
            store.storeIfAbsent(entry);
            throw failure;
        })).isSameAs(failure);
        assertThat(store.findById(entry.id())).isEmpty();
    }

    private static LedgerEntry entry() {
        return new LedgerEntry(UUID.randomUUID(), IDS.incrementAndGet(), Optional.empty(), UUID.randomUUID(),
                UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), Money.of(700, CurrencyCode.of("TRY")), "TRANSFER",
                Optional.empty(), Optional.empty(), Instant.parse("2026-09-26T10:00:00Z"));
    }
}
