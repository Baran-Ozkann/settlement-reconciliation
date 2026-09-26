package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.DuplicateLedgerEntryException;
import com.baran.recon.application.port.LedgerEntryBatchConflictException;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger projection through the application's own DataSource, connected as recon_app. The
 * database is shared with the other application tests and recon_app cannot delete, so every test
 * uses event ids and entry ids of its own rather than relying on an empty table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Ledger entries are stored once per event, as recon_app")
class LedgerEntryStoreTest {

    /** Ids that no other test class uses; the shared database outlives each class. */
    private static final AtomicLong IDS = new AtomicLong(9_100_000_000L);
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final Instant RECEIVED = Instant.parse("2026-09-24T08:15:01Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private LedgerEntryStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    @DisplayName("a seven-field entry is stored and read back unchanged, microseconds included")
    void datedEntryRoundTrips() {
        LedgerEntry entry = dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "TRANSFER");

        assertThat(store.storeIfAbsent(entry)).isTrue();
        assertThat(store.findById(entry.id())).contains(entry);
    }

    @Test
    @DisplayName("FR-LED-7: a five-field entry is stored with no entry id, created_at or value date")
    void fiveFieldEntryIsStoredUndated() {
        LedgerEntry entry = new LedgerEntry(UUID.randomUUID(), IDS.incrementAndGet(), Optional.empty(),
                UUID.randomUUID(), UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), Money.of(-4_000, CurrencyCode.of("TRY")),
                "TRANSFER", Optional.empty(), Optional.empty(), RECEIVED);

