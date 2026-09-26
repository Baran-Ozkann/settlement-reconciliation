package com.baran.recon.application.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.application.port.DuplicateLedgerEntryException;
import com.baran.recon.application.port.LedgerEntryBatchConflictException;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.source.LedgerAccountSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;

import static com.baran.recon.application.ledger.ProjectionOutcome.DUPLICATE_ENTRY_ID;
import static com.baran.recon.application.ledger.ProjectionOutcome.DUPLICATE_EVENT;
import static com.baran.recon.application.ledger.ProjectionOutcome.STORED;
import static com.baran.recon.application.ledger.ProjectionOutcome.UNMAPPED_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Projecting ledger events into the store")
class ProjectLedgerEventsTest {

    private static final UUID CLEARING = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final CurrencyCode TRY = CurrencyCode.of("TRY");

    private final FakeStore store = new FakeStore();
    private final CountingTransactions transactions = new CountingTransactions();
    private final ProjectLedgerEvents projection = new ProjectLedgerEvents(
            LedgerAccountSources.of(List.of(new SourceDefinition(SourceCode.of("PSP_ALPHA"),
                    SourceType.PSP_SETTLEMENT, Set.of(CLEARING)))),
            store, transactions, Clock.fixed(NOW, ZoneOffset.UTC), ISTANBUL);

