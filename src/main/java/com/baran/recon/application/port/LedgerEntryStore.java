package com.baran.recon.application.port;

import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.item.LedgerEntry;

/** The local projection of ledger entries (TDD 5.3). */
public interface LedgerEntryStore {

    /**
     * Stores the entry unless one with the same event id is already stored (FR-LED-3, INV-3).
     *
     * @return true if it was stored, false if it was a redelivery of an event already projected
     * @throws DuplicateLedgerEntryException if another event already carried the same ledger entry
     *         id: the ledger published one entry twice (FR-LED-8)
     */
    boolean storeIfAbsent(LedgerEntry entry);

    Optional<LedgerEntry> findById(UUID id);
}
