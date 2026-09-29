package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.domain.statement.StatementFileStatus;
import com.baran.recon.domain.statement.ValidationCode;

import static com.baran.recon.adapters.out.persistence.SqlValues.currency;
import static com.baran.recon.adapters.out.persistence.SqlValues.date;
import static com.baran.recon.adapters.out.persistence.SqlValues.instant;
import static com.baran.recon.adapters.out.persistence.SqlValues.money;
import static com.baran.recon.adapters.out.persistence.SqlValues.optionalText;
import static com.baran.recon.adapters.out.persistence.SqlValues.timestamp;
import static com.baran.recon.adapters.out.persistence.SqlValues.uuid;

@Repository
class JdbcStatementStore implements StatementStore {

    /** TDD 5.3: lines are inserted this many to a statement. */
    static final int BATCH_SIZE = 1_000;

    private static final String INSERT_FILE = """
            INSERT INTO statement_files (id, source_code, statement_reference, sha256, sanitized_filename,
                                         size_bytes, line_count, status, error_summary, uploaded_by, received_at)
            VALUES (:id, :sourceCode, :statementReference, :sha256, :sanitizedFilename,
                    :sizeBytes, :lineCount, :status, CAST(:errorSummary AS JSONB), :uploadedBy, :receivedAt)
            """;

    /*
     * One statement per batch: each column arrives as one array parameter, and unnest turns the arrays
     * back into rows, in the order given. ON CONFLICT DO NOTHING skips a line whose source already has
     * its line id, whether that line came from an earlier file or from earlier in the same batch, and
     * RETURNING names the lines that were stored, so every other one is a conflict. Dates travel as
     * ISO text and are cast, which keeps the parameters to types the driver builds arrays of.
     */
    private static final String INSERT_PSP_LINES = """
            INSERT INTO psp_lines (id, file_id, source_code, line_id, reference, batch_id, type, transaction_date,
                                   value_date, gross_amount, fee_amount, net_amount, currency)
            SELECT id, file_id, source_code, line_id, reference, batch_id, type, CAST(transaction_date AS DATE),
                   CAST(value_date AS DATE), gross_amount, fee_amount, net_amount, currency
              FROM unnest(:ids, :fileIds, :sourceCodes, :lineIds, :references, :batchIds, :types, :transactionDates,
                          :valueDates, :grossAmounts, :feeAmounts, :netAmounts, :currencies)
                   WITH ORDINALITY AS line(id, file_id, source_code, line_id, reference, batch_id, type,
                                           transaction_date, value_date, gross_amount, fee_amount, net_amount,
                                           currency, position)
             ORDER BY position
                ON CONFLICT (source_code, line_id) DO NOTHING
            RETURNING id
            """;

    private static final String INSERT_BANK_LINES = """
            INSERT INTO bank_lines (id, file_id, source_code, line_id, booking_date, value_date, amount, currency,
                                    reference, extracted_batch_id, description)
            SELECT id, file_id, source_code, line_id, CAST(booking_date AS DATE), CAST(value_date AS DATE), amount,
                   currency, reference, extracted_batch_id, description
              FROM unnest(:ids, :fileIds, :sourceCodes, :lineIds, :bookingDates, :valueDates, :amounts, :currencies,
                          :references, :extractedBatchIds, :descriptions)
                   WITH ORDINALITY AS line(id, file_id, source_code, line_id, booking_date, value_date, amount,
                                           currency, reference, extracted_batch_id, description, position)
             ORDER BY position
                ON CONFLICT (source_code, line_id) DO NOTHING
            RETURNING id
            """;

    /** A later statement of the same transaction, so it also sees lines the insert just stored. */
    private static final String STORED_LINES = """
            SELECT stored.id, stored.file_id, stored.source_code, stored.line_id
              FROM %s stored
              JOIN unnest(:sourceCodes, :lineIds) AS wanted(source_code, line_id)
                ON stored.source_code = wanted.source_code AND stored.line_id = wanted.line_id
            """;

    private static final TypeReference<List<ErrorJson>> ERROR_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcStatementStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** V10 made both foreign keys deferrable; this defers them for the current transaction only. */
    @Override
    public void checkLineFilesAtCommit() {
        jdbc.sql("SET CONSTRAINTS psp_lines_file_fk, bank_lines_file_fk DEFERRED").update();
    }

