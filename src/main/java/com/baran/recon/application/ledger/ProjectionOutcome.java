package com.baran.recon.application.ledger;

/** What projecting one ledger event did. */
public enum ProjectionOutcome {

    /** A new row in the projection. */
    STORED,

    /** Its event id was already projected: a redelivery, and a no-op (FR-LED-3). */
    DUPLICATE_EVENT,

    /** Its account is mapped to no source, so it is not this service's business (FR-LED-2). */
    UNMAPPED_ACCOUNT,

    /**
     * Another event already carried its entry id: the ledger published one entry twice (FR-LED-8).
     * Nothing was stored; the caller dead-letters it.
     */
    DUPLICATE_ENTRY_ID
}
