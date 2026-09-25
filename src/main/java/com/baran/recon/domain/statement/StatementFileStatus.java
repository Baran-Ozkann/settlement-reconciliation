package com.baran.recon.domain.statement;

/**
 * A statement file is recorded once, in its final state: INGESTED in the transaction that stores its
 * lines, or REJECTED in a transaction of its own after that one rolled back (TDD 5.3).
 */
public enum StatementFileStatus {
    INGESTED,
    REJECTED
}