    @Override
    public void storeFile(StatementFile file) {
        jdbc.sql(INSERT_FILE)
                .param("id", file.id())
                .param("sourceCode", file.source().value())
                .param("statementReference", file.statementReference())
                .param("sha256", file.sha256())
                .param("sanitizedFilename", file.sanitizedFilename())
                .param("sizeBytes", file.sizeBytes())
                .param("lineCount", file.lineCount())
                .param("status", file.status().name())
                .param("errorSummary", errorSummary(file.errors()), Types.VARCHAR)
                .param("uploadedBy", file.uploadedBy())
                .param("receivedAt", timestamp(file.receivedAt()))
                .update();
    }

    @Override
    public List<LineConflict> storePspLinesIfAbsent(List<PspLine> lines) {
        return inBatches(lines, batch -> storeIfAbsent("psp_lines", batch, PspLine::id, PspLine::source,
                PspLine::lineId, jdbc.sql(INSERT_PSP_LINES)
                        .param("ids", array("uuid", batch, PspLine::id))
                        .param("fileIds", array("uuid", batch, PspLine::fileId))
                        .param("sourceCodes", array("text", batch, line -> line.source().value()))
                        .param("lineIds", array("text", batch, PspLine::lineId))
                        .param("references", array("text", batch, line -> line.reference().orElse(null)))
                        .param("batchIds", array("text", batch, PspLine::batchId))
                        .param("types", array("text", batch, line -> line.type().name()))
                        .param("transactionDates", array("text", batch, line -> line.transactionDate().toString()))
                        .param("valueDates", array("text", batch, line -> line.valueDate().toString()))
                        .param("grossAmounts", array("int8", batch, line -> line.gross().minorUnits()))
                        .param("feeAmounts", array("int8", batch, line -> line.fee().minorUnits()))
                        .param("netAmounts", array("int8", batch, line -> line.net().minorUnits()))
                        .param("currencies", array("text", batch, line -> line.currency().code()))));
    }

    @Override
    public List<LineConflict> storeBankLinesIfAbsent(List<BankLine> lines) {
        return inBatches(lines, batch -> storeIfAbsent("bank_lines", batch, BankLine::id, BankLine::source,
                BankLine::lineId, jdbc.sql(INSERT_BANK_LINES)
                        .param("ids", array("uuid", batch, BankLine::id))
                        .param("fileIds", array("uuid", batch, BankLine::fileId))
                        .param("sourceCodes", array("text", batch, line -> line.source().value()))
                        .param("lineIds", array("text", batch, BankLine::lineId))
                        .param("bookingDates", array("text", batch, line -> line.bookingDate().toString()))
                        .param("valueDates", array("text", batch, line -> line.valueDate().toString()))
                        .param("amounts", array("int8", batch, line -> line.amount().minorUnits()))
                        .param("currencies", array("text", batch, line -> line.amount().currency().code()))
                        .param("references", array("text", batch, line -> line.reference().orElse(null)))
                        .param("extractedBatchIds", array("text", batch, line -> line.extractedBatchId().orElse(null)))
                        .param("descriptions", array("text", batch, line -> line.description().orElse(null)))));
    }

    @Override
    public Optional<StatementFile> findFile(UUID id) {
        return jdbc.sql("SELECT id, source_code, statement_reference, sha256, sanitized_filename, size_bytes, line_count, "
                + "status, error_summary, uploaded_by, received_at FROM statement_files WHERE id = :id").param("id", id)
                .query(this::mapFile).optional();
    }

    @Override
    public Optional<UUID> findIngestedFileBySha256(String sha256) {
        return jdbc.sql("SELECT id FROM statement_files WHERE sha256 = :sha256 AND status = 'INGESTED'")
                .param("sha256", sha256).query(UUID.class).optional();
    }

    @Override
    public Optional<UUID> findIngestedFileByReference(SourceCode source, String statementReference) {
        return jdbc.sql("SELECT id FROM statement_files WHERE source_code = :sourceCode "
                        + "AND statement_reference = :statementReference AND status = 'INGESTED'")
                .param("sourceCode", source.value())
                .param("statementReference", statementReference)
                .query(UUID.class).optional();
    }

    @Override
    public Optional<PspLine> findPspLine(UUID id) {
        return jdbc.sql("SELECT id, file_id, source_code, line_id, reference, batch_id, type, transaction_date, value_date, "
                + "gross_amount, fee_amount, net_amount, currency FROM psp_lines WHERE id = :id").param("id", id)
                .query(JdbcStatementStore::mapPspLine).optional();
    }

    @Override
    public Optional<BankLine> findBankLine(UUID id) {
        return jdbc.sql("SELECT id, file_id, source_code, line_id, booking_date, value_date, amount, currency, reference, "
                + "extracted_batch_id, description FROM bank_lines WHERE id = :id").param("id", id)
                .query(JdbcStatementStore::mapBankLine).optional();
    }

