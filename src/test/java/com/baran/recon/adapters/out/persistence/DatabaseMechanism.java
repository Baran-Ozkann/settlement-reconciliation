package com.baran.recon.adapters.out.persistence;

import java.util.List;

import static com.baran.recon.adapters.out.persistence.MechanismKind.CHECK;
import static com.baran.recon.adapters.out.persistence.MechanismKind.FOREIGN_KEY;
import static com.baran.recon.adapters.out.persistence.MechanismKind.PRIMARY_KEY;
import static com.baran.recon.adapters.out.persistence.MechanismKind.TRIGGER;
import static com.baran.recon.adapters.out.persistence.MechanismKind.UNIQUE;
import static com.baran.recon.adapters.out.persistence.MechanismKind.UNIQUE_INDEX;
import static com.baran.recon.adapters.out.persistence.Row.text;
import static com.baran.recon.adapters.out.persistence.Rows.BANK_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.BREAK;
import static com.baran.recon.adapters.out.persistence.Rows.BREAK_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.LEDGER_ENTRY;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH_ITEM;
import static com.baran.recon.adapters.out.persistence.Rows.PSP_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.RESOLVED_BREAK;
import static com.baran.recon.adapters.out.persistence.Rows.RESOLVING_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.RUN;
import static com.baran.recon.adapters.out.persistence.Rows.SECOND_MATCH;
import static com.baran.recon.adapters.out.persistence.Rows.SOURCE_STATE;
import static com.baran.recon.adapters.out.persistence.Rows.STATEMENT_FILE;
import static com.baran.recon.adapters.out.persistence.Rows.inserts;

/**
 * Every constraint, unique index and trigger in the recon schema, each with the rows it needs to
 * exist first and a statement that violates it and nothing else (TDD 9.1).
 * {@code DatabaseMechanismTest} runs each violation twice - once to see this mechanism refuse it by
 * name, once with only this mechanism removed to see it go through - and fails the build if the
 * schema holds a mechanism this list does not, so none can be added without its proof.
 */
enum DatabaseMechanism {

    LEDGER_ENTRIES_PK(PRIMARY_KEY, "ledger_entries", "ledger_entries_pk",
            List.of(LEDGER_ENTRY), LEDGER_ENTRY.with("event_id", "2").with("ledger_entry_id", "102")),
    LEDGER_ENTRIES_EVENT_ID_UNIQUE(UNIQUE, "ledger_entries", "ledger_entries_event_id_unique",
            List.of(LEDGER_ENTRY), LEDGER_ENTRY.with("id", text("1e000000-0000-4000-8000-000000000002"))
                    .with("ledger_entry_id", "102")),
    LEDGER_ENTRIES_LEDGER_ENTRY_ID_UNIQUE(UNIQUE_INDEX, "ledger_entries", "ledger_entries_ledger_entry_id_unique",
            List.of(LEDGER_ENTRY), LEDGER_ENTRY.with("id", text("1e000000-0000-4000-8000-000000000002"))
                    .with("event_id", "2")),
    LEDGER_ENTRIES_CURRENCY_FORMAT(CHECK, "ledger_entries", "ledger_entries_currency_format",
            List.of(), LEDGER_ENTRY.with("currency", text("try"))),
    LEDGER_ENTRIES_CREATED_AT_VALUE_DATE_TOGETHER(CHECK, "ledger_entries",
            "ledger_entries_created_at_value_date_together",
            List.of(), LEDGER_ENTRY.with("created_at", "NULL")),

