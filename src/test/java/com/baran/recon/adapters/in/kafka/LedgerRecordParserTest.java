package com.baran.recon.adapters.in.kafka;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.baran.recon.adapters.in.kafka.ParsedRecord.Accepted;
import com.baran.recon.adapters.in.kafka.ParsedRecord.Rejected;
import com.baran.recon.application.ledger.LedgerEvent;
import com.baran.recon.domain.money.CurrencyCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract check the consumer runs on every record, driven by the committed samples in
 * contracts/samples as well as by records the samples cannot express (headers, bytes, dates).
 */
@DisplayName("FR-LED-5, FR-LED-6: a ledger record is accepted or rejected with the right code")
class LedgerRecordParserTest {

    private static final Path CONTRACTS = Path.of("contracts");
    private static final Path SAMPLES = CONTRACTS.resolve("samples");

    private static final String TRANSFER_CREDIT = """
            {"transaction_id":"3f1c0c4e-7a41-4d2b-9d4f-0a1b2c3d4e5f","account_id":"9b2d5e77-1c3a-4f8e-8b6d-2e4a6c8e0a12",
             "amount":125000,"currency":"%s","tx_type":"TRANSFER","entry_id":4102,"created_at":"%s"}""";

    private final LedgerRecordParser parser = new LedgerRecordParser();

