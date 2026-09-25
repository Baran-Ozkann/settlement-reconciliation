package com.baran.recon.adapters.out.persistence;

import java.util.Arrays;
import java.util.List;

import static com.baran.recon.adapters.out.persistence.Row.text;

/** A valid row of each table, with fixed ids, for the mechanism tests to vary one column at a time. */
final class Rows {

    static final Row STATEMENT_FILE = Row.of("statement_files",
            "id", text("0a000000-0000-4000-8000-000000000001"),
            "source_code", text("PSP_ALPHA"),
            "statement_reference", text("STMT-2026-09-24"),
            "sha256", text("a".repeat(64)),
            "sanitized_filename", text("psp-alpha-2026-09-24.csv"),
            "size_bytes", "1024",
            "line_count", "1",
            "status", text("INGESTED"),
            "error_summary", "NULL",
            "uploaded_by", text("operator-001"),
            "received_at", text("2026-09-24T09:00:00Z"));

    static final Row LEDGER_ENTRY = Row.of("ledger_entries",
            "id", text("1e000000-0000-4000-8000-000000000001"),
            "event_id", "1",
            "ledger_entry_id", "101",
            "transaction_id", text("5f0c7a1e-0000-4000-8000-000000000001"),
            "account_id", text("ac000000-0000-4000-8000-000000000001"),
            "source_code", text("PSP_ALPHA"),
            "amount", "12500",
            "currency", text("TRY"),
            "tx_type", text("TRANSFER"),
            "created_at", text("2026-09-24T08:15:00.123456Z"),
            "value_date", text("2026-09-24"),
            "received_at", text("2026-09-24T08:15:01Z"));

    static final Row PSP_LINE = Row.of("psp_lines",
            "id", text("95000000-0000-4000-8000-000000000001"),
            "file_id", STATEMENT_FILE.literal("id"),
            "source_code", text("PSP_ALPHA"),
            "line_id", text("L-000001"),
            "reference", text("5f0c7a1e-0000-4000-8000-000000000001"),
            "batch_id", text("B-001"),
            "type", text("PAYMENT"),
            "transaction_date", text("2026-09-24"),
            "value_date", text("2026-09-25"),
            "gross_amount", "12500",
            "fee_amount", "250",
            "net_amount", "12250",
            "currency", text("TRY"));

    static final Row BANK_LINE = Row.of("bank_lines",
            "id", text("ba000000-0000-4000-8000-000000000001"),
            "file_id", STATEMENT_FILE.literal("id"),
            "source_code", text("BANK_MAIN"),
            "line_id", text("S-000001"),
            "booking_date", text("2026-09-26"),
            "value_date", text("2026-09-26"),
            "amount", "12250",
            "currency", text("TRY"),
            "reference", text("BATCH-B-001"),
            "extracted_batch_id", text("B-001"),
            "description", text("Settlement Test Merchant 001"));

    static final Row RUN = Row.of("reconciliation_runs",
            "id", text("c0000000-0000-4000-8000-000000000001"),
            "source_code", text("PSP_ALPHA"),
            "value_date_from", text("2026-09-24"),
            "value_date_to", text("2026-09-25"),
            "status", text("COMPLETED"),
            "config_snapshot", text("{\"value_date_zone\": \"Europe/Istanbul\"}"),
            "stats", text("{\"ledger_entries_without_value_date\": 0}"),
            "started_at", text("2026-09-26T10:00:00Z"),
            "finished_at", text("2026-09-26T10:01:00Z"),
            "triggered_by", text("system"));

    static final Row SOURCE_STATE = Row.of("sources_state",
            "source_code", text("PSP_ALPHA"),
            "last_run_id", RUN.literal("id"),
            "updated_at", text("2026-09-26T10:01:00Z"));

    static final Row MATCH = Row.of("matches",
            "id", text("ad000000-0000-4000-8000-000000000001"),
            "run_id", RUN.literal("id"),
            "rule_id", text("A1_EXACT_REFERENCE"),
            "rule_version", "1",
            "cardinality", text("ONE_TO_ONE"),
            "status", text("ACTIVE"),
            "amount_difference", "0",
            "currency", text("TRY"),
            "low_confidence", "false",
            "created_at", text("2026-09-26T10:00:30Z"));

    static final Row MATCH_ITEM = Row.of("match_items",
            "match_id", MATCH.literal("id"),
            "side", text("PSP"),
            "item_id", PSP_LINE.literal("id"),
            "active", "true");

    static final Row MATCH_EVENT = Row.of("match_events",
            "id", "1",
            "match_id", MATCH.literal("id"),
            "event_type", text("CREATED"),
            "actor", text("system"),
            "reason", "NULL",
            "occurred_at", text("2026-09-26T10:00:30Z"));

    static final Row BREAK = Row.of("breaks",
            "id", text("b0000000-0000-4000-8000-000000000001"),
            "break_type", text("AMOUNT_MISMATCH"),
            "item_side", text("PSP"),
            "item_id", PSP_LINE.literal("id"),
            "related_items", text("[{\"side\": \"LEDGER\", \"id\": \"1e000000-0000-4000-8000-000000000001\"}]"),
            "status", text("OPEN"),
            "resolution_code", "NULL",
            "opened_run_id", RUN.literal("id"),
            "previous_break_id", "NULL",
            "opened_at", text("2026-09-26T10:00:45Z"),
            "resolved_at", "NULL");

    static final Row BREAK_EVENT = Row.of("break_events",
            "id", "1",
            "break_id", BREAK.literal("id"),
            "from_status", "NULL",
            "to_status", text("OPEN"),
            "resolution_code", "NULL",
            "actor", text("system"),
            "reason", "NULL",
            "occurred_at", text("2026-09-26T10:00:45Z"));

    static final Row SECOND_MATCH = MATCH.with("id", text("ad000000-0000-4000-8000-000000000002"));

    static final Row RESOLVED_BREAK = BREAK
            .with("status", text("RESOLVED"))
            .with("resolution_code", text("WRITTEN_OFF"))
            .with("resolved_at", text("2026-09-26T11:00:00Z"));

    static final Row RESOLVING_EVENT = BREAK_EVENT
            .with("from_status", text("OPEN"))
            .with("to_status", text("RESOLVED"))
            .with("resolution_code", text("WRITTEN_OFF"))
            .with("reason", text("Below the write-off threshold"));

    private Rows() {
    }

    static List<String> inserts(Row... rows) {
        return Arrays.stream(rows).map(Row::insert).toList();
    }
}