    STATEMENT_FILES_PK(PRIMARY_KEY, "statement_files", "statement_files_pk",
            List.of(STATEMENT_FILE), STATEMENT_FILE.with("sha256", text("b".repeat(64)))
                    .with("statement_reference", text("STMT-2"))),
    STATEMENT_FILES_STATUS_VALID(CHECK, "statement_files", "statement_files_status_valid",
            List.of(), STATEMENT_FILE.with("status", text("PENDING"))),
    STATEMENT_FILES_SOURCE_CODE_FORMAT(CHECK, "statement_files", "statement_files_source_code_format",
            List.of(), STATEMENT_FILE.with("source_code", text("psp_alpha"))),
    STATEMENT_FILES_STATEMENT_REFERENCE_NOT_EMPTY(CHECK, "statement_files",
            "statement_files_statement_reference_not_empty",
            List.of(), STATEMENT_FILE.with("statement_reference", text(""))),
    STATEMENT_FILES_SHA256_FORMAT(CHECK, "statement_files", "statement_files_sha256_format",
            List.of(), STATEMENT_FILE.with("sha256", text("A".repeat(64)))),
    STATEMENT_FILES_FILENAME_FORMAT(CHECK, "statement_files", "statement_files_filename_format",
            List.of(), STATEMENT_FILE.with("sanitized_filename", text("../etc/passwd"))),
    STATEMENT_FILES_SIZE_NONNEGATIVE(CHECK, "statement_files", "statement_files_size_nonnegative",
            List.of(), STATEMENT_FILE.with("size_bytes", "-1")),
    STATEMENT_FILES_LINE_COUNT_NONNEGATIVE(CHECK, "statement_files", "statement_files_line_count_nonnegative",
            List.of(), STATEMENT_FILE.with("line_count", "-1")),
    STATEMENT_FILES_REJECTED_HAS_ERROR_SUMMARY(CHECK, "statement_files",
            "statement_files_rejected_has_error_summary",
            List.of(), STATEMENT_FILE.with("status", text("REJECTED"))),
    STATEMENT_FILES_SHA256_INGESTED_UNIQUE(UNIQUE_INDEX, "statement_files", "statement_files_sha256_ingested_unique",
            List.of(STATEMENT_FILE), STATEMENT_FILE.with("id", text("0a000000-0000-4000-8000-000000000002"))
                    .with("statement_reference", text("STMT-2"))),
    STATEMENT_FILES_REFERENCE_INGESTED_UNIQUE(UNIQUE_INDEX, "statement_files",
            "statement_files_reference_ingested_unique",
            List.of(STATEMENT_FILE), STATEMENT_FILE.with("id", text("0a000000-0000-4000-8000-000000000002"))
                    .with("sha256", text("b".repeat(64)))),

    PSP_LINES_PK(PRIMARY_KEY, "psp_lines", "psp_lines_pk",
            List.of(STATEMENT_FILE, PSP_LINE), PSP_LINE.with("line_id", text("L-000002"))),
    PSP_LINES_FILE_FK(FOREIGN_KEY, "psp_lines", "psp_lines_file_fk",
            List.of(), PSP_LINE),
    PSP_LINES_SOURCE_LINE_UNIQUE(UNIQUE, "psp_lines", "psp_lines_source_line_unique",
            List.of(STATEMENT_FILE, PSP_LINE), PSP_LINE.with("id", text("95000000-0000-4000-8000-000000000002"))),
    PSP_LINES_SOURCE_CODE_FORMAT(CHECK, "psp_lines", "psp_lines_source_code_format",
            List.of(STATEMENT_FILE), PSP_LINE.with("source_code", text("PSP-ALPHA"))),
    PSP_LINES_LINE_ID_FORMAT(CHECK, "psp_lines", "psp_lines_line_id_format",
            List.of(STATEMENT_FILE), PSP_LINE.with("line_id", text("L 1"))),
    PSP_LINES_BATCH_ID_FORMAT(CHECK, "psp_lines", "psp_lines_batch_id_format",
            List.of(STATEMENT_FILE), PSP_LINE.with("batch_id", text(""))),
    PSP_LINES_REFERENCE_LENGTH(CHECK, "psp_lines", "psp_lines_reference_length",
            List.of(STATEMENT_FILE), PSP_LINE.with("reference", text(""))),
    PSP_LINES_TYPE_VALID(CHECK, "psp_lines", "psp_lines_type_valid",
            List.of(STATEMENT_FILE), PSP_LINE.with("type", text("PAYOUT"))),
    PSP_LINES_DATES_IN_ORDER(CHECK, "psp_lines", "psp_lines_dates_in_order",
            List.of(STATEMENT_FILE), PSP_LINE.with("transaction_date", text("2026-09-26"))),
    PSP_LINES_FEE_NONNEGATIVE(CHECK, "psp_lines", "psp_lines_fee_nonnegative",
            List.of(STATEMENT_FILE), PSP_LINE.with("fee_amount", "-1").with("net_amount", "12501")),
    PSP_LINES_NET_IS_GROSS_MINUS_FEE(CHECK, "psp_lines", "psp_lines_net_is_gross_minus_fee",
            List.of(STATEMENT_FILE), PSP_LINE.with("net_amount", "12251")),
    PSP_LINES_GROSS_SIGN_MATCHES_TYPE(CHECK, "psp_lines", "psp_lines_gross_sign_matches_type",
            List.of(STATEMENT_FILE), PSP_LINE.with("type", text("REFUND"))),
    PSP_LINES_CURRENCY_FORMAT(CHECK, "psp_lines", "psp_lines_currency_format",
            List.of(STATEMENT_FILE), PSP_LINE.with("currency", text("TR1"))),

