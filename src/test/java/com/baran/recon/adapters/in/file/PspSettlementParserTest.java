package com.baran.recon.adapters.in.file;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.domain.item.PspLine;
import com.baran.recon.domain.item.PspLineType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.domain.statement.ValidationCode;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TDD 7.1: PSP settlement report CSV v1, line by line")
class PspSettlementParserTest {

    private static final String HEADER = PspSettlementParser.HEADER + "\n";
    private static final String VALID = "L-1,5f0c7a1e-0000-4000-8000-000000000001,B-001,2026-09-23,2026-09-24,PAYMENT,125.00,2.50,122.50,TRY";
    private static final CurrencyCode TRY = CurrencyCode.of("TRY");

    private final PspSettlementParser parser = new PspSettlementParser(4096, 1000);

    @Test
    @DisplayName("FR-ING-2: this parser reads the PSP settlement source type")
    void readsThePspType() {
        assertThat(parser.sourceType()).isEqualTo(SourceType.PSP_SETTLEMENT);
    }

    @Test
    @DisplayName("a valid line becomes a PSP line of the file and source, in exact minor units")
    void validLine() {
        PspLine line = psp(HEADER + VALID);

        assertThat(line.fileId()).isEqualTo(ParserRun.FILE_ID);
        assertThat(line.source()).isEqualTo(SourceCode.of("PSP_ALPHA"));
        assertThat(line.lineId()).isEqualTo("L-1");
        assertThat(line.reference()).contains("5f0c7a1e-0000-4000-8000-000000000001");
        assertThat(line.batchId()).isEqualTo("B-001");
        assertThat(line.transactionDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(line.type()).isEqualTo(PspLineType.PAYMENT);
        assertThat(line.gross()).isEqualTo(Money.of(12_500, TRY));
        assertThat(line.fee()).isEqualTo(Money.of(250, TRY));
        assertThat(line.net()).isEqualTo(Money.of(12_250, TRY));
    }

    @Test
    @DisplayName("an empty transaction_reference is absent, so only rule A3 can match the line")
    void emptyReferenceIsAbsent() {
        assertThat(psp(HEADER + VALID.replace("5f0c7a1e-0000-4000-8000-000000000001", "")).reference()).isEmpty();
    }

    @Test
    @DisplayName("a refund and a chargeback carry a negative gross; fewer decimals than the currency's are exact")
    void negativeTypesAndShortScale() {
        PspLine refund = psp(HEADER + "L-2,,B-001,2026-09-24,2026-09-24,REFUND,-40.5,0,-40.5,TRY");
        PspLine chargeback = psp(HEADER + "L-3,,B-001,2026-09-24,2026-09-24,CHARGEBACK,-10,1.25,-11.25,TRY");

        assertThat(refund.gross()).isEqualTo(Money.of(-4_050, TRY));
        assertThat(chargeback.net()).isEqualTo(Money.of(-1_125, TRY));
    }

    @Test
    @DisplayName("TDD 7.3: a real ISO currency outside the supported set is read like any other")
    void unsupportedIsoCurrencyIsRead() {
        PspLine euro = psp(HEADER + VALID.replace(",TRY", ",EUR"));
        PspLine yen = psp(HEADER + "L-4,,B-001,2026-09-24,2026-09-24,PAYMENT,1500,0,1500,JPY");

        assertThat(euro.currency()).isEqualTo(CurrencyCode.of("EUR"));
        assertThat(yen.gross()).isEqualTo(Money.of(1_500, CurrencyCode.of("JPY")));
    }

    @Test
    @DisplayName("quoted fields are read, a comma or quote inside the reference included")
    void quotedFields() {
        PspLine line = psp(HEADER + "\"L-5\",\"ref, with \"\"quote\"\"\",B-001,2026-09-24,2026-09-24,PAYMENT,\"1.00\",0,1.00,TRY");

        assertThat(line.reference()).contains("ref, with \"quote\"");
    }

    @Test
    @DisplayName("FR-ING-8: CRLF line endings and a leading BOM are accepted")
    void crlfAndBom() {
        ParserRun run = ParserRun.of(parser, ParserRun.PSP, "﻿" + PspSettlementParser.HEADER + "\r\n" + VALID + "\r\n"
                + VALID.replace("L-1", "L-2") + "\r\n");

        assertThat(run.headerError()).isEmpty();
        assertThat(run.lines()).hasSize(2).allMatch(ParsedLine.Psp.class::isInstance);
    }

    @Test
    @DisplayName("HEADER_MISMATCH: the bank header on a PSP source rejects the file before any line")
    void wrongFormatHeaderIsRefused() {
        ParserRun run = ParserRun.of(parser, ParserRun.PSP,
                "line_id,booking_date,value_date,amount,currency,reference,description\n" + VALID);

        assertThat(run.headerError()).contains(new LineError(1, ValidationCode.HEADER_MISMATCH));
        assertThat(run.lines()).isEmpty();
    }

    @ParameterizedTest(name = "{0}: [{1}]")
    @CsvSource(delimiter = '|', value = {
            // Record level
            "COLUMN_COUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00",
            "COLUMN_COUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY,extra",
            "INVALID_FORMAT|L-1,\"open,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            // Required columns
            "REQUIRED_MISSING|,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "REQUIRED_MISSING|L-1,,,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "REQUIRED_MISSING|L-1,,B-001,,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "REQUIRED_MISSING|L-1,,B-001,2026-09-24,,PAYMENT,1.00,0,1.00,TRY",
            "REQUIRED_MISSING|L-1,,B-001,2026-09-24,2026-09-24,,1.00,0,1.00,TRY",
            "REQUIRED_MISSING|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,,0,1.00,TRY",
            "REQUIRED_MISSING|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,,1.00,TRY",
            "REQUIRED_MISSING|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,,TRY",
            "REQUIRED_MISSING|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,",
            // Column formats
            "INVALID_FORMAT|L 1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "INVALID_FORMAT|L-1,,B/001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "INVALID_FORMAT|L-1,,B-001,2026-09-24,2026-09-24,payment,1.00,0,1.00,TRY",
            "INVALID_FORMAT|L-1,,B-001,2026-09-24,2026-09-24,FEE,1.00,0,1.00,TRY",
            "INVALID_FORMAT|L-1,a-reference-that-is-sixty-five-characters-long-and-so-one-too-many,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "INVALID_DATE|L-1,,B-001,2026-02-30,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "INVALID_DATE|L-1,,B-001,24/09/2026,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
            "INVALID_DATE|L-1,,B-001,2026-09-24,2026-9-24,PAYMENT,1.00,0,1.00,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,\"1,000.00\",0,1.00,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT, 1.00,0,1.00,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1e2,0,1e2,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,+1.00,0,1.00,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.,0,1.,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,.5,0,.5,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,01.00,0,01.00,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,-0.01,1.01,TRY",
            "INVALID_AMOUNT|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,92233720368547758.08,0,92233720368547758.08,TRY",
            "INVALID_CURRENCY|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,ABC",
            "INVALID_CURRENCY|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,try",
            "INVALID_CURRENCY|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.00,0,1.00,XAU",
            "SCALE_EXCEEDS_CURRENCY|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.005,0,1.005,TRY",
            "SCALE_EXCEEDS_CURRENCY|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,1.000,0,1.00,TRY",
            "SCALE_EXCEEDS_CURRENCY|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,100.5,0,100.5,JPY",
            // Cross-field rules
            "SIGN_TYPE_MISMATCH|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,-1.00,0,-1.00,TRY",
            "SIGN_TYPE_MISMATCH|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,0.00,0,0.00,TRY",
            "SIGN_TYPE_MISMATCH|L-1,,B-001,2026-09-24,2026-09-24,REFUND,1.00,0,1.00,TRY",
            "SIGN_TYPE_MISMATCH|L-1,,B-001,2026-09-24,2026-09-24,CHARGEBACK,1.00,0,1.00,TRY",
            "NET_AMOUNT_MISMATCH|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,125.00,2.50,122.51,TRY",
            "NET_AMOUNT_MISMATCH|L-1,,B-001,2026-09-24,2026-09-24,PAYMENT,125.00,2.50,125.00,TRY",
            "DATE_ORDER|L-1,,B-001,2026-09-25,2026-09-24,PAYMENT,1.00,0,1.00,TRY",
    })
    @DisplayName("TDD 7.3: each rule refuses the line with its own code")
    void eachRuleHasItsCode(ValidationCode code, String line) {
        ParsedLine outcome = ParserRun.of(parser, ParserRun.PSP, HEADER + line).only();

        assertThat(outcome).isEqualTo(new ParsedLine.Invalid(new LineError(2, code)));
    }

    @Test
    @DisplayName("INVALID_FORMAT: a control character in the reference, a lone CR included")
    void controlCharacterInReference() {
        ParsedLine outcome = ParserRun.of(parser, ParserRun.PSP, HEADER + VALID.replace("5f0c7a1e", "5f0c\r7a1e")).only();

        assertThat(outcome).isEqualTo(new ParsedLine.Invalid(new LineError(2, ValidationCode.INVALID_FORMAT)));
    }

    @Test
    @DisplayName("LINE_TOO_LONG and INVALID_ENCODING reach the parser's output with their line numbers")
    void readerCodesAreLineErrors() {
        PspSettlementParser shortLines = new PspSettlementParser(PspSettlementParser.HEADER.length(), 1000);
        java.io.ByteArrayOutputStream badByte = new java.io.ByteArrayOutputStream();
        badByte.writeBytes((HEADER + "L-1,ref").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        badByte.write(0xFF);
        badByte.writeBytes(",B-001,2026-09-23,2026-09-24,PAYMENT,1.00,0,1.00,TRY\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(ParserRun.of(shortLines, ParserRun.PSP, HEADER + VALID + " ".repeat(200)).only())
                .isEqualTo(new ParsedLine.Invalid(new LineError(2, ValidationCode.LINE_TOO_LONG)));
        assertThat(ParserRun.of(parser, ParserRun.PSP, badByte.toByteArray()).only())
                .isEqualTo(new ParsedLine.Invalid(new LineError(2, ValidationCode.INVALID_ENCODING)));
    }

    @Test
    @DisplayName("FR-ING-7: an invalid line does not stop the lines after it")
    void invalidLineDoesNotStopTheFile() {
        ParserRun run = ParserRun.of(parser, ParserRun.PSP, HEADER + "broken\n" + VALID + "\n");

        assertThat(run.lines()).extracting(ParsedLine::lineNumber).containsExactly(2L, 3L);
        assertThat(run.lines().get(0)).isInstanceOf(ParsedLine.Invalid.class);
        assertThat(run.lines().get(1)).isInstanceOf(ParsedLine.Psp.class);
    }

    @Test
    @DisplayName("a header-only file has no lines and no error")
    void headerOnly() {
        assertThat(ParserRun.of(parser, ParserRun.PSP, HEADER)).isEqualTo(new ParserRun(Optional.empty(), java.util.List.of()));
    }

    private PspLine psp(String content) {
        ParsedLine outcome = ParserRun.of(parser, ParserRun.PSP, content).only();
        assertThat(outcome).isInstanceOf(ParsedLine.Psp.class);
        return ((ParsedLine.Psp) outcome).line();
    }
}
