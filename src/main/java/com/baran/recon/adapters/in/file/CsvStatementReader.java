package com.baran.recon.adapters.in.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.baran.recon.application.port.TooManyLinesException;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.ValidationCode;

/**
 * A statement file as the header and data rows of one CSV layout (TDD 7), under the limits of
 * FR-ING-8. The header is required and must be exactly the layout's text; anything else rejects the
 * whole file, since no data line can be read by a header it does not have. Data lines are counted,
 * and the first one over {@code maxDataLines} stops reading with {@link TooManyLinesException}.
 */
final class CsvStatementReader {

    private final LineReader lines;
    private final String header;
    private final int columns;
    private final long maxDataLines;
    private long dataLines;
    private boolean headerRead;

    CsvStatementReader(InputStream in, String header, int columns, int maxLineBytes, long maxDataLines) {
        this.lines = new LineReader(in, maxLineBytes);
        this.header = Objects.requireNonNull(header, "header");
        this.columns = columns;
        this.maxDataLines = maxDataLines;
    }

    /** Empty when the header is right; otherwise the error on line 1 that rejects the file. */
    Optional<LineError> readHeader() throws IOException {
        if (headerRead) {
            throw new IllegalStateException("the header is read once");
        }
        headerRead = true;
        Optional<LineReader.RawLine> first = lines.next();
        if (first.isEmpty()) {
            return Optional.of(new LineError(1, ValidationCode.HEADER_MISMATCH));
        }
        LineReader.RawLine line = first.get();
        if (line.error().isPresent()) {
            return line.error().map(code -> new LineError(1, code));
        }
        return line.text().filter(header::equals).isPresent()
                ? Optional.empty()
                : Optional.of(new LineError(1, ValidationCode.HEADER_MISMATCH));
    }

    /**
     * The next data row, or empty at the end of the file.
     *
     * @throws TooManyLinesException on the first data line over the limit
     */
    Optional<CsvRow> next() throws IOException {
        if (!headerRead) {
            throw new IllegalStateException("the header is read first");
        }
        Optional<LineReader.RawLine> next = lines.next();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        if (++dataLines > maxDataLines) {
            throw new TooManyLinesException(maxDataLines);
        }
        LineReader.RawLine line = next.get();
        if (line.error().isPresent()) {
            return Optional.of(CsvRow.invalid(line.number(), line.error().get()));
        }
        CsvFields.Split split = CsvFields.split(line.text().orElseThrow(), columns);
        return Optional.of(split.error()
                .map(code -> CsvRow.invalid(line.number(), code))
                .orElseGet(() -> new CsvRow(line.number(), split.fields(), Optional.empty())));
    }

    /** One data line: its number, and its fields or the reason it has none. */
    record CsvRow(long lineNumber, List<String> fields, Optional<ValidationCode> error) {

        static CsvRow invalid(long lineNumber, ValidationCode code) {
            return new CsvRow(lineNumber, List.of(), Optional.of(code));
        }
    }
}
