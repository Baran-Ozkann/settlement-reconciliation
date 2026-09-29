package com.baran.recon.adapters.in.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.application.port.StatementContext;
import com.baran.recon.application.port.StatementParser;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.BatchIdPattern;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.statement.LineError;

/** One parser run in memory: the header outcome and every line handed on, for the parser tests. */
record ParserRun(Optional<LineError> headerError, List<ParsedLine> lines) {

    static final UUID FILE_ID = UUID.fromString("00000000-0000-4000-8000-00000000f11e");
    static final StatementContext PSP = new StatementContext(FILE_ID,
            SourceDefinition.psp(SourceCode.of("PSP_ALPHA"), Set.of()));
    static final StatementContext BANK = new StatementContext(FILE_ID,
            SourceDefinition.bank(SourceCode.of("BANK_MAIN"), BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})")));

    static ParserRun of(StatementParser parser, StatementContext context, String content) {
        return of(parser, context, content.getBytes(StandardCharsets.UTF_8));
    }

    static ParserRun of(StatementParser parser, StatementContext context, byte[] content) {
        List<ParsedLine> lines = new ArrayList<>();
        try {
            Optional<LineError> headerError = parser.parse(new ByteArrayInputStream(content), context, lines::add);
            return new ParserRun(headerError, lines);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    ParsedLine only() {
        if (lines.size() != 1) {
            throw new AssertionError("expected one line, got " + lines);
        }
        return lines.getFirst();
    }
}