    BANK_LINES_PK(PRIMARY_KEY, "bank_lines", "bank_lines_pk",
            List.of(STATEMENT_FILE, BANK_LINE), BANK_LINE.with("line_id", text("S-000002"))),
    BANK_LINES_FILE_FK(FOREIGN_KEY, "bank_lines", "bank_lines_file_fk",
            List.of(), BANK_LINE),
    BANK_LINES_SOURCE_LINE_UNIQUE(UNIQUE, "bank_lines", "bank_lines_source_line_unique",
            List.of(STATEMENT_FILE, BANK_LINE), BANK_LINE.with("id", text("ba000000-0000-4000-8000-000000000002"))),
    BANK_LINES_SOURCE_CODE_FORMAT(CHECK, "bank_lines", "bank_lines_source_code_format",
            List.of(STATEMENT_FILE), BANK_LINE.with("source_code", text("bank main"))),
    BANK_LINES_LINE_ID_FORMAT(CHECK, "bank_lines", "bank_lines_line_id_format",
            List.of(STATEMENT_FILE), BANK_LINE.with("line_id", text("S/1"))),
    BANK_LINES_AMOUNT_NONZERO(CHECK, "bank_lines", "bank_lines_amount_nonzero",
            List.of(STATEMENT_FILE), BANK_LINE.with("amount", "0")),
    BANK_LINES_CURRENCY_FORMAT(CHECK, "bank_lines", "bank_lines_currency_format",
            List.of(STATEMENT_FILE), BANK_LINE.with("currency", text("eur"))),
    BANK_LINES_REFERENCE_LENGTH(CHECK, "bank_lines", "bank_lines_reference_length",
            List.of(STATEMENT_FILE), BANK_LINE.with("reference", text(""))),
    BANK_LINES_EXTRACTED_BATCH_ID_FORMAT(CHECK, "bank_lines", "bank_lines_extracted_batch_id_format",
            List.of(STATEMENT_FILE), BANK_LINE.with("extracted_batch_id", text("B 001"))),
    BANK_LINES_DESCRIPTION_LENGTH(CHECK, "bank_lines", "bank_lines_description_length",
            List.of(STATEMENT_FILE), BANK_LINE.with("description", text("x".repeat(141)))),

