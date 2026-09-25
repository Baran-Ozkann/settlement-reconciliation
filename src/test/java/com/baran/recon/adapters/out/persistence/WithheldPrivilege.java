package com.baran.recon.adapters.out.persistence;

import java.util.List;

import static com.baran.recon.adapters.out.persistence.Rows.BANK_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.LEDGER_ENTRY;
import static com.baran.recon.adapters.out.persistence.Rows.PSP_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.RUN;
import static com.baran.recon.adapters.out.persistence.Rows.STATEMENT_FILE;
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
            "GRANT TRUNCATE ON recon.ledger_entries TO recon_app", AfterGrant.SUCCEEDS),

    // A file is recorded once, in its final state, and a stored line is never changed or removed.
    STATEMENT_FILES_UPDATE(inserts(STATEMENT_FILE),
            "UPDATE recon.statement_files SET status = 'REJECTED', error_summary = '[]' WHERE line_count = 1",
            "GRANT UPDATE ON recon.statement_files TO recon_app", AfterGrant.SUCCEEDS),
    STATEMENT_FILES_DELETE(inserts(STATEMENT_FILE),
            "DELETE FROM recon.statement_files WHERE line_count = 1",
            "GRANT DELETE ON recon.statement_files TO recon_app", AfterGrant.SUCCEEDS),
    PSP_LINES_UPDATE(inserts(STATEMENT_FILE, PSP_LINE),
            "UPDATE recon.psp_lines SET batch_id = 'B-002' WHERE line_id = 'L-000001'",
            "GRANT UPDATE ON recon.psp_lines TO recon_app", AfterGrant.SUCCEEDS),
    PSP_LINES_DELETE(inserts(STATEMENT_FILE, PSP_LINE),
            "DELETE FROM recon.psp_lines WHERE line_id = 'L-000001'",
            "GRANT DELETE ON recon.psp_lines TO recon_app", AfterGrant.SUCCEEDS),
    BANK_LINES_UPDATE(inserts(STATEMENT_FILE, BANK_LINE),
            "UPDATE recon.bank_lines SET amount = 1 WHERE line_id = 'S-000001'",
            "GRANT UPDATE ON recon.bank_lines TO recon_app", AfterGrant.SUCCEEDS),
    BANK_LINES_DELETE(inserts(STATEMENT_FILE, BANK_LINE),
            "DELETE FROM recon.bank_lines WHERE line_id = 'S-000001'",
            "GRANT DELETE ON recon.bank_lines TO recon_app", AfterGrant.SUCCEEDS),

    // A run is never removed: its matches and breaks refer to it.
    RECONCILIATION_RUNS_DELETE(inserts(RUN),
            "DELETE FROM recon.reconciliation_runs WHERE source_code = 'PSP_ALPHA'",
            "GRANT DELETE ON recon.reconciliation_runs TO recon_app", AfterGrant.SUCCEEDS);

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
