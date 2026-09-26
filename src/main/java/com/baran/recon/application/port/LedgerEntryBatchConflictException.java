package com.baran.recon.application.port;

/**
 * A batch of ledger entries was refused because at least one of them carries a ledger entry id that
 * another event already carried (FR-LED-8). A batch failure does not say which entry it was: the
 * database names the index but not the row, except in a message whose wording depends on the
 * server's locale. The caller rolls the batch back and stores its entries one at a time, where
 * {@link DuplicateLedgerEntryException} names the offending entry.
 */
public final class LedgerEntryBatchConflictException extends RuntimeException {

    public LedgerEntryBatchConflictException(Throwable cause) {
        super("a ledger entry in the batch was already projected from another event", cause);
    }
}
