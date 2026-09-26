package com.baran.recon.adapters.in.kafka;

/**
 * The {@code x-error-code} of a dead-lettered ledger record (FR-LED-5). Each names a cause with a
 * different fix, so whoever reads the dead-letter topic knows where to look.
 */
public enum DeadLetterReason {

    /** Fails contracts/ledger-events.schema.json, or cannot be read as a JSON document at all. */
    SCHEMA_INVALID,

    /** created_at has the contract's shape but names no real instant, e.g. 2026-02-30. */
    CREATED_AT_NOT_A_DATE,

    /**
     * The event-id header is absent, repeated, or not a positive decimal int64. Without exactly one
     * well-formed value the record cannot be deduplicated. The message says which of the three.
     */
    INVALID_EVENT_ID,

    /** Another event already carried this entry_id: the ledger published one entry twice (FR-LED-8). */
    DUPLICATE_ENTRY_ID
}
