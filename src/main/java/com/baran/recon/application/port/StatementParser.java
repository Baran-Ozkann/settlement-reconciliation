package com.baran.recon.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.function.Consumer;

import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.LineError;

/**
 * Reads one statement format (TDD 5.2, 7). There is one parser per {@link SourceType}, and the
 * source's type chooses it, never the file's name or extension (FR-ING-2).
 *
 * <p>Parsing streams: each data line is handed on as soon as it is read and nothing is kept, so
 * memory does not grow with the file (FR-ING-5). Every line is either a valid line or a line error;
 * a line never makes the parser throw.
 */
public interface StatementParser {

    SourceType sourceType();

    /**
     * Reads the whole file, handing each data line to {@code lines} in file order.
     *
     * @return empty when every data line was handed on, or the error on line 1 that rejects the
     *         whole file (a missing or wrong header), in which case no data line was
     * @throws TooManyLinesException on the first data line over the configured limit
     * @throws IOException when the content cannot be read, which says nothing about the file
     */
    Optional<LineError> parse(InputStream content, StatementContext context, Consumer<ParsedLine> lines)
            throws IOException;
}
