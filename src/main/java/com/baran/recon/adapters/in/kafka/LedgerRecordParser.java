package com.baran.recon.adapters.in.kafka;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.baran.recon.adapters.in.kafka.ParsedRecord.Accepted;
import com.baran.recon.adapters.in.kafka.ParsedRecord.Rejected;
import com.baran.recon.application.ledger.LedgerEvent;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.InvalidCurrencyCodeException;

/**
 * Turns one record of the ledger topic into a {@link LedgerEvent}, or into the reason it cannot be
 * one (FR-LED-5, FR-LED-6). The record is hostile input: its value is decoded as strict UTF-8, read
 * as JSON with duplicate keys refused, and validated against the committed contract before any
 * field is read. No rejection message ever carries a value taken from the record, because the
 * dead-letter headers and the log both see it.
 */
final class LedgerRecordParser {

    static final String EVENT_ID_HEADER = "event-id";

    /** Copied from contracts/ by the build, so this is the committed file, not a copy of it. */
    static final String SCHEMA_RESOURCE = "/contracts/ledger-events.schema.json";

    /** The outbox id: a positive int64 in decimal, with no sign, space or leading zero. */
    private static final Pattern EVENT_ID = Pattern.compile("[1-9][0-9]{0,18}");

    /**
     * The contract's created_at pattern, read strictly: a date that does not exist, hour 24 and
     * second 60 are all refused rather than rolled over into a neighbouring instant.
     */
    private static final DateTimeFormatter CREATED_AT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withResolverStyle(ResolverStyle.STRICT);

    private final Schema schema;
    private final JsonMapper json;

    LedgerRecordParser() {
        this.schema = loadSchema();
        this.json = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    ParsedRecord parse(Headers headers, byte[] value) {
        Optional<Rejected> eventIdProblem = eventIdProblem(headers);
        if (eventIdProblem.isPresent()) {
            return eventIdProblem.get();
        }
        long eventId = Long.parseLong(utf8(headers.lastHeader(EVENT_ID_HEADER).value()).orElseThrow());
        if (value == null) {
            return schemaInvalid("the record has no value");
        }
        Optional<String> text = utf8(value);
        if (text.isEmpty()) {
            return schemaInvalid("the value is not valid UTF-8");
        }
        JsonNode payload;
        try {
            payload = json.readTree(text.get());
        } catch (JacksonException unreadable) {
            return notOneDocument();
        }
        if (payload.isMissingNode()) {
            return notOneDocument();
        }
        List<Error> errors = schema.validate(payload);
        if (!errors.isEmpty()) {
            return schemaInvalid(describe(errors));
        }
        return toEvent(eventId, payload);
    }

    private ParsedRecord toEvent(long eventId, JsonNode payload) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(payload.get("currency").asString());
        } catch (InvalidCurrencyCodeException notIso) {
            return schemaInvalid("currency is not an ISO 4217 code with minor units");
        }
        Optional<Instant> createdAt = Optional.empty();
        if (payload.has("created_at")) {
            try {
                createdAt = Optional.of(LocalDateTime.parse(payload.get("created_at").asString(), CREATED_AT)
                        .toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException notADate) {
                return new Rejected(DeadLetterReason.CREATED_AT_NOT_A_DATE, "created_at names no real instant");
            }
        }
        Optional<Long> entryId = payload.has("entry_id")
                ? Optional.of(payload.get("entry_id").asLong())
                : Optional.empty();
        return new Accepted(new LedgerEvent(
                eventId,
                entryId,
                createdAt,
                UUID.fromString(payload.get("transaction_id").asString()),
                UUID.fromString(payload.get("account_id").asString()),
                payload.get("amount").asLong(),
                currency,
                payload.get("tx_type").asString()));
    }

    /**
     * Absent, repeated and malformed are three different faults, and each message says which, so
     * the one code does not send whoever reads the dead-letter topic looking for the wrong one.
     */
    private static Optional<Rejected> eventIdProblem(Headers headers) {
        List<Header> found = new ArrayList<>();
        headers.headers(EVENT_ID_HEADER).forEach(found::add);
        if (found.isEmpty()) {
            return Optional.of(invalidEventId("the event-id header is absent"));
        }
        if (found.size() > 1) {
            return Optional.of(invalidEventId(
                    "the event-id header appears " + found.size() + " times; exactly one is required"));
        }
        byte[] raw = found.getFirst().value();
        Optional<String> text = raw == null ? Optional.empty() : utf8(raw);
        if (text.isEmpty() || !EVENT_ID.matcher(text.get()).matches() || !fitsInLong(text.get())) {
            return Optional.of(invalidEventId("the event-id header is not a positive decimal 64-bit integer"));
        }
        return Optional.empty();
    }

    private static boolean fitsInLong(String digits) {
        try {
            Long.parseLong(digits);
            return true;
        } catch (NumberFormatException tooLarge) {
            return false;
        }
    }

    /** Keyword and location only: a schema error's own message can quote the offending value. */
    private static String describe(List<Error> errors) {
        return errors.stream()
                .map(error -> error.getKeyword() + " at " + location(error.getInstanceLocation().toString())
                        + (error.getProperty() == null ? "" : " (" + error.getProperty() + ")"))
                .sorted()
                .collect(Collectors.joining("; ", "schema: ", ""));
    }

    private static String location(String pointer) {
        return pointer.isEmpty() ? "the root" : pointer;
    }

    private static Optional<String> utf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    private static Rejected notOneDocument() {
        return schemaInvalid("the value is not one JSON document without duplicate keys");
    }

    private static Rejected schemaInvalid(String message) {
        return new Rejected(DeadLetterReason.SCHEMA_INVALID, message);
    }

    private static Rejected invalidEventId(String message) {
        return new Rejected(DeadLetterReason.INVALID_EVENT_ID, message);
    }

    /** Remote fetching off: the draft 2020-12 meta-schema ships inside the validator. */
    private static Schema loadSchema() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
        try (InputStream in = LedgerRecordParser.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is not on the classpath");
            }
            return registry.getSchema(in);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
