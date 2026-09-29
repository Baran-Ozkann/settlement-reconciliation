package com.baran.recon.adapters.in.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.baran.recon.adapters.in.file.CsvStatementReader.CsvRow;
import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.application.port.StatementContext;
import com.baran.recon.application.port.StatementParser;
import com.baran.recon.domain.statement.LineError;

/**
 * What both CSV formats share: the header, the limits, and one outcome per data line. A subclass
 * reads one line's fields into its format's line, and refuses the line with the first rule it
 * breaks (FR-ING-7): its columns in order, then the rules that relate one column to another.
 */
abstract class CsvStatementParser implements StatementParser {

    private final String header;
    private final int columns;
    private final int maxLineBytes;
    private final long maxLines;

    CsvStatementParser(String header, int maxLineBytes, long maxLines) {
        this.header = header;
        this.columns = header.split(",", -1).length;
        if (maxLineBytes < 1 || maxLines < 1) {
            throw new IllegalArgumentException("the line limits must be positive");
        }
        this.maxLineBytes = maxLineBytes;
        this.maxLines = maxLines;
    }

    @Override
    public final Optional<LineError> parse(InputStream content, StatementContext context, Consumer<ParsedLine> lines)
            throws IOException {
        CsvStatementReader reader = new CsvStatementReader(content, header, columns, maxLineBytes, maxLines);
        Optional<LineError> headerError = reader.readHeader();
        if (headerError.isPresent()) {
            return headerError;
        }
        for (Optional<CsvRow> row = reader.next(); row.isPresent(); row = reader.next()) {
            lines.accept(outcome(row.get(), context));
        }
        return Optional.empty();
    }

    /** One line's fields, all of them present: the column count is checked before this is called. */
    abstract ParsedLine read(long lineNumber, List<String> fields, StatementContext context);

    private ParsedLine outcome(CsvRow row, StatementContext context) {
        if (row.error().isPresent()) {
            return new ParsedLine.Invalid(new LineError(row.lineNumber(), row.error().get()));
        }
        try {
            return read(row.lineNumber(), row.fields(), context);
        } catch (FieldRules.LineRejected rejected) {
            return new ParsedLine.Invalid(new LineError(row.lineNumber(), rejected.code()));
        }
    }
}