    @Test
    @DisplayName("FR-LED-6: the schema on the classpath is byte for byte the committed contract")
    void classpathSchemaIsTheCommittedOne() throws IOException {
        try (InputStream onClasspath = LedgerRecordParser.class.getResourceAsStream(LedgerRecordParser.SCHEMA_RESOURCE)) {
            assertThat(onClasspath).isNotNull();
            assertThat(onClasspath.readAllBytes()).isEqualTo(Files.readAllBytes(CONTRACTS.resolve("ledger-events.schema.json")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSamples")
    @DisplayName("FR-LED-6, FR-LED-7, FR-LED-9: every valid contract sample is accepted")
    void validSampleIsAccepted(Path sample) throws IOException {
        assertThat(parser.parse(eventId("7001"), Files.readAllBytes(sample))).isInstanceOf(Accepted.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSamples")
    @DisplayName("FR-LED-5, FR-LED-6: every invalid contract sample is SCHEMA_INVALID")
    void invalidSampleIsSchemaInvalid(Path sample) throws IOException {
        ParsedRecord parsed = parser.parse(eventId("7002"), Files.readAllBytes(sample));

        assertThat(parsed).isInstanceOfSatisfying(Rejected.class, rejected -> {
            assertThat(rejected.reason()).isEqualTo(DeadLetterReason.SCHEMA_INVALID);
            assertThat(rejected.message()).startsWith("schema: ");
        });
    }

    @Test
    @DisplayName("a seven-field event carries every field, created_at read as the UTC instant it names")
    void sevenFieldEventIsMapped() {
        ParsedRecord parsed = parser.parse(eventId("9223372036854775807"),
                bytes(TRANSFER_CREDIT.formatted("TRY", "2026-09-23T21:00:00.000000Z")));

        assertThat(parsed).isEqualTo(new Accepted(new LedgerEvent(Long.MAX_VALUE, Optional.of(4102L),
                Optional.of(Instant.parse("2026-09-23T21:00:00Z")),
                UUID.fromString("3f1c0c4e-7a41-4d2b-9d4f-0a1b2c3d4e5f"),
                UUID.fromString("9b2d5e77-1c3a-4f8e-8b6d-2e4a6c8e0a12"), 125_000, CurrencyCode.of("TRY"), "TRANSFER")));
    }

    @Test
    @DisplayName("FR-LED-7: a five-field event is accepted with no entry id and no created_at")
    void fiveFieldEventIsAcceptedUndated() throws IOException {
        ParsedRecord parsed = parser.parse(eventId("7003"),
                Files.readAllBytes(SAMPLES.resolve("valid-five-field-written-before-entry-reference.json")));

        assertThat(parsed).isInstanceOfSatisfying(Accepted.class, accepted -> {
            assertThat(accepted.event().entryId()).isEmpty();
            assertThat(accepted.event().createdAt()).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "2026-02-30T10:00:00.000000Z",
            "2026-09-24T24:00:00.000000Z",
            "2026-06-30T23:59:60.000000Z",
            "2026-13-01T00:00:00.000000Z"})
    @DisplayName("FR-LED-5: created_at in the contract's shape but naming no real instant is CREATED_AT_NOT_A_DATE")
    void createdAtThatIsNoInstant(String createdAt) {
        ParsedRecord parsed = parser.parse(eventId("7004"), bytes(TRANSFER_CREDIT.formatted("TRY", createdAt)));

        assertThat(parsed).isEqualTo(new Rejected(DeadLetterReason.CREATED_AT_NOT_A_DATE, "created_at names no real instant"));
    }

    @Test
    @DisplayName("FR-LED-5: no event-id header is INVALID_EVENT_ID, and the message says it is absent")
    void eventIdAbsent() {
        ParsedRecord parsed = parser.parse(new RecordHeaders(), validValue());

        assertThat(parsed).isEqualTo(new Rejected(DeadLetterReason.INVALID_EVENT_ID, "the event-id header is absent"));
    }

    @Test
    @DisplayName("FR-LED-5: two event-id headers are INVALID_EVENT_ID, and the message says it is repeated")
    void eventIdRepeated() {
        Headers headers = eventId("7005");
        headers.add(LedgerRecordParser.EVENT_ID_HEADER, bytes("7005"));

        assertThat(parser.parse(headers, validValue())).isEqualTo(new Rejected(DeadLetterReason.INVALID_EVENT_ID,
                "the event-id header appears 2 times; exactly one is required"));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "0", "-7", "+7", "07", " 7", "7 ", "7.0", "abc", "9223372036854775808",
            "0b5a4c1e-0000-4000-8000-000000000000"})
    @DisplayName("FR-LED-5: a malformed event-id is INVALID_EVENT_ID, and the message says it is malformed")
    void eventIdMalformed(String value) {
        assertThat(parser.parse(eventId(value), validValue())).isEqualTo(new Rejected(DeadLetterReason.INVALID_EVENT_ID,
                "the event-id header is not a positive decimal 64-bit integer"));
    }

    @Test
    @DisplayName("FR-LED-5: an event-id that is not UTF-8, or has no value at all, is malformed")
    void eventIdNotText() {
        Headers notUtf8 = new RecordHeaders().add(LedgerRecordParser.EVENT_ID_HEADER, new byte[] {(byte) 0xC3, (byte) 0x28});
        Headers noValue = new RecordHeaders().add(LedgerRecordParser.EVENT_ID_HEADER, null);

        assertThat(parser.parse(notUtf8, validValue())).extracting(parsed -> ((Rejected) parsed).message())
                .isEqualTo("the event-id header is not a positive decimal 64-bit integer");
        assertThat(parser.parse(noValue, validValue())).extracting(parsed -> ((Rejected) parsed).message())
                .isEqualTo("the event-id header is not a positive decimal 64-bit integer");
    }

    @Test
    @DisplayName("an event-id problem is reported before a schema problem: the record could not be deduplicated either way")
    void eventIdIsCheckedFirst() {
        assertThat(parser.parse(new RecordHeaders(), bytes("not json")))
                .extracting(parsed -> ((Rejected) parsed).reason()).isEqualTo(DeadLetterReason.INVALID_EVENT_ID);
    }

    @Test
    @DisplayName("FR-LED-5: a tombstone (no value) is SCHEMA_INVALID")
    void tombstone() {
        assertThat(parser.parse(eventId("7006"), null))
                .isEqualTo(new Rejected(DeadLetterReason.SCHEMA_INVALID, "the record has no value"));
    }

    @Test
    @DisplayName("FR-LED-5: a value that is not valid UTF-8 is SCHEMA_INVALID")
    void invalidUtf8() {
        byte[] value = validValue();
        value[value.length - 3] = (byte) 0xFF;

        assertThat(parser.parse(eventId("7007"), value))
                .isEqualTo(new Rejected(DeadLetterReason.SCHEMA_INVALID, "the value is not valid UTF-8"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"amount\":1,\"amount\":2}",
            "not json",
            "{\"transaction_id\":\"3f1c0c4e-7a41-4d2b-9d4f-0a1b2c3d4e5f\"} {}",
            ""})
    @DisplayName("FR-LED-5: duplicate keys, trailing content or no JSON at all is SCHEMA_INVALID")
    void notOneCleanJsonDocument(String value) {
        assertThat(parser.parse(eventId("7008"), bytes(value))).isEqualTo(new Rejected(DeadLetterReason.SCHEMA_INVALID,
                "the value is not one JSON document without duplicate keys"));
    }

    @Test
    @DisplayName("FR-LED-5: a JSON value of the wrong type is SCHEMA_INVALID, by the schema")
    void jsonArray() {
        assertThat(parser.parse(eventId("7009"), bytes("[]")))
                .isInstanceOfSatisfying(Rejected.class, rejected -> assertThat(rejected.message()).isEqualTo("schema: type at the root"));
    }

    @Test
    @DisplayName("FR-LED-5: a currency in the contract's shape that is no ISO 4217 code (ABC) is SCHEMA_INVALID")
    void nonIsoCurrencyIsSchemaInvalid() {
        ParsedRecord parsed = parser.parse(eventId("7010"), bytes(TRANSFER_CREDIT.formatted("ABC", "2026-09-24T08:15:42.318204Z")));

        assertThat(parsed).isEqualTo(new Rejected(DeadLetterReason.SCHEMA_INVALID,
                "currency is not an ISO 4217 code with minor units"));
    }

    @Test
    @DisplayName("a real ISO 4217 currency outside the supported set (USD) passes the contract: supported is not the contract's call")
    void isoCurrencyOutsideTheSupportedSetIsAccepted() {
        ParsedRecord parsed = parser.parse(eventId("7011"), bytes(TRANSFER_CREDIT.formatted("USD", "2026-09-24T08:15:42.318204Z")));

        assertThat(parsed).isInstanceOfSatisfying(Accepted.class,
                accepted -> assertThat(accepted.event().currency()).isEqualTo(CurrencyCode.of("USD")));
    }

    @Test
    @DisplayName("a schema rejection names keyword and location, never the value that failed")
    void rejectionCarriesNoRecordValue() throws IOException {
        byte[] sample = Files.readAllBytes(SAMPLES.resolve("invalid-account-id-not-a-uuid.json"));
        String offending = "account-42";
        assertThat(new String(sample, StandardCharsets.UTF_8)).as("the sample's bad value").contains(offending);

        Rejected rejected = (Rejected) parser.parse(eventId("7012"), sample);

        assertThat(rejected.message()).contains("pattern").contains("account_id").doesNotContain(offending);
    }

    static Stream<Path> validSamples() throws IOException {
        return samples("valid-");
    }

    static Stream<Path> invalidSamples() throws IOException {
        return samples("invalid-");
    }

    private static Stream<Path> samples(String prefix) throws IOException {
        try (Stream<Path> files = Files.list(SAMPLES)) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix)).sorted().toList().stream();
        }
    }

    private static byte[] validValue() {
        return bytes(TRANSFER_CREDIT.formatted("TRY", "2026-09-24T08:15:42.318204Z"));
    }

    private static Headers eventId(String value) {
        return new RecordHeaders().add(LedgerRecordParser.EVENT_ID_HEADER, bytes(value));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
