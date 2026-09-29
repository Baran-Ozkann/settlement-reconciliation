package com.baran.recon.adapters.in.file;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.domain.item.BankLine;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.ValidationCode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TDD 7.2: bank statement CSV v1, line by line")
class BankStatementParserTest {

    private static final String HEADER = BankStatementParser.HEADER + "\n";
    private static final String VALID = "S-1,2026-09-24,2026-09-25,245.00,TRY,PSP ALPHA BATCH-B-001 payout,Settlement";
    private static final CurrencyCode TRY = CurrencyCode.of("TRY");

    private final BankStatementParser parser = new BankStatementParser(4096, 1000);

    @Test
    @DisplayName("FR-ING-2: this parser reads the bank statement source type")
    void readsTheBankType() {
        assertThat(parser.sourceType()).isEqualTo(SourceType.BANK_STATEMENT);
    }

    @Test
    @DisplayName("a valid line becomes a bank line with its batch id extracted by the source's pattern")
    void validLine() {
        BankLine line = bank(HEADER + VALID);

        assertThat(line.fileId()).isEqualTo(ParserRun.FILE_ID);
        assertThat(line.source()).isEqualTo(SourceCode.of("BANK_MAIN"));
        assertThat(line.lineId()).isEqualTo("S-1");
        assertThat(line.bookingDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(line.amount()).isEqualTo(Money.of(24_500, TRY));
        assertThat(line.reference()).contains("PSP ALPHA BATCH-B-001 payout");
        assertThat(line.extractedBatchId()).contains("B-001");
        assertThat(line.description()).contains("Settlement");
    }

    @Test
    @DisplayName("a debit is negative; empty reference and description are absent; no batch id is not an error")
    void debitWithoutReference() {
        BankLine line = bank(HEADER + "S-2,2026-09-24,2026-09-24,-12.30,TRY,,");

        assertThat(line.amount()).isEqualTo(Money.of(-1_230, TRY));
        assertThat(line.reference()).isEmpty();
        assertThat(line.extractedBatchId()).isEmpty();
        assertThat(line.description()).isEmpty();
    }

    @Test
    @DisplayName("TDD 7.3: a real ISO currency outside the supported set is read like any other")
    void unsupportedIsoCurrencyIsRead() {
        assertThat(bank(HEADER + VALID.replace(",TRY,", ",USD,")).amount().currency()).isEqualTo(CurrencyCode.of("USD"));
    }

    @ParameterizedTest(name = "{0}: [{1}]")
    @CsvSource(delimiter = '|', value = {
            "COLUMN_COUNT|S-1,2026-09-24,2026-09-25,245.00,TRY,ref",
            "INVALID_FORMAT|S-1,2026-09-24,2026-09-25,245.00,TRY,\"ref\"x,desc",
            "REQUIRED_MISSING|,2026-09-24,2026-09-25,245.00,TRY,ref,desc",
            "REQUIRED_MISSING|S-1,,2026-09-25,245.00,TRY,ref,desc",
            "REQUIRED_MISSING|S-1,2026-09-24,,245.00,TRY,ref,desc",
            "REQUIRED_MISSING|S-1,2026-09-24,2026-09-25,,TRY,ref,desc",
            "REQUIRED_MISSING|S-1,2026-09-24,2026-09-25,245.00,,ref,desc",
            "INVALID_FORMAT|S.1,2026-09-24,2026-09-25,245.00,TRY,ref,desc",
            "INVALID_DATE|S-1,2026-13-01,2026-09-25,245.00,TRY,ref,desc",
            "INVALID_DATE|S-1,2026-09-24,20260925,245.00,TRY,ref,desc",
            "INVALID_AMOUNT|S-1,2026-09-24,2026-09-25,\"245,00\",TRY,ref,desc",
            "INVALID_AMOUNT|S-1,2026-09-24,2026-09-25,0.00,TRY,ref,desc",
            "INVALID_AMOUNT|S-1,2026-09-24,2026-09-25,-0,TRY,ref,desc",
            "INVALID_AMOUNT|S-1,2026-09-24,2026-09-25,TRY,245.00,ref,desc",
            "INVALID_CURRENCY|S-1,2026-09-24,2026-09-25,245.00,TL,ref,desc",
            "SCALE_EXCEEDS_CURRENCY|S-1,2026-09-24,2026-09-25,245.001,TRY,ref,desc",
    })
    @DisplayName("TDD 7.3: each rule refuses the line with its own code")
    void eachRuleHasItsCode(ValidationCode code, String line) {
        assertThat(ParserRun.of(parser, ParserRun.BANK, HEADER + line).only())
                .isEqualTo(new ParsedLine.Invalid(new LineError(2, code)));
    }

    @Test
    @DisplayName("INVALID_FORMAT: a reference or description over 140 characters, or holding a control character")
    void textLimits() {
        String tooLong = "r".repeat(141);

        assertThat(ParserRun.of(parser, ParserRun.BANK, HEADER + "S-1,2026-09-24,2026-09-25,1.00,TRY," + tooLong + ",d").only())
                .isEqualTo(new ParsedLine.Invalid(new LineError(2, ValidationCode.INVALID_FORMAT)));
        assertThat(ParserRun.of(parser, ParserRun.BANK, HEADER + "S-1,2026-09-24,2026-09-25,1.00,TRY,r," + tooLong).only())
                .isEqualTo(new ParsedLine.Invalid(new LineError(2, ValidationCode.INVALID_FORMAT)));
        assertThat(ParserRun.of(parser, ParserRun.BANK, HEADER + "S-1,2026-09-24,2026-09-25,1.00,TRY,r,tab\there").only())
                .isEqualTo(new ParsedLine.Invalid(new LineError(2, ValidationCode.INVALID_FORMAT)));
        assertThat(bank(HEADER + "S-1,2026-09-24,2026-09-25,1.00,TRY," + "r".repeat(140) + ",d").reference())
                .hasValueSatisfying(reference -> assertThat(reference).hasSize(140));
    }

    @Test
    @DisplayName("HEADER_MISMATCH: the PSP header on a bank source rejects the file before any line")
    void wrongFormatHeaderIsRefused() {
        ParserRun run = ParserRun.of(parser, ParserRun.BANK, PspSettlementParser.HEADER + "\n" + VALID);

        assertThat(run.headerError()).contains(new LineError(1, ValidationCode.HEADER_MISMATCH));
        assertThat(run.lines()).isEmpty();
    }

    private BankLine bank(String content) {
        ParsedLine outcome = ParserRun.of(parser, ParserRun.BANK, content).only();
        assertThat(outcome).isInstanceOf(ParsedLine.Bank.class);
        return ((ParsedLine.Bank) outcome).line();
    }
}
