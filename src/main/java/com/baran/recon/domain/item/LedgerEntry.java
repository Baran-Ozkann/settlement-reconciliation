package com.baran.recon.domain.item;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.money.Money;

/**
 * One ledger posting on an account mapped to a source, as projected from the ledger's event
 * (TDD 6, 10).
 *
 * <p>{@code eventId} is the event-id header: it identifies a delivery and is the deduplication key
 * (FR-LED-3). {@code ledgerEntryId} is the ledger's own entry id. The ledger's entry id, its
 * {@code createdAt} and the value date derived from it are present together or absent together:
 * an event published before the ledger added the two fields carries neither, and such an entry
 * has no value date. It is never back-dated from anything else (FR-LED-7).
 */
public record LedgerEntry(
        UUID id,
        long eventId,
        Optional<Long> ledgerEntryId,
        UUID transactionId,
        UUID accountId,
        SourceCode source,
        Money amount,
        String txType,
        Optional<Instant> createdAt,
        Optional<LocalDate> valueDate,
        Instant receivedAt) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (amount.isZero()) {
            throw new InvalidItemException("a ledger entry moves money; its amount is not zero");
        }
        // FR-LED-9: any non-empty tx_type is stored, including one this service does not map.
        if (txType == null || txType.isEmpty()) {
            throw new InvalidItemException("tx_type is a non-empty string");
        }
        if (ledgerEntryId.isPresent() != createdAt.isPresent() || createdAt.isPresent() != valueDate.isPresent()) {
            throw new InvalidItemException("entry id, created_at and value date are present together or not at all");
        }
    }

    /**
     * The value date of an entry: the date {@code createdAt} falls on in the configured zone
     * (TDD 6), not in UTC and not taken from the Kafka record timestamp.
     */
    public static LocalDate valueDateOf(Instant createdAt, ZoneId zone) {
        return createdAt.atZone(zone).toLocalDate();
    }

    /** False for five-field history: such an entry falls in no date window (FR-MAT-9). */
    public boolean isDated() {
        return valueDate.isPresent();
    }
}
