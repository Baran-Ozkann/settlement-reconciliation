package com.baran.recon.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;

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

    /**
     * As {@link #storeIfAbsent} for each entry, in one database round trip. An event id repeated
     * inside the batch is stored once, like one redelivered across batches.
     *
     * @return for each entry, in order, true if it was stored and false if its event id was
     *         already projected
     * @throws LedgerEntryBatchConflictException if any entry's ledger entry id was already carried
     *         by another event. The statement has then failed, and the caller's transaction with
     *         it: nothing from the batch may be kept
     */
    List<Boolean> storeAllIfAbsent(List<LedgerEntry> entries);

    Optional<LedgerEntry> findById(UUID id);

    /**
     * The source's entries with a value date in {@code [from, to]}: a run's scope on the ledger side
     * (TDD 8.2). An entry with no value date is in no run's scope (FR-MAT-9).
     */
    long countInScope(SourceCode source, LocalDate from, LocalDate to);

    /** The source's entries with no value date (FR-MAT-10): five-field history, never in scope. */
    long countWithoutValueDate(SourceCode source);
}
