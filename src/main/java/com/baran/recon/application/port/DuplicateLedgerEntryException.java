package com.baran.recon.application.port;

/**
 * A second event carried a ledger entry id that is already projected (FR-LED-8). The ledger must
 * not publish one entry twice, so this is a fault on its side. It is never silently dropped:
 * Phase 3 logs it and dead-letters the record as DUPLICATE_ENTRY_ID.
 */
public final class DuplicateLedgerEntryException extends RuntimeException {

    private final long ledgerEntryId;

    public DuplicateLedgerEntryException(long ledgerEntryId, Throwable cause) {
        super("ledger entry " + ledgerEntryId + " was already projected from another event", cause);
        this.ledgerEntryId = ledgerEntryId;
    }

    public long ledgerEntryId() {
        return ledgerEntryId;
    }
}
