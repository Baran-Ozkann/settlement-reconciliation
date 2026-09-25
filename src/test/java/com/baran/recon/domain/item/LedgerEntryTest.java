package com.baran.recon.domain.item;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ledger entries as projected from the ledger's events")
class LedgerEntryTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final Instant RECEIVED = Instant.parse("2026-09-24T09:00:00Z");
    private static final Money AMOUNT = Money.of(12_500, CurrencyCode.of("TRY"));

    @Test
    @DisplayName("TDD 6: the value date is the date created_at falls on in Istanbul, not in UTC")
    void valueDateUsesTheConfiguredZone() {
        Instant lateUtcEvening = Instant.parse("2026-09-23T21:30:00.000000Z");

        assertThat(LedgerEntry.valueDateOf(lateUtcEvening, ISTANBUL)).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(LedgerEntry.valueDateOf(lateUtcEvening, ZoneId.of("UTC"))).isEqualTo(LocalDate.of(2026, 9, 23));
    }

    @Test
    @DisplayName("a seven-field entry carries its entry id, created_at and value date")
    void datedEntry() {
        Instant createdAt = Instant.parse("2026-09-24T08:15:00.123456Z");

        LedgerEntry entry = entry(Optional.of(41L), Optional.of(createdAt),
                Optional.of(LedgerEntry.valueDateOf(createdAt, ISTANBUL)), "TRANSFER");

        assertThat(entry.isDated()).isTrue();
        assertThat(entry.valueDate()).contains(LocalDate.of(2026, 9, 24));
    }

    @Test
    @DisplayName("FR-LED-7: a five-field entry has no entry id, no created_at and no value date")
    void fiveFieldEntryIsUndated() {
        LedgerEntry entry = entry(Optional.empty(), Optional.empty(), Optional.empty(), "TRANSFER");

        assertThat(entry.isDated()).isFalse();
    }

    @Test
    @DisplayName("FR-LED-7: a value date without created_at is rejected, so nothing is back-dated")
    void valueDateWithoutCreatedAtIsRejected() {
        assertThatThrownBy(() -> entry(Optional.empty(), Optional.empty(), Optional.of(LocalDate.of(2026, 9, 24)), "TRANSFER"))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("FR-LED-6: an entry id without created_at, or the reverse, is rejected")
    void halfOfThePairIsRejected() {
        Instant createdAt = Instant.parse("2026-09-24T08:15:00Z");
        LocalDate valueDate = LocalDate.of(2026, 9, 24);

        assertThatThrownBy(() -> entry(Optional.of(41L), Optional.empty(), Optional.empty(), "TRANSFER"))
                .isInstanceOf(InvalidItemException.class);
        assertThatThrownBy(() -> entry(Optional.empty(), Optional.of(createdAt), Optional.of(valueDate), "TRANSFER"))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("FR-LED-9: a tx_type this service does not map is kept verbatim")
    void unmappedTxTypeIsKept() {
        assertThat(entry(Optional.empty(), Optional.empty(), Optional.empty(), "FEE").txType()).isEqualTo("FEE");
    }

    @Test
    @DisplayName("FR-LED-9: an empty tx_type is rejected")
    void emptyTxTypeIsRejected() {
        assertThatThrownBy(() -> entry(Optional.empty(), Optional.empty(), Optional.empty(), ""))
                .isInstanceOf(InvalidItemException.class);
    }

    @Test
    @DisplayName("a zero amount is rejected")
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> new LedgerEntry(UUID.randomUUID(), 7L, Optional.empty(), UUID.randomUUID(),
                UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), Money.zero(CurrencyCode.of("TRY")), "TRANSFER",
                Optional.empty(), Optional.empty(), RECEIVED))
                .isInstanceOf(InvalidItemException.class);
    }

    private static LedgerEntry entry(Optional<Long> ledgerEntryId, Optional<Instant> createdAt,
                                     Optional<LocalDate> valueDate, String txType) {
        return new LedgerEntry(UUID.randomUUID(), 7L, ledgerEntryId, UUID.randomUUID(), UUID.randomUUID(),
                SourceCode.of("PSP_ALPHA"), AMOUNT, txType, createdAt, valueDate, RECEIVED);
    }
}
