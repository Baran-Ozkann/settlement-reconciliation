package com.baran.recon.adapters.out.persistence;

import java.util.List;

import static com.baran.recon.adapters.out.persistence.MechanismKind.CHECK;
import static com.baran.recon.adapters.out.persistence.MechanismKind.FOREIGN_KEY;
import static com.baran.recon.adapters.out.persistence.MechanismKind.PRIMARY_KEY;
import static com.baran.recon.adapters.out.persistence.MechanismKind.UNIQUE;
import static com.baran.recon.adapters.out.persistence.MechanismKind.UNIQUE_INDEX;
import static com.baran.recon.adapters.out.persistence.Row.text;
import static com.baran.recon.adapters.out.persistence.Rows.BANK_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.LEDGER_ENTRY;
import static com.baran.recon.adapters.out.persistence.Rows.PSP_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.STATEMENT_FILE;

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
            List.of(STATEMENT_FILE), BANK_LINE.with("description", text("x".repeat(141))));

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