        assertThat(store.storeIfAbsent(entry)).isTrue();
        assertThat(store.findById(entry.id())).hasValueSatisfying(found -> {
            assertThat(found).isEqualTo(entry);
            assertThat(found.isDated()).isFalse();
        });
    }

    @Test
    @DisplayName("FR-LED-3, INV-3: the same event delivered twice produces one row")
    void redeliveryIsSkipped() {
        long eventId = IDS.incrementAndGet();
        LedgerEntry first = dated(eventId, IDS.incrementAndGet(), "TRANSFER");
        LedgerEntry redelivered = new LedgerEntry(UUID.randomUUID(), eventId, first.ledgerEntryId(),
                first.transactionId(), first.accountId(), first.source(), first.amount(), first.txType(),
                first.createdAt(), first.valueDate(), RECEIVED.plusSeconds(60));

        assertThat(store.storeIfAbsent(first)).isTrue();
        assertThat(store.storeIfAbsent(redelivered)).isFalse();
        assertThat(rowsWithEventId(eventId)).isEqualTo(1);
        assertThat(store.findById(redelivered.id())).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-8: the same entry id under a different event id is refused, never skipped")
    void sameEntryUnderAnotherEventIsRefused() {
        long entryId = IDS.incrementAndGet();
        LedgerEntry first = dated(IDS.incrementAndGet(), entryId, "TRANSFER");
        LedgerEntry second = dated(IDS.incrementAndGet(), entryId, "TRANSFER");
        store.storeIfAbsent(first);

        assertThatThrownBy(() -> store.storeIfAbsent(second))
                .isInstanceOf(DuplicateLedgerEntryException.class)
                .satisfies(e -> assertThat(((DuplicateLedgerEntryException) e).ledgerEntryId()).isEqualTo(entryId));
        assertThat(store.findById(second.id())).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-9: a tx_type this service does not map is stored verbatim")
    void unmappedTxTypeIsStored() {
        LedgerEntry entry = dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "FEE");

        assertThat(store.storeIfAbsent(entry)).isTrue();
        assertThat(store.findById(entry.id())).map(LedgerEntry::txType).contains("FEE");
    }

    @Test
    @DisplayName("INV-3: a batch stores every new entry and reports each one as stored")
    void batchStoresEveryNewEntry() {
        List<LedgerEntry> entries = List.of(
                dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "TRANSFER"),
                dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "FUNDING"),
                undated(IDS.incrementAndGet()));

        assertThat(inTransaction(() -> store.storeAllIfAbsent(entries))).containsExactly(true, true, true);
        assertThat(entries).allSatisfy(entry -> assertThat(store.findById(entry.id())).contains(entry));
    }

    @Test
    @DisplayName("FR-LED-3, INV-3: in a batch, an event already stored and an event repeated are each kept once")
    void batchSkipsRedeliveries() {
        LedgerEntry earlier = dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "TRANSFER");
        store.storeIfAbsent(earlier);
        LedgerEntry redeliveredEarlier = redelivery(earlier);
        LedgerEntry fresh = dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "TRANSFER");
        LedgerEntry freshAgain = redelivery(fresh);

        List<Boolean> stored = inTransaction(
                () -> store.storeAllIfAbsent(List.of(redeliveredEarlier, fresh, freshAgain)));

        assertThat(stored).containsExactly(false, true, false);
        assertThat(rowsWithEventId(earlier.eventId())).isEqualTo(1);
        assertThat(rowsWithEventId(fresh.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-LED-8: a batch carrying an entry id another event carried is refused whole")
    void batchWithAProjectedEntryIdIsRefused() {
        long entryId = IDS.incrementAndGet();
        store.storeIfAbsent(dated(IDS.incrementAndGet(), entryId, "TRANSFER"));
        LedgerEntry innocent = dated(IDS.incrementAndGet(), IDS.incrementAndGet(), "TRANSFER");
        LedgerEntry sameEntryNewEvent = dated(IDS.incrementAndGet(), entryId, "TRANSFER");

        assertThatThrownBy(() -> inTransaction(() -> store.storeAllIfAbsent(List.of(innocent, sameEntryNewEvent))))
                .isInstanceOf(LedgerEntryBatchConflictException.class);
        assertThat(store.findById(innocent.id())).as("rolled back with the batch").isEmpty();
        assertThat(store.findById(sameEntryNewEvent.id())).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-8: two new events in one batch carrying one entry id are refused")
    void batchCarryingOneEntryTwiceIsRefused() {
        long entryId = IDS.incrementAndGet();
        List<LedgerEntry> entries = List.of(
                dated(IDS.incrementAndGet(), entryId, "TRANSFER"),
                dated(IDS.incrementAndGet(), entryId, "TRANSFER"));

        assertThatThrownBy(() -> inTransaction(() -> store.storeAllIfAbsent(entries)))
                .isInstanceOf(LedgerEntryBatchConflictException.class);
        assertThat(entries).allSatisfy(entry -> assertThat(store.findById(entry.id())).isEmpty());
    }

    @Test
    @DisplayName("an empty batch stores nothing and asks the database nothing")
    void emptyBatch() {
        assertThat(store.storeAllIfAbsent(List.of())).isEmpty();
    }

    /** The caller's side of the batch contract: a failed batch takes its transaction with it. */
    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private static LedgerEntry redelivery(LedgerEntry original) {
        return new LedgerEntry(UUID.randomUUID(), original.eventId(), original.ledgerEntryId(),
                original.transactionId(), original.accountId(), original.source(), original.amount(),
                original.txType(), original.createdAt(), original.valueDate(), RECEIVED.plusSeconds(60));
    }

    private static LedgerEntry undated(long eventId) {
        return new LedgerEntry(UUID.randomUUID(), eventId, Optional.empty(), UUID.randomUUID(), UUID.randomUUID(),
                SourceCode.of("PSP_ALPHA"), Money.of(-4_000, CurrencyCode.of("TRY")), "TRANSFER",
                Optional.empty(), Optional.empty(), RECEIVED);
    }

    private int rowsWithEventId(long eventId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE event_id = :eventId")
                .param("eventId", eventId).query(Integer.class).single();
    }

    private static LedgerEntry dated(long eventId, long entryId, String txType) {
        Instant createdAt = Instant.parse("2026-09-23T21:30:00.123456Z");
        LocalDate valueDate = LedgerEntry.valueDateOf(createdAt, ISTANBUL);
        return new LedgerEntry(UUID.randomUUID(), eventId, Optional.of(entryId), UUID.randomUUID(), UUID.randomUUID(),
                SourceCode.of("PSP_ALPHA"), Money.of(12_500, CurrencyCode.of("TRY")), txType,
                Optional.of(createdAt), Optional.of(valueDate), RECEIVED);
    }
}
