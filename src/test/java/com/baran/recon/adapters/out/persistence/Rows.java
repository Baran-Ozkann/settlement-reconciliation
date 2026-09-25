package com.baran.recon.adapters.out.persistence;

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

    private Rows() {
    }
}
