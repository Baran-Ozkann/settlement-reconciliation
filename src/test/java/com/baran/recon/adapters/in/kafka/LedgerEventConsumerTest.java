package com.baran.recon.adapters.in.kafka;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.support.ReconKafka.header;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer end to end: a real broker, the ledger's topic with the ledger's three partitions, the
 * application's listener, and a real PostgreSQL as recon_app. The test plays the ledger's relay.
 *
 * <p>The context is closed when the class ends, so its listener leaves the consumer group rather
 * than competing with a later class's for the topic's partitions.
 *
 * <p>Absence is never asserted by waiting. Where a record must not be stored, a sentinel is published
 * after it on the same key, which is the same partition, so once the sentinel is stored the record
 * before it has been processed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_ALPHA",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].ledger-accounts[0]=" + LedgerEventConsumerTest.CLEARING_ACCOUNT})
@ActiveProfiles("test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@Import(FailingLedgerEntryStore.Injection.class)
@DisplayName("FR-LED-1..9: the ledger event consumer")
class LedgerEventConsumerTest {

    static final String CLEARING_ACCOUNT = "00000000-0000-4000-8000-00000000c1ea";

    private static final UUID CLEARING = UUID.fromString(CLEARING_ACCOUNT);
    private static final Duration AWAIT = Duration.ofSeconds(60);

    /** Event and entry ids of this class alone; the database and the broker outlive it. */
    private static final AtomicLong IDS = new AtomicLong(9_300_000_000L);

    private static KafkaProducer<String, byte[]> ledger;
    private static KafkaConsumer<String, byte[]> deadLetterReader;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.registerListening(registry);
    }

    @BeforeAll
    static void playTheLedger() {
        ledger = ReconKafka.ledgerProducer();
        deadLetterReader = ReconKafka.reader(LedgerTopics.DEAD_LETTER);
    }

    @AfterAll
    static void stopPlaying() {
        ledger.close();
        deadLetterReader.close();
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private MeterRegistry meters;

    /** The application's store, wrapped by {@link FailingLedgerEntryStore.Injection}. */
    @Autowired
    private LedgerEntryStore store;

    @Test
    @DisplayName("the test broker's ledger topic has the ledger's three partitions, whichever test started it")
    void ledgerTopicHasTheLedgersPartitions() throws Exception {
        try (Admin admin = ReconKafka.admin()) {
            assertThat(admin.describeTopics(List.of(LedgerTopics.ACCOUNT_ACTIVITY)).allTopicNames()
                    .get(10, TimeUnit.SECONDS).get(LedgerTopics.ACCOUNT_ACTIVITY).partitions())
                    .hasSize(ReconKafka.LEDGER_TOPIC_PARTITIONS);
        }
    }

    @Test
    @DisplayName("FR-LED-1, FR-LED-2: an event on a mapped account is stored; one on any other account is not")
    void onlyMappedAccountsAreStored() {
        UUID otherAccount = UUID.randomUUID();
        long unmapped = IDS.incrementAndGet();
        long mapped = IDS.incrementAndGet();

        publish(otherAccount, unmapped, event(otherAccount, IDS.incrementAndGet()));
        publish(otherAccount, IDS.incrementAndGet(), event(CLEARING, IDS.incrementAndGet()));
        publish(CLEARING, mapped, event(CLEARING, IDS.incrementAndGet()));

        awaitStored(mapped);
        assertThat(row(mapped).get()).containsEntry("source_code", "PSP_ALPHA").containsEntry("amount", 125_000L);
        assertThat(row(unmapped)).as("an event on an unmapped account").isEmpty();
    }

    @Test
    @DisplayName("TDD 6: the value date is created_at's date in Europe/Istanbul, not in UTC")
    void valueDateInIstanbul() {
        long eventId = IDS.incrementAndGet();

        publish(CLEARING, eventId, event(CLEARING, IDS.incrementAndGet(), "2026-09-23T21:00:00.000000Z", "TRANSFER"));

        awaitStored(eventId);
        assertThat(row(eventId).get().get("value_date")).isEqualTo(Date.valueOf(LocalDate.of(2026, 9, 24)));
    }

    @Test
    @DisplayName("FR-LED-3: the same event delivered three times produces one row")
    void duplicateDeliveryIsStoredOnce() {
        long eventId = IDS.incrementAndGet();
        byte[] value = event(CLEARING, IDS.incrementAndGet());

        publish(CLEARING, eventId, value);
        publish(CLEARING, eventId, value);
        publish(CLEARING, eventId, value);
        long sentinel = publishSentinel(CLEARING);

        awaitStored(sentinel);
        assertThat(rowsWithEventId(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-LED-7: a five-field event is stored with null entry id, created_at and value date, never back-dated from the record timestamp")
    void fiveFieldEventIsNeverBackDated() {
        long eventId = IDS.incrementAndGet();
        byte[] fiveFields = """
                {"transaction_id":"%s","account_id":"%s","amount":-4500,"currency":"TRY","tx_type":"TRANSFER"}"""
                .formatted(UUID.randomUUID(), CLEARING).getBytes(StandardCharsets.UTF_8);
        long longAgo = Instant.parse("2025-01-15T09:00:00Z").toEpochMilli();

        send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, longAgo, CLEARING.toString(), fiveFields,
                List.of(eventIdHeader(eventId))));

        awaitStored(eventId);
        Map<String, Object> row = row(eventId).get();
        assertThat(row.get("ledger_entry_id")).isNull();
        assertThat(row.get("created_at")).isNull();
        assertThat(row.get("value_date")).isNull();
    }

    @Test
    @DisplayName("FR-LED-9: an unmapped tx_type is projected, not dead-lettered, warned about once and counted per entry")
    void unmappedTxTypeIsProjected(CapturedOutput output) {
        double countBefore = unmappedTxTypeCount("ADJUSTMENT");
        long first = IDS.incrementAndGet();
        long second = IDS.incrementAndGet();

        publish(CLEARING, first, event(CLEARING, IDS.incrementAndGet(), "2026-09-24T08:15:42.318204Z", "ADJUSTMENT"));
        RecordMetadata sent = publish(CLEARING, second,
                event(CLEARING, IDS.incrementAndGet(), "2026-09-24T08:15:42.318204Z", "ADJUSTMENT"));

        awaitStored(second);
        assertThat(row(first).get()).containsEntry("tx_type", "ADJUSTMENT");
        assertThat(row(second).get()).containsEntry("tx_type", "ADJUSTMENT");
        awaitCommitted(sent);
        assertThat(unmappedTxTypeCount("ADJUSTMENT") - countBefore).isEqualTo(2);
        assertThat(output.getOut().split("WARN .*with tx_type ADJUSTMENT,", -1)).as("one WARN").hasSize(2);
    }

    @Test
    @DisplayName("FR-LED-5: a currency in the contract's shape that is no ISO 4217 code (ABC) is dead-lettered as SCHEMA_INVALID")
    void nonIsoCurrencyIsDeadLettered() {
        long eventId = IDS.incrementAndGet();

        RecordMetadata invalid = publish(CLEARING, eventId, event(CLEARING, IDS.incrementAndGet(), "ABC"));
        long sentinel = publishSentinel(CLEARING);

        assertDeadLetter(awaitDeadLetter(invalid), invalid, "SCHEMA_INVALID",
                "currency is not an ISO 4217 code with minor units");
        awaitStored(sentinel);
        assertThat(row(eventId)).isEmpty();
    }

    @Test
    @DisplayName("TDD 6: a real ISO 4217 currency outside the supported set (USD) is projected, warned about once and counted")
    void unsupportedIsoCurrencyIsProjected(CapturedOutput output) {
        double countBefore = unsupportedCurrencyCount("USD");
        long first = IDS.incrementAndGet();
        long second = IDS.incrementAndGet();

        publish(CLEARING, first, event(CLEARING, IDS.incrementAndGet(), "USD"));
        publish(CLEARING, second, event(CLEARING, IDS.incrementAndGet(), "USD"));

        awaitStored(second);
        assertThat(row(first).get()).containsEntry("currency", "USD");
        assertThat(row(second).get()).containsEntry("currency", "USD");
        assertThat(unsupportedCurrencyCount("USD") - countBefore).isEqualTo(2);
        assertThat(output.getOut().split("WARN .*ledger entry in USD,", -1)).as("one WARN").hasSize(2);
    }

    private double unsupportedCurrencyCount(String currency) {
        Counter counter = meters.find(UnfamiliarValueReporter.UNSUPPORTED_CURRENCY).tag("currency", currency).counter();
        return counter == null ? 0 : counter.count();
    }

    private double unmappedTxTypeCount(String txType) {
        Counter counter = meters.find(UnfamiliarValueReporter.UNMAPPED_TX_TYPE).tag("tx_type", txType).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("FR-LED-5: SCHEMA_INVALID is dead-lettered with every header, and the partition is not blocked")
    void schemaInvalidIsDeadLettered() {
        long eventId = IDS.incrementAndGet();
        byte[] noTransactionId = """
                {"account_id":"%s","amount":100,"currency":"TRY","tx_type":"TRANSFER"}"""
                .formatted(CLEARING).getBytes(StandardCharsets.UTF_8);

        RecordMetadata invalid = publish(CLEARING, eventId, noTransactionId);
        long sentinel = publishSentinel(CLEARING);

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(invalid);
        assertDeadLetter(deadLetter, invalid, "SCHEMA_INVALID", "schema: required at the root (transaction_id)");
        assertThat(deadLetter.value()).isEqualTo(noTransactionId);
        assertThat(header(deadLetter, "event-id")).isEqualTo(Long.toString(eventId));
        awaitStored(sentinel);
        assertThat(row(eventId)).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-5: CREATED_AT_NOT_A_DATE is dead-lettered with every header, and the partition is not blocked")
    void createdAtNotADateIsDeadLettered() {
        long eventId = IDS.incrementAndGet();

        RecordMetadata invalid = publish(CLEARING, eventId,
                event(CLEARING, IDS.incrementAndGet(), "2026-02-30T10:00:00.000000Z", "TRANSFER"));
        long sentinel = publishSentinel(CLEARING);

        assertDeadLetter(awaitDeadLetter(invalid), invalid, "CREATED_AT_NOT_A_DATE", "created_at names no real instant");
        awaitStored(sentinel);
        assertThat(row(eventId)).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-5: INVALID_EVENT_ID for an absent header is dead-lettered with every header")
    void absentEventIdIsDeadLettered() {
        RecordMetadata invalid = send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, CLEARING.toString(),
                event(CLEARING, IDS.incrementAndGet())));
        long sentinel = publishSentinel(CLEARING);

        assertDeadLetter(awaitDeadLetter(invalid), invalid, "INVALID_EVENT_ID", "the event-id header is absent");
        awaitStored(sentinel);
    }

    @Test
    @DisplayName("FR-LED-5: INVALID_EVENT_ID for a repeated header is dead-lettered, and says it was repeated")
    void repeatedEventIdIsDeadLettered() {
        long eventId = IDS.incrementAndGet();
        RecordMetadata invalid = send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, CLEARING.toString(),
                event(CLEARING, IDS.incrementAndGet()), List.of(eventIdHeader(eventId), eventIdHeader(eventId + 1))));
        long sentinel = publishSentinel(CLEARING);

        assertDeadLetter(awaitDeadLetter(invalid), invalid, "INVALID_EVENT_ID",
                "the event-id header appears 2 times; exactly one is required");
        awaitStored(sentinel);
        assertThat(row(eventId)).isEmpty();
    }

    @Test
    @DisplayName("FR-LED-5: INVALID_EVENT_ID for a malformed header is dead-lettered, and says it was malformed")
    void malformedEventIdIsDeadLettered() {
        RecordMetadata invalid = send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, CLEARING.toString(),
                event(CLEARING, IDS.incrementAndGet()),
                List.of(new RecordHeader("event-id",
                        "not-a-number".getBytes(StandardCharsets.UTF_8)))));
        long sentinel = publishSentinel(CLEARING);

        assertDeadLetter(awaitDeadLetter(invalid), invalid, "INVALID_EVENT_ID",
                "the event-id header is not a positive decimal 64-bit integer");
        awaitStored(sentinel);
    }

    @Test
    @DisplayName("FR-LED-5, FR-LED-8: the same entry_id under a new event-id is refused, logged at ERROR and dead-lettered as DUPLICATE_ENTRY_ID")
    void duplicateEntryIdIsDeadLettered(CapturedOutput output) {
        long entryId = IDS.incrementAndGet();
        long firstEvent = IDS.incrementAndGet();
        long secondEvent = IDS.incrementAndGet();
        publish(CLEARING, firstEvent, event(CLEARING, entryId));
        awaitStored(firstEvent);

        RecordMetadata duplicate = publish(CLEARING, secondEvent, event(CLEARING, entryId));
        long sentinel = publishSentinel(CLEARING);

        assertDeadLetter(awaitDeadLetter(duplicate), duplicate, "DUPLICATE_ENTRY_ID",
                "entry_id " + entryId + " was already projected from another event");
        awaitStored(sentinel);
        assertThat(row(secondEvent)).as("never silently kept").isEmpty();
        assertThat(rowsWithEntryId(entryId)).isEqualTo(1);
        assertThat(output.getOut()).containsPattern("ERROR .*Ledger entry " + entryId
                + " arrived again under event-id " + secondEvent + " at ledger\\.account-activity-\\d+@" + duplicate.offset());
    }

    @Test
    @DisplayName("FR-LED-4: the offset is committed once the record's transaction has committed")
    void offsetIsCommittedAfterTheStore() {
        long eventId = IDS.incrementAndGet();

        RecordMetadata sent = publish(CLEARING, eventId, event(CLEARING, IDS.incrementAndGet()));

        awaitCommitted(sent);
        assertThat(row(eventId)).as("stored by the time its offset is committed").isPresent();
    }

    /**
     * The settings the FR-LED-4 break proofs depend on, pinned. Under MANUAL a batch's offsets are
     * committed when the listener returns normally, after the error handler has handled what it
     * threw, or - for offsets already acknowledged - when the container stops. MANUAL_IMMEDIATE
     * would commit at the acknowledge call itself, and auto-commit on the consumer's own schedule
     * (Spring Kafka refuses auto-commit with MANUAL at startup, but not with a non-manual ack mode).
     * Either would bring back the lost record the break proofs build, with no test code changed.
     */
    @Test
    @DisplayName("FR-LED-4: the container commits only acknowledged offsets, after the listener returns, never on its own")
    void containerCommitsOnlyAfterTheListener() {
        MessageListenerContainer container = listeners.getListenerContainer(LedgerEventListener.LISTENER_ID);

        assertThat(container.getContainerProperties().getAckMode())
                .isEqualTo(AckMode.MANUAL);
        assertThat(kafkaProperties.buildConsumerProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    /**
     * The database fails after the insert, inside the transaction, until the test releases it. While
     * it fails, the insert is rolled back and the offset stays where it was; each failed attempt is
     * logged at ERROR with the backoff state, not only the first. Once released, the redelivered
     * batch is stored once and only then is the offset committed.
     */
    @Test
    @DisplayName("FR-LED-4: a failure before the commit rolls back and keeps the offset; the record is redelivered and stored once")
    void crashBeforeCommitIsRedelivered(CapturedOutput output) throws Exception {
        long eventId = IDS.incrementAndGet();
        FailingLedgerEntryStore failing = FailingLedgerEntryStore.of(store);
        failing.failUntilReleased();
        RecordMetadata sent;
        try {
            sent = publish(CLEARING, eventId, event(CLEARING, IDS.incrementAndGet()));
            Awaitility.await().atMost(AWAIT).until(() -> failing.failures() >= 2);

            assertThat(row(eventId)).as("rolled back").isEmpty();
            assertThat(committedOffset(sent)).as("offset while the store fails").isLessThanOrEqualTo(sent.offset());
        } finally {
            failing.release();
        }

        awaitStored(eventId);
        awaitCommitted(sent);
        assertThat(rowsWithEventId(eventId)).isEqualTo(1);
        assertThat(output.getOut())
                .containsPattern("ERROR .*Ledger batch of \\d+ records failed on attempt 1, caused by .*"
                        + "<- org\\.springframework\\.dao\\.TransientDataAccessResourceException; "
                        + "retrying in PT0\\.5S, failing for PT0S so far")
                .containsPattern("ERROR .*Ledger batch of \\d+ records failed on attempt 2, caused by .*"
                        + "<- org\\.springframework\\.dao\\.TransientDataAccessResourceException; "
                        + "retrying in PT1S, failing for PT0\\.\\d+S so far")
                .doesNotContain(FailingLedgerEntryStore.FAILURE);
    }

    /**
     * A container that stops commits the offsets its listener has acknowledged so far. The listener
     * acknowledges only after the projection has committed, so stopping it while the store fails -
     * a shutdown during a database outage - commits nothing for the failing batch, and the record
     * is delivered again after a restart. {@code EarlyAcknowledgeBreakProofTest} is the same stop
     * with the acknowledgement moved first.
     */
    @Test
    @DisplayName("FR-LED-4: a consumer stopped while the store fails commits no offset; restarted, it stores the record once")
    void stopDuringAnOutageCommitsNothing() {
        long eventId = IDS.incrementAndGet();
        FailingLedgerEntryStore failing = FailingLedgerEntryStore.of(store);
        MessageListenerContainer container = listeners.getListenerContainer(LedgerEventListener.LISTENER_ID);
        failing.failUntilReleased();
        RecordMetadata sent;
        try {
            sent = publish(CLEARING, eventId, event(CLEARING, IDS.incrementAndGet()));
            Awaitility.await().atMost(AWAIT).until(() -> failing.failures() >= 1);

            container.stop();

            assertThat(container.isRunning()).isFalse();
            assertThat(LedgerRelay.committedOffset(sent)).as("offset after the stop").isLessThanOrEqualTo(sent.offset());
            assertThat(row(eventId)).isEmpty();
        } finally {
            failing.release();
            if (!container.isRunning()) {
                container.start();
            }
        }

        awaitStored(eventId);
        awaitCommitted(sent);
        assertThat(rowsWithEventId(eventId)).isEqualTo(1);
    }

    /**
     * NFR-REL-3: the group's offsets are reset to the start of every partition, so everything the
     * topic holds - every test's events, valid and invalid - is delivered again.
     */
    @Test
    @DisplayName("NFR-REL-3: replaying the whole topic from offset 0 produces no new rows")
    void replayFromOffsetZeroAddsNothing() throws Exception {
        long before = publishSentinel(CLEARING);
        awaitStored(before);
        MessageListenerContainer container = listeners.getListenerContainer(LedgerEventListener.LISTENER_ID);
        int rowsBefore = allRows();

        container.stop();
        try (Admin admin = ReconKafka.admin()) {
            Map<TopicPartition, OffsetAndMetadata> toStart = partitions().stream()
                    .collect(Collectors.toMap(partition -> partition, partition -> new OffsetAndMetadata(0)));
            Awaitility.await().atMost(AWAIT).ignoreExceptions().until(() -> {
                admin.alterConsumerGroupOffsets(LedgerEventListener.CONSUMER_GROUP, toStart).all().get(10, TimeUnit.SECONDS);
                return true;
            });
            assertThat(committed(admin).values()).allMatch(offset -> offset == 0);
            container.start();

            Map<TopicPartition, Long> ends = admin.listOffsets(partitions().stream()
                            .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest())))
                    .all().get().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
            Awaitility.await().atMost(AWAIT).until(() -> committed(admin).equals(ends));
        }

        assertThat(allRows()).isEqualTo(rowsBefore);
    }

    private static void assertDeadLetter(ConsumerRecord<String, byte[]> deadLetter, RecordMetadata original,
                                         String code, String message) {
        assertThat(header(deadLetter, DeadLetterPublisher.ERROR_CODE)).isEqualTo(code);
        assertThat(header(deadLetter, DeadLetterPublisher.ERROR_MESSAGE)).isEqualTo(message);
        assertThat(header(deadLetter, DeadLetterPublisher.ORIGINAL_TOPIC)).isEqualTo(LedgerTopics.ACCOUNT_ACTIVITY);
        assertThat(header(deadLetter, DeadLetterPublisher.ORIGINAL_PARTITION)).isEqualTo(Integer.toString(original.partition()));
        assertThat(header(deadLetter, DeadLetterPublisher.ORIGINAL_OFFSET)).isEqualTo(Long.toString(original.offset()));
        assertThat(deadLetter.key()).isEqualTo(CLEARING.toString());
    }

    private static ConsumerRecord<String, byte[]> awaitDeadLetter(RecordMetadata original) {
        return ReconKafka.awaitRecords(deadLetterReader, record ->
                Integer.toString(original.partition()).equals(header(record, DeadLetterPublisher.ORIGINAL_PARTITION))
                        && Long.toString(original.offset()).equals(header(record, DeadLetterPublisher.ORIGINAL_OFFSET)),
                1).getFirst();
    }

    private void awaitStored(long eventId) {
        Awaitility.await().atMost(AWAIT).until(() -> row(eventId).isPresent());
    }

    private static void awaitCommitted(RecordMetadata record) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        try (Admin admin = ReconKafka.admin()) {
            Awaitility.await().atMost(AWAIT).until(() -> committed(admin).getOrDefault(partition, -1L) > record.offset());
        }
    }

    /** The group's committed offset on the record's partition, or -1 if it has none. */
    private static long committedOffset(RecordMetadata record) throws Exception {
        try (Admin admin = ReconKafka.admin()) {
            return committed(admin).getOrDefault(new TopicPartition(record.topic(), record.partition()), -1L);
        }
    }

    private static Map<TopicPartition, Long> committed(Admin admin) throws Exception {
        return admin.listConsumerGroupOffsets(LedgerEventListener.CONSUMER_GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).entrySet().stream()
                .filter(entry -> entry.getKey().topic().equals(LedgerTopics.ACCOUNT_ACTIVITY) && entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    }

    private static List<TopicPartition> partitions() {
        return IntStream.range(0, ReconKafka.LEDGER_TOPIC_PARTITIONS)
                .mapToObj(partition -> new TopicPartition(LedgerTopics.ACCOUNT_ACTIVITY, partition))
                .toList();
    }

    private Optional<Map<String, Object>> row(long eventId) {
        return jdbc.sql("""
                        SELECT event_id, ledger_entry_id, source_code, amount, currency, tx_type, created_at, value_date
                          FROM ledger_entries WHERE event_id = :eventId""")
                .param("eventId", eventId).query().listOfRows().stream().findFirst();
    }

    private int rowsWithEventId(long eventId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE event_id = :eventId")
                .param("eventId", eventId).query(Integer.class).single();
    }

    private int rowsWithEntryId(long entryId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE ledger_entry_id = :entryId")
                .param("entryId", entryId).query(Integer.class).single();
    }

    private int allRows() {
        return jdbc.sql("SELECT count(*) FROM ledger_entries").query(Integer.class).single();
    }

    /** A valid event on the clearing account, published after the records a test is about. */
    private static long publishSentinel(UUID key) {
        long eventId = IDS.incrementAndGet();
        publish(key, eventId, event(CLEARING, IDS.incrementAndGet()));
        return eventId;
    }

    private static RecordMetadata publish(UUID key, long eventId, byte[] value) {
        return send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, key.toString(), value,
                List.of(eventIdHeader(eventId))));
    }

    private static RecordMetadata send(ProducerRecord<String, byte[]> record) {
        try {
            return ledger.send(record).get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static Header eventIdHeader(long eventId) {
        return new RecordHeader("event-id",
                Long.toString(eventId).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] event(UUID account, long entryId) {
        return event(account, entryId, "2026-09-24T08:15:42.318204Z", "TRANSFER");
    }

    private static byte[] event(UUID account, long entryId, String createdAt, String txType) {
        return event(account, entryId, createdAt, txType, "TRY");
    }

    private static byte[] event(UUID account, long entryId, String currency) {
        return event(account, entryId, "2026-09-24T08:15:42.318204Z", "TRANSFER", currency);
    }

    private static byte[] event(UUID account, long entryId, String createdAt, String txType, String currency) {
        return """
                {"transaction_id":"%s","account_id":"%s","amount":125000,"currency":"%s","tx_type":"%s",
                 "entry_id":%d,"created_at":"%s"}"""
                .formatted(UUID.randomUUID(), account, currency, txType, entryId, createdAt)
                .getBytes(StandardCharsets.UTF_8);
    }
}
