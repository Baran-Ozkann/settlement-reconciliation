package com.baran.recon.adapters.in.file;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.money.CurrencyCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser is hand-written rather than taken from a library, so these properties are what stand
 * behind that choice: every line the format allows is read back exactly, and no input at all makes
 * the parser throw - hostile bytes become line errors, nothing else.
 */
@Label("TDD 7.1, FR-ING-7: the PSP parser over generated lines and arbitrary bytes")
class PspSettlementParserPropertiesTest {

    private static final String SEED = "20260929";
    private static final List<String> CURRENCIES = List.of("TRY", "EUR", "USD", "JPY", "KWD");
    /** Keeps gross - fee inside a long, so every generated line is one the format allows. */
    private static final long MAX_MAGNITUDE = 1_000_000_000_000_000L;

    private final PspSettlementParser parser = new PspSettlementParser(4096, 100_000);

    @Property(seed = SEED, tries = 1000)
    @Label("a generated valid line, written with any allowed quoting and ending, reads back exactly")
    void validLinesRoundTrip(@ForAll @Size(min = 1, max = 20) List<@From("validLines") Generated> lines,
                             @ForAll boolean crlf, @ForAll boolean bom) {
        String ending = crlf ? "\r\n" : "\n";
        String content = (bom ? "﻿" : "") + PspSettlementParser.HEADER + ending
                + lines.stream().map(Generated::csv).collect(Collectors.joining(ending));

        ParserRun run = ParserRun.of(parser, ParserRun.PSP, content);

        assertThat(run.headerError()).isEmpty();
        assertThat(run.lines()).hasSize(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertThat(run.lines().get(i)).isInstanceOf(ParsedLine.Psp.class);
            ParsedLine.Psp parsed = (ParsedLine.Psp) run.lines().get(i);
            assertThat(parsed.lineNumber()).isEqualTo(i + 2L);
            lines.get(i).assertReadAs(parsed.line());
        }
    }

