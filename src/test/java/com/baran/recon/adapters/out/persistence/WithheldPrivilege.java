package com.baran.recon.adapters.out.persistence;

import java.util.List;

import static com.baran.recon.adapters.out.persistence.Rows.LEDGER_ENTRY;
import static com.baran.recon.adapters.out.persistence.Rows.inserts;

/**
 * Statements recon_app must be refused on tables it can otherwise use, each with the grant whose
 * absence refuses it and what the statement does once that grant is made. For an ordinary table
 * the statement then succeeds. For an append-only audit table it is then refused by the trigger
 * instead (INV-6), which shows the privilege and the trigger are two defences, each able to stop the
 * statement on its own.
 */
enum WithheldPrivilege {

    // A correction in the ledger arrives as another entry; a projected entry is never changed.
    LEDGER_ENTRIES_UPDATE(inserts(LEDGER_ENTRY),
            "UPDATE recon.ledger_entries SET amount = 1 WHERE event_id = 1",
            "GRANT UPDATE ON recon.ledger_entries TO recon_app", AfterGrant.SUCCEEDS),
    LEDGER_ENTRIES_DELETE(inserts(LEDGER_ENTRY),
            "DELETE FROM recon.ledger_entries WHERE event_id = 1",
            "GRANT DELETE ON recon.ledger_entries TO recon_app", AfterGrant.SUCCEEDS),
    LEDGER_ENTRIES_TRUNCATE(inserts(LEDGER_ENTRY),
            "TRUNCATE recon.ledger_entries",
            "GRANT TRUNCATE ON recon.ledger_entries TO recon_app", AfterGrant.SUCCEEDS);

    enum AfterGrant {
        SUCCEEDS,
        REFUSED_BY_TRIGGER
    }

    private final List<String> setup;
    private final String statement;
    private final String grant;
    private final AfterGrant afterGrant;

    WithheldPrivilege(List<String> setup, String statement, String grant, AfterGrant afterGrant) {
        this.setup = setup;
        this.statement = statement;
        this.grant = grant;
        this.afterGrant = afterGrant;
    }

    List<String> setup() {
        return setup;
    }

    String statement() {
        return statement;
    }

    String grant() {
        return grant;
    }

    AfterGrant afterGrant() {
        return afterGrant;
    }
}