    RECONCILIATION_RUNS_PK(PRIMARY_KEY, "reconciliation_runs", "reconciliation_runs_pk",
            List.of(RUN), RUN.with("source_code", text("PSP_BETA"))),
    RECONCILIATION_RUNS_SOURCE_CODE_FORMAT(CHECK, "reconciliation_runs", "reconciliation_runs_source_code_format",
            List.of(), RUN.with("source_code", text("psp"))),
    RECONCILIATION_RUNS_STATUS_VALID(CHECK, "reconciliation_runs", "reconciliation_runs_status_valid",
            List.of(), RUN.with("status", text("DONE"))),
    RECONCILIATION_RUNS_DATE_RANGE_IN_ORDER(CHECK, "reconciliation_runs", "reconciliation_runs_date_range_in_order",
            List.of(), RUN.with("value_date_from", text("2026-09-26"))),
    RECONCILIATION_RUNS_FINISHED_UNLESS_RUNNING(CHECK, "reconciliation_runs",
            "reconciliation_runs_finished_unless_running",
            List.of(), RUN.with("status", text("RUNNING"))),
    RECONCILIATION_RUNS_FINISHED_AFTER_START(CHECK, "reconciliation_runs", "reconciliation_runs_finished_after_start",
            List.of(), RUN.with("finished_at", text("2026-09-26T09:00:00Z"))),
    RECONCILIATION_RUNS_COMPLETED_HAS_STATS(CHECK, "reconciliation_runs", "reconciliation_runs_completed_has_stats",
            List.of(), RUN.with("stats", "NULL")),
    RECONCILIATION_RUNS_CONFIG_SNAPSHOT_OBJECT(CHECK, "reconciliation_runs",
            "reconciliation_runs_config_snapshot_object",
            List.of(), RUN.with("config_snapshot", text("[]"))),

    SOURCES_STATE_PK(PRIMARY_KEY, "sources_state", "sources_state_pk",
            List.of(RUN, SOURCE_STATE), SOURCE_STATE.with("updated_at", text("2026-09-26T11:00:00Z"))),
    SOURCES_STATE_LAST_RUN_FK(FOREIGN_KEY, "sources_state", "sources_state_last_run_fk",
            List.of(), SOURCE_STATE),
    SOURCES_STATE_SOURCE_CODE_FORMAT(CHECK, "sources_state", "sources_state_source_code_format",
            List.of(RUN), SOURCE_STATE.with("source_code", text("psp alpha"))),

    MATCHES_PK(PRIMARY_KEY, "matches", "matches_pk",
            List.of(RUN, MATCH), MATCH.with("created_at", text("2026-09-26T10:00:31Z"))),
    MATCHES_RUN_FK(FOREIGN_KEY, "matches", "matches_run_fk",
            List.of(), MATCH),
    MATCHES_RULE_VALID(CHECK, "matches", "matches_rule_valid",
            List.of(RUN), MATCH.with("rule_id", text("A2_REFERENCE_CONFLICT"))),
    MATCHES_RULE_VERSION_POSITIVE(CHECK, "matches", "matches_rule_version_positive",
            List.of(RUN), MATCH.with("rule_version", "0")),
    MATCHES_CARDINALITY_VALID(CHECK, "matches", "matches_cardinality_valid",
            List.of(RUN), MATCH.with("cardinality", text("MANY"))),
    MATCHES_STATUS_VALID(CHECK, "matches", "matches_status_valid",
            List.of(RUN), MATCH.with("status", text("DELETED"))),
    MATCHES_CURRENCY_FORMAT(CHECK, "matches", "matches_currency_format",
            List.of(RUN), MATCH.with("currency", text("try"))),
    MATCHES_CARDINALITY_MATCHES_RULE(CHECK, "matches", "matches_cardinality_matches_rule",
            List.of(RUN), MATCH.with("cardinality", text("MANY_TO_ONE"))),
    MATCHES_LOW_CONFIDENCE_MATCHES_RULE(CHECK, "matches", "matches_low_confidence_matches_rule",
            List.of(RUN), MATCH.with("low_confidence", "true")),

    MATCH_ITEMS_PK(PRIMARY_KEY, "match_items", "match_items_pk",
            List.of(RUN, MATCH, MATCH_ITEM), MATCH_ITEM.with("active", "false")),
    MATCH_ITEMS_MATCH_FK(FOREIGN_KEY, "match_items", "match_items_match_fk",
            List.of(), MATCH_ITEM),
    MATCH_ITEMS_SIDE_VALID(CHECK, "match_items", "match_items_side_valid",
            List.of(RUN, MATCH), MATCH_ITEM.with("side", text("ACCOUNT"))),
    MATCH_ITEMS_ACTIVE_ITEM_UNIQUE(UNIQUE_INDEX, "match_items", "match_items_active_item_unique",
            List.of(RUN, MATCH, MATCH_ITEM, SECOND_MATCH), MATCH_ITEM.with("match_id", SECOND_MATCH.literal("id"))),

