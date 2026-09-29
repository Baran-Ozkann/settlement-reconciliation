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
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.money.CurrencyCode;

import static org.assertj.core.api.Assertions.assertThat;

/** As for the PSP parser: generated valid lines read back exactly, and no input makes the parser throw. */
@Label("TDD 7.2, FR-ING-7: the bank parser over generated lines and arbitrary bytes")
class BankStatementParserPropertiesTest {

    private static final String SEED = "20260929";

    private final BankStatementParser parser = new BankStatementParser(4096, 100_000);

    @Property(seed = SEED, tries = 1000)
    @Label("a generated valid line, written with any allowed quoting and ending, reads back exactly")
    void validLinesRoundTrip(@ForAll @Size(min = 1, max = 20) List<@From("validLines") Generated> lines,
                             @ForAll boolean crlf) {
        String ending = crlf ? "\r\n" : "\n";
        String content = BankStatementParser.HEADER + ending
                + lines.stream().map(Generated::csv).collect(Collectors.joining(ending)) + ending;

        ParserRun run = ParserRun.of(parser, ParserRun.BANK, content);

        assertThat(run.headerError()).isEmpty();
        assertThat(run.lines()).hasSize(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertThat(run.lines().get(i)).isInstanceOf(ParsedLine.Bank.class);
            lines.get(i).assertReadAs(((ParsedLine.Bank) run.lines().get(i)).line());
        }
    }

    @Property(seed = SEED, tries = 1000)
    @Label("arbitrary bytes after the header never throw: each line is a bank line or a line error, in order")
    void arbitraryBytesNeverThrow(@ForAll @Size(max = 2000) byte[] body) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes((BankStatementParser.HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        content.writeBytes(body);

        ParserRun run = ParserRun.of(parser, ParserRun.BANK, content.toByteArray());

        long expected = 2;
        for (ParsedLine line : run.lines()) {
            assertThat(line).isInstanceOfAny(ParsedLine.Bank.class, ParsedLine.Invalid.class);
            assertThat(line.lineNumber()).isEqualTo(expected++);
        }
    }

    @Property(seed = SEED, tries = 2000)
    @Label("seven arbitrary fields in a well-formed record never throw: the line is read or refused with a code")
    void arbitraryFieldsNeverThrow(@ForAll @Size(7) List<@StringLength(max = 40) String> fields) {
        String line = fields.stream().map(field -> "\"" + field.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));

        ParserRun run = ParserRun.of(parser, ParserRun.BANK, BankStatementParser.HEADER + "\n" + line);

        assertThat(run.lines()).isNotEmpty()
                .allMatch(parsed -> parsed instanceof ParsedLine.Bank || parsed instanceof ParsedLine.Invalid);
    }

    @Provide
    Arbitrary<Generated> validLines() {
        Arbitrary<String> identifier = Arbitraries.strings().withCharRange('a', 'z').withCharRange('A', 'Z')
                .withCharRange('0', '9').withChars('_', '-').ofMinLength(1).ofMaxLength(64);
        Arbitrary<Optional<String>> text = Arbitraries.strings().withCharRange(' ', '~').withChars('ş', 'Ö', '€')
                .ofMinLength(1).ofMaxLength(140).optional(0.8);
        Arbitrary<LocalDate> date = Arbitraries.longs().between(0, 3650).map(days -> LocalDate.of(2020, 1, 1).plusDays(days));
        Arbitrary<CurrencyCode> currency = Arbitraries.of("TRY", "EUR", "JPY", "KWD").map(CurrencyCode::of);
        Arbitrary<Long> amount = Arbitraries.longs().between(-Long.MAX_VALUE, Long.MAX_VALUE).filter(value -> value != 0);
        Arbitrary<Integer> style = Arbitraries.integers().between(0, 15);
        return Combinators.combine(identifier, date, date, amount, currency, text, text, style).as(Generated::new);
    }

    record Generated(String lineId, LocalDate bookingDate, LocalDate valueDate, long amount, CurrencyCode currency,
                     Optional<String> reference, Optional<String> description, int style) {

        String csv() {
            BigDecimal decimal = BigDecimal.valueOf(amount, currency.minorUnitDigits());
            String amountText = ((style & 8) == 0 ? decimal : decimal.stripTrailingZeros()).toPlainString();
            return String.join(",", field(lineId, 0), field(bookingDate.toString(), 1), field(valueDate.toString(), 2),
                    field(amountText, 0), field(currency.code(), 1), field(reference.orElse(""), 2),
                    field(description.orElse(""), 0));
        }

        void assertReadAs(BankLine line) {
            assertThat(line.lineId()).isEqualTo(lineId);
            assertThat(line.bookingDate()).isEqualTo(bookingDate);
            assertThat(line.valueDate()).isEqualTo(valueDate);
            assertThat(line.amount().minorUnits()).isEqualTo(amount);
            assertThat(line.amount().currency()).isEqualTo(currency);
            assertThat(line.reference()).isEqualTo(reference);
            assertThat(line.description()).isEqualTo(description);
            assertThat(line.extractedBatchId()).isEqualTo(
                    ParserRun.BANK.source().batchIdPattern().orElseThrow().extract(reference));
        }

        private String field(String value, int slot) {
            boolean quote = value.contains(",") || value.contains("\"") || ((style >> slot) & 1) == 1;
            return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
        }
    }
}