    @Test
    @DisplayName("FR-LED-2: only events on a mapped account are stored, under that account's source")
    void onlyMappedAccountsAreStored() {
        List<ProjectionOutcome> outcomes = projection.project(List.of(dated(1, 101, CLEARING), dated(2, 102, CUSTOMER)));

        assertThat(outcomes).containsExactly(STORED, UNMAPPED_ACCOUNT);
        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.get(1L).source()).isEqualTo(SourceCode.of("PSP_ALPHA"));
    }

    @Test
    @DisplayName("FR-LED-4: a batch is stored in one transaction, so it can be acknowledged as one")
    void batchIsOneTransaction() {
        projection.project(List.of(dated(3, 103, CLEARING), dated(4, 104, CLEARING), dated(5, 105, CLEARING)));

        assertThat(transactions.count).isEqualTo(1);
        assertThat(store.writesOutsideTransaction).isZero();
    }

    @Test
    @DisplayName("a batch with no mapped account touches no transaction at all")
    void nothingMappedNothingStored() {
        assertThat(projection.project(List.of(dated(6, 106, CUSTOMER)))).containsExactly(UNMAPPED_ACCOUNT);
        assertThat(transactions.count).isZero();
    }

    @Test
    @DisplayName("FR-LED-3: a redelivered event is reported as a duplicate and stored once")
    void redeliveryIsADuplicate() {
        projection.project(List.of(dated(7, 107, CLEARING)));

        assertThat(projection.project(List.of(dated(7, 107, CLEARING)))).containsExactly(DUPLICATE_EVENT);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    @DisplayName("TDD 6: the value date is created_at's date in the configured zone; received_at is the clock's")
    void valueDateAndReceivedAt() {
        LedgerEvent lateEvening = new LedgerEvent(8, Optional.of(108L),
                Optional.of(Instant.parse("2026-09-23T21:30:00.000000Z")), UUID.randomUUID(), CLEARING, 500, TRY,
                "TRANSFER");

        projection.project(List.of(lateEvening));

        LedgerEntry stored = store.rows.get(8L);
        assertThat(stored.valueDate()).contains(LocalDate.of(2026, 9, 24));
        assertThat(stored.receivedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("FR-LED-7: a five-field event is stored with no entry id, created_at or value date")
    void fiveFieldEventIsNeverBackDated() {
        LedgerEvent history = new LedgerEvent(9, Optional.empty(), Optional.empty(), UUID.randomUUID(), CLEARING,
                -4_500, TRY, "TRANSFER");

        assertThat(projection.project(List.of(history))).containsExactly(STORED);
        LedgerEntry stored = store.rows.get(9L);
        assertThat(stored.ledgerEntryId()).isEmpty();
        assertThat(stored.createdAt()).isEmpty();
        assertThat(stored.valueDate()).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-8: a batch holding a projected entry id is redone one by one; only that event is refused")
    void duplicateEntryIdIsIsolated() {
        projection.project(List.of(dated(10, 110, CLEARING)));
        transactions.count = 0;

        List<ProjectionOutcome> outcomes = projection.project(List.of(
                dated(11, 111, CLEARING), dated(12, 110, CLEARING), dated(13, 113, CUSTOMER), dated(10, 110, CLEARING)));

        assertThat(outcomes).containsExactly(STORED, DUPLICATE_ENTRY_ID, UNMAPPED_ACCOUNT, DUPLICATE_EVENT);
        assertThat(store.rows).containsOnlyKeys(10L, 11L);
        assertThat(transactions.count).as("the failed batch, then one per mapped entry").isEqualTo(4);
        assertThat(store.writesOutsideTransaction).isZero();
    }

    @Test
    @DisplayName("FR-LED-9: a tx_type this service does not map is stored verbatim")
    void unmappedTxTypeIsStored() {
        LedgerEvent fee = new LedgerEvent(14, Optional.of(114L), Optional.of(NOW), UUID.randomUUID(), CLEARING, -100,
                TRY, "FEE");

        assertThat(projection.project(List.of(fee))).containsExactly(STORED);
        assertThat(store.rows.get(14L).txType()).isEqualTo("FEE");
    }

    @Test
    @DisplayName("an empty batch has no outcomes")
    void emptyBatch() {
        assertThat(projection.project(List.of())).isEmpty();
    }

    private static LedgerEvent dated(long eventId, long entryId, UUID account) {
        return new LedgerEvent(eventId, Optional.of(entryId), Optional.of(Instant.parse("2026-09-24T08:15:42.318204Z")),
                UUID.randomUUID(), account, 125_000, TRY, "TRANSFER");
    }

    private static final class CountingTransactions implements Transactions {

        int count;
        boolean open;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            open = true;
            try {
                return work.get();
            } finally {
                open = false;
            }
        }
    }

    /** Atomic per call, as a transaction around it makes the real store: a refused batch keeps nothing. */
    private final class FakeStore implements LedgerEntryStore {

        final Map<Long, LedgerEntry> rows = new HashMap<>();
        int writesOutsideTransaction;

        @Override
        public boolean storeIfAbsent(LedgerEntry entry) {
            List<Boolean> stored = storeAll(List.of(entry), () -> {
                throw new DuplicateLedgerEntryException(entry.ledgerEntryId().orElseThrow(), null);
            });
            return stored.getFirst();
        }

        @Override
        public List<Boolean> storeAllIfAbsent(List<LedgerEntry> entries) {
            return storeAll(entries, () -> {
                throw new LedgerEntryBatchConflictException(null);
            });
        }

        private List<Boolean> storeAll(List<LedgerEntry> entries, Runnable refuse) {
            if (!transactions.open) {
                writesOutsideTransaction++;
            }
            Map<Long, LedgerEntry> staged = new HashMap<>(rows);
            Set<Long> entryIds = new HashSet<>();
            staged.values().forEach(row -> row.ledgerEntryId().ifPresent(entryIds::add));
            List<Boolean> result = new ArrayList<>();
            for (LedgerEntry entry : entries) {
                if (staged.containsKey(entry.eventId())) {
                    result.add(false);
                    continue;
                }
                if (entry.ledgerEntryId().isPresent() && !entryIds.add(entry.ledgerEntryId().get())) {
                    refuse.run();
                }
                staged.put(entry.eventId(), entry);
                result.add(true);
            }
            rows.clear();
            rows.putAll(staged);
            return result;
        }

        @Override
        public Optional<LedgerEntry> findById(UUID id) {
            return rows.values().stream().filter(row -> row.id().equals(id)).findFirst();
        }
    }
}