    MATCH_EVENTS_PK(PRIMARY_KEY, "match_events", "match_events_pk",
            List.of(RUN, MATCH, MATCH_EVENT), MATCH_EVENT),
    MATCH_EVENTS_MATCH_FK(FOREIGN_KEY, "match_events", "match_events_match_fk",
            List.of(), MATCH_EVENT),
    MATCH_EVENTS_EVENT_TYPE_VALID(CHECK, "match_events", "match_events_event_type_valid",
            List.of(RUN, MATCH), MATCH_EVENT.with("event_type", text("DELETED"))),
    MATCH_EVENTS_ACTOR_LENGTH(CHECK, "match_events", "match_events_actor_length",
            List.of(RUN, MATCH), MATCH_EVENT.with("actor", text(""))),
    MATCH_EVENTS_REASON_LENGTH(CHECK, "match_events", "match_events_reason_length",
            List.of(RUN, MATCH), MATCH_EVENT.with("reason", text(""))),
    MATCH_EVENTS_REVERSAL_HAS_REASON(CHECK, "match_events", "match_events_reversal_has_reason",
            List.of(RUN, MATCH), MATCH_EVENT.with("event_type", text("REVERSED"))),

    BREAKS_PK(PRIMARY_KEY, "breaks", "breaks_pk",
            List.of(RUN, BREAK), BREAK.with("item_id", LEDGER_ENTRY.literal("id"))),
    BREAKS_OPENED_RUN_FK(FOREIGN_KEY, "breaks", "breaks_opened_run_fk",
            List.of(), BREAK),
    BREAKS_PREVIOUS_BREAK_FK(FOREIGN_KEY, "breaks", "breaks_previous_break_fk",
            List.of(RUN), BREAK.with("previous_break_id", text("b0000000-0000-4000-8000-000000000099"))),
    BREAKS_TYPE_VALID(CHECK, "breaks", "breaks_type_valid",
            List.of(RUN), BREAK.with("break_type", text("OTHER"))),
    BREAKS_ITEM_SIDE_VALID(CHECK, "breaks", "breaks_item_side_valid",
            List.of(RUN), BREAK.with("item_side", text("ACCOUNT"))),
    BREAKS_STATUS_VALID(CHECK, "breaks", "breaks_status_valid",
            List.of(RUN), BREAK.with("status", text("CLOSED"))),
    BREAKS_RESOLUTION_CODE_VALID(CHECK, "breaks", "breaks_resolution_code_valid",
            List.of(RUN), RESOLVED_BREAK.with("resolution_code", text("BOGUS"))),
    BREAKS_RELATED_ITEMS_ARRAY(CHECK, "breaks", "breaks_related_items_array",
            List.of(RUN), BREAK.with("related_items", text("{}"))),
    BREAKS_RESOLUTION_CODE_WHEN_RESOLVED(CHECK, "breaks", "breaks_resolution_code_when_resolved",
            List.of(RUN), RESOLVED_BREAK.with("resolution_code", "NULL")),
    BREAKS_RESOLVED_AT_WHEN_RESOLVED(CHECK, "breaks", "breaks_resolved_at_when_resolved",
            List.of(RUN), RESOLVED_BREAK.with("resolved_at", "NULL")),
    BREAKS_RESOLVED_AFTER_OPENED(CHECK, "breaks", "breaks_resolved_after_opened",
            List.of(RUN), RESOLVED_BREAK.with("resolved_at", text("2026-09-26T09:00:00Z"))),
    BREAKS_NOT_ITS_OWN_PREVIOUS(CHECK, "breaks", "breaks_not_its_own_previous",
            List.of(RUN), BREAK.with("previous_break_id", BREAK.literal("id"))),
    BREAKS_ONE_UNRESOLVED_PER_ITEM(UNIQUE_INDEX, "breaks", "breaks_one_unresolved_per_item",
            List.of(RUN, BREAK), BREAK.with("id", text("b0000000-0000-4000-8000-000000000002"))),