    private static <T> List<LineConflict> inBatches(List<T> lines, Function<List<T>, List<LineConflict>> store) {
        List<LineConflict> conflicts = new ArrayList<>();
        for (int from = 0; from < lines.size(); from += BATCH_SIZE) {
            conflicts.addAll(store.apply(lines.subList(from, Math.min(from + BATCH_SIZE, lines.size()))));
        }
        return conflicts;
    }

    /**
     * Runs the batch's insert, then looks up the stored line each skipped one met. Skips are rare,
     * so the second statement usually does not run at all.
     */
    private <T> List<LineConflict> storeIfAbsent(String table, List<T> batch, Function<T, UUID> id,
                                                 Function<T, SourceCode> source, Function<T, String> lineId,
                                                 JdbcClient.StatementSpec insert) {
        Set<UUID> stored = new HashSet<>(insert.query(UUID.class).list());
        List<T> skipped = batch.stream().filter(line -> !stored.contains(id.apply(line))).toList();
        if (skipped.isEmpty()) {
            return List.of();
        }
        Map<String, StoredLine> byKey = new HashMap<>();
        jdbc.sql(STORED_LINES.formatted(table))
                .param("sourceCodes", array("text", skipped, line -> source.apply(line).value()))
                .param("lineIds", array("text", skipped, lineId))
                .query((row, rowNumber) -> new StoredLine(uuid(row, "id"), uuid(row, "file_id"),
                        key(row.getString("source_code"), row.getString("line_id"))))
                .list()
                .forEach(line -> byKey.put(line.key(), line));
        return skipped.stream().map(line -> {
            StoredLine met = byKey.get(key(source.apply(line).value(), lineId.apply(line)));
            if (met == null) {
                throw new IllegalStateException("a skipped line has no stored line under its source and line id");
            }
            return new LineConflict(id.apply(line), met.id(), met.fileId());
        }).toList();
    }

    private static <T> SqlArrayValue array(String type, List<T> lines, Function<? super T, ?> column) {
        return new SqlArrayValue(type, lines.stream().map(column).toArray());
    }

    private static String key(String source, String lineId) {
        return source + '\n' + lineId;
    }

    /** NULL when there is nothing to report, so an error summary is either absent or non-empty. */
    private String errorSummary(List<LineError> errors) {
        if (errors.isEmpty()) {
            return null;
        }
        return json.writeValueAsString(errors.stream().map(ErrorJson::of).toList());
    }

    private StatementFile mapFile(ResultSet row, int rowNumber) throws SQLException {
        String summary = row.getString("error_summary");
        List<LineError> errors = summary == null ? List.of()
                : json.readValue(summary, ERROR_LIST).stream().map(ErrorJson::toLineError).toList();
        return new StatementFile(
                uuid(row, "id"),
                SourceCode.of(row.getString("source_code")),
                row.getString("statement_reference"),
                row.getString("sha256"),
                row.getString("sanitized_filename"),
                row.getLong("size_bytes"),
                row.getLong("line_count"),
                StatementFileStatus.valueOf(row.getString("status")),
                errors,
                row.getString("uploaded_by"),
                instant(row, "received_at"));
    }

    private static PspLine mapPspLine(ResultSet row, int rowNumber) throws SQLException {
        CurrencyCode currency = currency(row, "currency");
        return new PspLine(
                uuid(row, "id"),
                uuid(row, "file_id"),
                SourceCode.of(row.getString("source_code")),
                row.getString("line_id"),
                optionalText(row, "reference"),
                row.getString("batch_id"),
                PspLineType.valueOf(row.getString("type")),
                date(row, "transaction_date"),
                date(row, "value_date"),
                money(row, "gross_amount", currency),
                money(row, "fee_amount", currency),
                money(row, "net_amount", currency));
    }

    private static BankLine mapBankLine(ResultSet row, int rowNumber) throws SQLException {
        return new BankLine(
                uuid(row, "id"),
                uuid(row, "file_id"),
                SourceCode.of(row.getString("source_code")),
                row.getString("line_id"),
                date(row, "booking_date"),
                date(row, "value_date"),
                money(row, "amount", currency(row, "currency")),
                optionalText(row, "reference"),
                optionalText(row, "extracted_batch_id"),
                optionalText(row, "description"));
    }

    private record StoredLine(UUID id, UUID fileId, String key) {
    }

    /** The stored form of one line error: its number and code, never the line's content. */
    private record ErrorJson(long line, String code) {

        static ErrorJson of(LineError error) {
            return new ErrorJson(error.lineNumber(), error.code().name());
        }

        LineError toLineError() {
            return new LineError(line, ValidationCode.valueOf(code));
        }
    }
}
