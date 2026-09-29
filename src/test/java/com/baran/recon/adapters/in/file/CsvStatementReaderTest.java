package com.baran.recon.adapters.in.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.adapters.in.file.CsvStatementReader.CsvRow;
import com.baran.recon.application.port.TooManyLinesException;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.ValidationCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-ING-8: the header is required and exact, and data lines are counted against the limit")
class CsvStatementReaderTest {

    private static final String HEADER = "id,amount";

    @Test
    @DisplayName("an exact header, then each data line numbered from 2")
    void headerThenRows() throws IOException {
        CsvStatementReader reader = reader(HEADER + "\na,1\nb,2\n", 10);

        assertThat(reader.readHeader()).isEmpty();
        List<CsvRow> rows = rows(reader);
        assertThat(rows).extracting(CsvRow::lineNumber).containsExactly(2L, 3L);
        assertThat(rows.getFirst().fields()).containsExactly("a", "1");
    }

    @ParameterizedTest(name = "header [{0}] is HEADER_MISMATCH")
    @ValueSource(strings = {"ID,amount", "id,amount,", "id, amount", "\"id\",amount", "amount,id", "id"})
    @DisplayName("HEADER_MISMATCH: a header that is not exactly the layout's text, even by case or a space")
    void inexactHeaderIsRefused(String header) throws IOException {
        assertThat(reader(header + "\na,1", 10).readHeader()).contains(new LineError(1, ValidationCode.HEADER_MISMATCH));
    }

    @Test
    @DisplayName("HEADER_MISMATCH: an empty file has no header")
    void emptyFileHasNoHeader() throws IOException {
        assertThat(reader("", 10).readHeader()).contains(new LineError(1, ValidationCode.HEADER_MISMATCH));
    }

    @Test
    @DisplayName("a header line that cannot be read reports why, on line 1")
    void unreadableHeaderReportsItsCode() throws IOException {
        byte[] badHeader = {'i', 'd', (byte) 0xFF, '\n'};
        CsvStatementReader reader = new CsvStatementReader(new ByteArrayInputStream(badHeader), HEADER, 2, 64, 10);

        assertThat(reader.readHeader()).contains(new LineError(1, ValidationCode.INVALID_ENCODING));
    }

    @Test
    @DisplayName("a BOM before the header is dropped; a header-only file has no rows")
    void bomBeforeHeader() throws IOException {
        byte[] content = ("﻿" + HEADER + "\r\n").getBytes(StandardCharsets.UTF_8);
        CsvStatementReader reader = new CsvStatementReader(new ByteArrayInputStream(content), HEADER, 2, 64, 10);

        assertThat(reader.readHeader()).isEmpty();
        assertThat(reader.next()).isEmpty();
    }

    @Test
    @DisplayName("an invalid data line carries its code and no fields")
    void invalidRowCarriesItsCode() throws IOException {
        CsvStatementReader reader = reader(HEADER + "\na,1,extra\n", 10);
        reader.readHeader();

        assertThat(reader.next()).map(CsvRow::error).contains(Optional.of(ValidationCode.COLUMN_COUNT));
    }

    @Test
    @DisplayName("FR-ING-8: exactly the line limit is read; the first data line over it stops reading")
    void lineLimitBoundary() throws IOException {
        CsvStatementReader atLimit = reader(HEADER + "\na,1\nb,2\nc,3\n", 3);
        atLimit.readHeader();
        assertThat(rows(atLimit)).hasSize(3);

        CsvStatementReader overLimit = reader(HEADER + "\na,1\nb,2\nc,3\nd,4\n", 3);
        overLimit.readHeader();
        overLimit.next();
        overLimit.next();
        overLimit.next();
        assertThatThrownBy(overLimit::next).isInstanceOf(TooManyLinesException.class)
                .extracting(failure -> ((TooManyLinesException) failure).maxLines()).isEqualTo(3L);
    }

    @Test
    @DisplayName("the header is read first and once")
    void headerOrder() throws IOException {
        CsvStatementReader reader = reader(HEADER + "\n", 10);
        assertThatThrownBy(reader::next).isInstanceOf(IllegalStateException.class);
        reader.readHeader();
        assertThatThrownBy(reader::readHeader).isInstanceOf(IllegalStateException.class);
    }

    private static CsvStatementReader reader(String content, long maxLines) {
        return new CsvStatementReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), HEADER, 2, 64,
                maxLines);
    }

    private static List<CsvRow> rows(CsvStatementReader reader) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        for (Optional<CsvRow> row = reader.next(); row.isPresent(); row = reader.next()) {
            rows.add(row.get());
        }
        return rows;
    }
}