    @Property(seed = SEED, tries = 1000)
    @Label("arbitrary bytes after the header never throw: each line is a PSP line or a line error, in order")
    void arbitraryBytesNeverThrow(@ForAll @Size(max = 2000) byte[] body) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes((PspSettlementParser.HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        content.writeBytes(body);

        ParserRun run = ParserRun.of(parser, ParserRun.PSP, content.toByteArray());

        assertThat(run.headerError()).isEmpty();
        long expected = 2;
        for (ParsedLine line : run.lines()) {
            assertThat(line).isInstanceOfAny(ParsedLine.Psp.class, ParsedLine.Invalid.class);
            assertThat(line.lineNumber()).isEqualTo(expected++);
        }
    }

    @Property(seed = SEED, tries = 500)
    @Label("arbitrary bytes as the whole file never throw: at worst the header is refused")
    void arbitraryFileNeverThrows(@ForAll @Size(max = 600) byte[] content) {
        ParserRun run = ParserRun.of(parser, ParserRun.PSP, content);

        assertThat(run.headerError().isPresent() || run.lines().stream().allMatch(line -> line.lineNumber() >= 2)).isTrue();
    }

    @Property(seed = SEED, tries = 2000)
    @Label("ten arbitrary fields in a well-formed record never throw: the line is read or refused with a code")
    void arbitraryFieldsNeverThrow(@ForAll @Size(10) List<@StringLength(max = 30) String> fields) {
        String line = fields.stream().map(field -> "\"" + field.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
        // Arbitrary text may hold a line break, which ends the record there; that is fine too.
        ParserRun run = ParserRun.of(parser, ParserRun.PSP, PspSettlementParser.HEADER + "\n" + line);

        assertThat(run.lines()).isNotEmpty()
                .allMatch(parsed -> parsed instanceof ParsedLine.Psp || parsed instanceof ParsedLine.Invalid);
    }

    @Provide
    Arbitrary<Generated> validLines() {
        Arbitrary<String> identifier = Arbitraries.strings().withCharRange('a', 'z').withCharRange('A', 'Z')
                .withCharRange('0', '9').withChars('_', '-').ofMinLength(1).ofMaxLength(64);
        Arbitrary<Optional<String>> reference = Arbitraries.strings().withCharRange(' ', '~').withChars('ş', 'İ', '€')
                .ofMinLength(1).ofMaxLength(64).optional(0.8);
        Arbitrary<LocalDate> transactionDate = Arbitraries.longs().between(0, 3650)
                .map(days -> LocalDate.of(2020, 1, 1).plusDays(days));
        Arbitrary<Integer> settlementDelay = Arbitraries.integers().between(0, 10);
        Arbitrary<PspLineType> type = Arbitraries.of(PspLineType.class);
        Arbitrary<CurrencyCode> currency = Arbitraries.of(CURRENCIES).map(CurrencyCode::of);
        Arbitrary<Long> magnitude = Arbitraries.longs().between(1, MAX_MAGNITUDE);
        Arbitrary<Long> fee = Arbitraries.longs().between(0, MAX_MAGNITUDE);
        Arbitrary<Integer> style = Arbitraries.integers().between(0, 7);
        return Combinators.combine(identifier, reference, identifier, transactionDate, settlementDelay, type, currency,
                        magnitude)
                .flatAs((lineId, ref, batchId, txDate, delay, lineType, code, gross) ->
                        Combinators.combine(fee, style).as((feeUnits, quoting) -> new Generated(lineId, ref, batchId,
                                txDate, txDate.plusDays(delay), lineType, code,
                                lineType.hasPositiveGross() ? gross : -gross, feeUnits, quoting)));
    }

    /** One valid line's values, and how it is written: which fields are quoted, whether amounts are trimmed. */
    record Generated(String lineId, Optional<String> reference, String batchId, LocalDate transactionDate,
                     LocalDate valueDate, PspLineType type, CurrencyCode currency, long gross, long fee, int style) {

        long net() {
            return gross - fee;
        }

        String csv() {
            return String.join(",",
                    field(lineId, 0),
                    field(reference.orElse(""), 1),
                    field(batchId, 2),
                    field(transactionDate.toString(), 0),
                    field(valueDate.toString(), 1),
                    field(type.name(), 2),
                    field(amount(gross), 0),
                    field(amount(fee), 1),
                    field(amount(net()), 2),
                    field(currency.code(), 0));
        }

        void assertReadAs(PspLine line) {
            assertThat(line.lineId()).isEqualTo(lineId);
            assertThat(line.reference()).isEqualTo(reference);
            assertThat(line.batchId()).isEqualTo(batchId);
            assertThat(line.transactionDate()).isEqualTo(transactionDate);
            assertThat(line.valueDate()).isEqualTo(valueDate);
            assertThat(line.type()).isEqualTo(type);
            assertThat(line.currency()).isEqualTo(currency);
            assertThat(line.gross().minorUnits()).isEqualTo(gross);
            assertThat(line.fee().minorUnits()).isEqualTo(fee);
            assertThat(line.net().minorUnits()).isEqualTo(net());
            assertThat(line.fileId()).isEqualTo(ParserRun.FILE_ID);
        }

        /** Written with all its decimals, or with trailing zeros dropped: both are exact. */
        private String amount(long minorUnits) {
            BigDecimal amount = BigDecimal.valueOf(minorUnits, currency.minorUnitDigits());
            return ((style & 4) == 0 ? amount : amount.stripTrailingZeros()).toPlainString();
        }

        /** Quoted when it must be, and otherwise by the style bits, so both forms are exercised. */
        private String field(String value, int slot) {
            boolean mustQuote = value.contains(",") || value.contains("\"");
            boolean quote = mustQuote || ((style >> slot) & 1) == 1;
            return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
        }
    }
}
