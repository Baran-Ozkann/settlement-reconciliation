package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    /** TDD 5.3: lines are inserted in JDBC batches of this size. */
    static final int BATCH_SIZE = 1_000;

    private static final String INSERT_FILE = """
            INSERT INTO statement_files (id, source_code, statement_reference, sha256, sanitized_filename,
                                         size_bytes, line_count, status, error_summary, uploaded_by, received_at)
            VALUES (:id, :sourceCode, :statementReference, :sha256, :sanitizedFilename,
                    :sizeBytes, :lineCount, :status, CAST(:errorSummary AS JSONB), :uploadedBy, :receivedAt)
            """;

    private static final String INSERT_PSP_LINE = """
            INSERT INTO psp_lines (id, file_id, source_code, line_id, reference, batch_id, type, transaction_date,
                                   value_date, gross_amount, fee_amount, net_amount, currency)
            VALUES (:id, :fileId, :sourceCode, :lineId, :reference, :batchId, :type, :transactionDate,
                    :valueDate, :grossAmount, :feeAmount, :netAmount, :currency)
            """;

    private static final String INSERT_BANK_LINE = """
            INSERT INTO bank_lines (id, file_id, source_code, line_id, booking_date, value_date, amount, currency,
                                    reference, extracted_batch_id, description)
            VALUES (:id, :fileId, :sourceCode, :lineId, :bookingDate, :valueDate, :amount, :currency,
                    :reference, :extractedBatchId, :description)
            """;

    private static final TypeReference<List<ErrorJson>> ERROR_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;
    private final JsonMapper json;

    JdbcStatementStore(JdbcClient jdbc, NamedParameterJdbcTemplate batch, JsonMapper json) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.json = json;
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
    public long storePspLines(Stream<PspLine> lines) {
        return insertInBatches(INSERT_PSP_LINE, lines, JdbcStatementStore::pspLineParameters);
    }

    @Override
    public long storeBankLines(Stream<BankLine> lines) {
        return insertInBatches(INSERT_BANK_LINE, lines, JdbcStatementStore::bankLineParameters);
    }

    @Override
    public Optional<StatementFile> findFile(UUID id) {
        return jdbc.sql("SELECT id, source_code, statement_reference, sha256, sanitized_filename, size_bytes, line_count, "
                + "status, error_summary, uploaded_by, received_at FROM statement_files WHERE id = :id").param("id", id)
                .query(this::mapFile).optional();
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

    /** Consumes the stream a batch at a time, so no more than one batch is held at once. */
    private <T> long insertInBatches(String sql, Stream<T> items, Function<T, SqlParameterSource> parameters) {
        long stored = 0;
        List<SqlParameterSource> pending = new ArrayList<>(BATCH_SIZE);
        Iterator<T> iterator = items.iterator();
        while (iterator.hasNext()) {
            pending.add(parameters.apply(iterator.next()));
            if (pending.size() == BATCH_SIZE) {
                stored += flush(sql, pending);
            }
        }
        return stored + flush(sql, pending);
    }

    private int flush(String sql, List<SqlParameterSource> pending) {
        if (pending.isEmpty()) {
            return 0;
        }
        batch.batchUpdate(sql, pending.toArray(SqlParameterSource[]::new));
        int flushed = pending.size();
        pending.clear();
        return flushed;
    }

    private static SqlParameterSource pspLineParameters(PspLine line) {
        return new MapSqlParameterSource()
                .addValue("id", line.id())
                .addValue("fileId", line.fileId())
                .addValue("sourceCode", line.source().value())
                .addValue("lineId", line.lineId())
                .addValue("reference", line.reference().orElse(null), Types.VARCHAR)
                .addValue("batchId", line.batchId())
                .addValue("type", line.type().name())
                .addValue("transactionDate", line.transactionDate())
                .addValue("valueDate", line.valueDate())
                .addValue("grossAmount", line.gross().minorUnits())
                .addValue("feeAmount", line.fee().minorUnits())
                .addValue("netAmount", line.net().minorUnits())
                .addValue("currency", line.currency().code());
    }

    private static SqlParameterSource bankLineParameters(BankLine line) {
        return new MapSqlParameterSource()
                .addValue("id", line.id())
                .addValue("fileId", line.fileId())
                .addValue("sourceCode", line.source().value())
                .addValue("lineId", line.lineId())
                .addValue("bookingDate", line.bookingDate())
                .addValue("valueDate", line.valueDate())
                .addValue("amount", line.amount().minorUnits())
                .addValue("currency", line.amount().currency().code())
                .addValue("reference", line.reference().orElse(null), Types.VARCHAR)
                .addValue("extractedBatchId", line.extractedBatchId().orElse(null), Types.VARCHAR)
                .addValue("description", line.description().orElse(null), Types.VARCHAR);
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