    BREAK_EVENTS_PK(PRIMARY_KEY, "break_events", "break_events_pk",
            List.of(RUN, BREAK, BREAK_EVENT), BREAK_EVENT),
    BREAK_EVENTS_BREAK_FK(FOREIGN_KEY, "break_events", "break_events_break_fk",
            List.of(), BREAK_EVENT),
    BREAK_EVENTS_FROM_STATUS_VALID(CHECK, "break_events", "break_events_from_status_valid",
            List.of(RUN, BREAK), BREAK_EVENT.with("from_status", text("CLOSED")).with("to_status", text("INVESTIGATING"))),
    BREAK_EVENTS_TO_STATUS_VALID(CHECK, "break_events", "break_events_to_status_valid",
            List.of(RUN, BREAK), BREAK_EVENT.with("from_status", text("OPEN")).with("to_status", text("CLOSED"))),
    BREAK_EVENTS_RESOLUTION_CODE_VALID(CHECK, "break_events", "break_events_resolution_code_valid",
            List.of(RUN, BREAK), RESOLVING_EVENT.with("resolution_code", text("BOGUS"))),
    BREAK_EVENTS_ACTOR_LENGTH(CHECK, "break_events", "break_events_actor_length",
            List.of(RUN, BREAK), BREAK_EVENT.with("actor", text(""))),
    BREAK_EVENTS_REASON_LENGTH(CHECK, "break_events", "break_events_reason_length",
            List.of(RUN, BREAK), BREAK_EVENT.with("reason", text("x".repeat(1001)))),
    BREAK_EVENTS_RESOLUTION_CODE_WHEN_RESOLVED(CHECK, "break_events", "break_events_resolution_code_when_resolved",
            List.of(RUN, BREAK), RESOLVING_EVENT.with("resolution_code", "NULL")),
    BREAK_EVENTS_NOT_FROM_RESOLVED(CHECK, "break_events", "break_events_not_from_resolved",
            List.of(RUN, BREAK), BREAK_EVENT.with("from_status", text("RESOLVED"))),
    BREAK_EVENTS_OPENING_EVENT_OPENS(CHECK, "break_events", "break_events_opening_event_opens",
            List.of(RUN, BREAK), BREAK_EVENT.with("to_status", text("INVESTIGATING"))),

    MATCH_EVENTS_APPEND_ONLY(TRIGGER, "match_events", "match_events_append_only",
            inserts(RUN, MATCH, MATCH_EVENT), "UPDATE recon.match_events SET reason = 'Rewritten' WHERE id = 1"),
    MATCH_EVENTS_NO_TRUNCATE(TRIGGER, "match_events", "match_events_no_truncate",
            inserts(RUN, MATCH, MATCH_EVENT), "TRUNCATE recon.match_events"),
    BREAK_EVENTS_APPEND_ONLY(TRIGGER, "break_events", "break_events_append_only",
            inserts(RUN, BREAK, BREAK_EVENT), "DELETE FROM recon.break_events WHERE id = 1"),
    BREAK_EVENTS_NO_TRUNCATE(TRIGGER, "break_events", "break_events_no_truncate",
            inserts(RUN, BREAK, BREAK_EVENT), "TRUNCATE recon.break_events");

    private final MechanismKind kind;
    private final String table;
    private final String objectName;
    private final List<String> setup;
    private final String violation;

    DatabaseMechanism(MechanismKind kind, String table, String objectName, List<Row> setup, Row violation) {
        this(kind, table, objectName, setup.stream().map(Row::insert).toList(), violation.insert());
    }

    DatabaseMechanism(MechanismKind kind, String table, String objectName, List<String> setup, String violation) {
        this.kind = kind;
        this.table = table;
        this.objectName = objectName;
        this.setup = setup;
        this.violation = violation;
    }

    MechanismKind kind() {
        return kind;
    }

    String table() {
        return table;
    }

    String objectName() {
        return objectName;
    }

    List<String> setup() {
        return setup;
    }

    String violation() {
        return violation;
    }

    String removal() {
        return kind.removal(table, objectName);
    }
}
