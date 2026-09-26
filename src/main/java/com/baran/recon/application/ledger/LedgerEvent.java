package com.baran.recon.application.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.money.CurrencyCode;

/**
 * One ledger account activity event after it has passed the contract (FR-LED-6): what the Kafka
 * adapter hands the projection. {@code eventId} is the event-id header, the deduplication key;
 * {@code entryId} and {@code createdAt} are both present or both absent, as the contract pairs them.
 */
public record LedgerEvent(
        long eventId,
        Optional<Long> entryId,
        Optional<Instant> createdAt,
        UUID transactionId,
        UUID accountId,
        long amount,
        CurrencyCode currency,
        String txType) {

    public LedgerEvent {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(txType, "txType");
        if (entryId.isPresent() != createdAt.isPresent()) {
            throw new IllegalArgumentException("entry_id and created_at come together or not at all");
        }
    }
}
