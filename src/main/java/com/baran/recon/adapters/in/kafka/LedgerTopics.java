package com.baran.recon.adapters.in.kafka;

/**
 * The two topics this service touches. Constants rather than configuration: which topic the ledger
 * publishes on is a fact of its contract, and which topic this service may write is a rule of INV-9,
 * and neither should change with a property.
 */
public final class LedgerTopics {

    /** Where the ledger publishes account activity (docs/ledger-integration-notes.md 5.1). Read only. */
    public static final String ACCOUNT_ACTIVITY = "ledger.account-activity";

    /**
     * Where a record that cannot be projected goes (FR-LED-5): the only topic this service writes.
     * Named outside the ledger's {@code ledger.} namespace, so nothing about it looks like, or could
     * be mistaken for, a topic the ledger owns.
     */
    public static final String DEAD_LETTER = "recon.ledger-account-activity.dlq";

    private LedgerTopics() {
    }
}
