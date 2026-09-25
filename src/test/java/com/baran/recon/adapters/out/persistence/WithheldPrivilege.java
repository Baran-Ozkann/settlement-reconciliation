package com.baran.recon.adapters.out.persistence;

import java.util.List;

import static com.baran.recon.adapters.out.persistence.Rows.BANK_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.BREAK;
import static com.baran.recon.adapters.out.persistence.Rows.BREAK_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.LEDGER_ENTRY;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH_ITEM;
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
            "GRANT DELETE ON recon.reconciliation_runs TO recon_app", AfterGrant.SUCCEEDS),

    // A reversed match is kept, never deleted (FR-MAT-7).
    MATCHES_DELETE(inserts(RUN, MATCH),
            "DELETE FROM recon.matches WHERE rule_id = 'A1_EXACT_REFERENCE'",
            "GRANT DELETE ON recon.matches TO recon_app", AfterGrant.SUCCEEDS),
    MATCH_ITEMS_DELETE(inserts(RUN, MATCH, MATCH_ITEM),
            "DELETE FROM recon.match_items WHERE side = 'PSP'",
            "GRANT DELETE ON recon.match_items TO recon_app", AfterGrant.SUCCEEDS),

    // INV-6, the privilege half: with the privilege granted, the trigger still refuses.
    MATCH_EVENTS_UPDATE(inserts(RUN, MATCH, MATCH_EVENT),
            "UPDATE recon.match_events SET reason = 'Rewritten' WHERE event_type = 'CREATED'",
            "GRANT UPDATE ON recon.match_events TO recon_app", AfterGrant.REFUSED_BY_TRIGGER),
    MATCH_EVENTS_DELETE(inserts(RUN, MATCH, MATCH_EVENT),
            "DELETE FROM recon.match_events WHERE event_type = 'CREATED'",
            "GRANT DELETE ON recon.match_events TO recon_app", AfterGrant.REFUSED_BY_TRIGGER),
    MATCH_EVENTS_TRUNCATE(inserts(RUN, MATCH, MATCH_EVENT),
            "TRUNCATE recon.match_events",
            "GRANT TRUNCATE ON recon.match_events TO recon_app", AfterGrant.REFUSED_BY_TRIGGER),

    // A break is never deleted, and a transition changes its status, code and time alone.
    BREAKS_DELETE(inserts(RUN, BREAK),
            "DELETE FROM recon.breaks WHERE break_type = 'AMOUNT_MISMATCH'",
            "GRANT DELETE ON recon.breaks TO recon_app", AfterGrant.SUCCEEDS),
    BREAKS_UPDATE_TYPE(inserts(RUN, BREAK),
            "UPDATE recon.breaks SET break_type = 'MISSING_IN_PSP' WHERE status = 'OPEN'",
            "GRANT UPDATE (break_type) ON recon.breaks TO recon_app", AfterGrant.SUCCEEDS),
    BREAKS_UPDATE_ITEM(inserts(RUN, BREAK),
            "UPDATE recon.breaks SET item_id = '1e000000-0000-4000-8000-000000000001' WHERE status = 'OPEN'",
            "GRANT UPDATE (item_id) ON recon.breaks TO recon_app", AfterGrant.SUCCEEDS),

    // INV-6, the privilege half: with the privilege granted, the trigger still refuses.
    BREAK_EVENTS_UPDATE(inserts(RUN, BREAK, BREAK_EVENT),
            "UPDATE recon.break_events SET reason = 'Rewritten' WHERE to_status = 'OPEN'",
            "GRANT UPDATE ON recon.break_events TO recon_app", AfterGrant.REFUSED_BY_TRIGGER),
    BREAK_EVENTS_DELETE(inserts(RUN, BREAK, BREAK_EVENT),
            "DELETE FROM recon.break_events WHERE to_status = 'OPEN'",
            "GRANT DELETE ON recon.break_events TO recon_app", AfterGrant.REFUSED_BY_TRIGGER),
    BREAK_EVENTS_TRUNCATE(inserts(RUN, BREAK, BREAK_EVENT),
            "TRUNCATE recon.break_events",
            "GRANT TRUNCATE ON recon.break_events TO recon_app", AfterGrant.REFUSED_BY_TRIGGER);

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
