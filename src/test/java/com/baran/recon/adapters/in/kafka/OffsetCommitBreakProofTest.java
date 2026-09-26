package com.baran.recon.adapters.in.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.backoff.FixedBackOff;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The break proof for FR-LED-4, as a permanent test. The offset of a record whose transaction did
 * not commit stays uncommitted for two reasons together: the listener acknowledges only after the
 * commit, and the error handler retries a failing batch without limit instead of giving up on it.
 *
 * <p>This context replaces the application's error handler with a bounded one - Spring Kafka's own
 * default kind, three attempts and then the batch is treated as handled - and runs the failure of
 * {@code LedgerEventConsumerTest.crashBeforeCommitIsRedelivered}. The offset is then committed
 * while nothing is stored: the record is lost. The replacement is made by a bean post-processor in
 * the test's own context; no file is edited.
 *
 * <p>The other half cannot be broken in a context: Spring Kafka refuses to start a consumer with
 * auto-commit on and a manual ack mode, and acknowledging before the commit would mean editing the
 * listener.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_ALPHA",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].ledger-accounts[0]=" + OffsetCommitBreakProofTest.CLEARING_ACCOUNT})
@ActiveProfiles("test")
@DirtiesContext
@Import({FailingLedgerEntryStore.Injection.class, OffsetCommitBreakProofTest.BoundedRetries.class})
@DisplayName("Break proof: FR-LED-4 holds because a failing batch is retried until it commits")
class OffsetCommitBreakProofTest {

    static final String CLEARING_ACCOUNT = "00000000-0000-4000-8000-0000000b7eaf";

    private static final Duration AWAIT = Duration.ofSeconds(60);
    private static final long EVENT_ID = 9_400_000_001L;
    private static final long ENTRY_ID = 9_400_000_002L;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.registerListening(registry);
    }

    @Autowired
    private LedgerEntryStore store;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("break proof: with bounded retries, the offset is committed while the store fails and nothing is stored")
    void withBoundedRetriesTheRecordIsLost() throws Exception {
        FailingLedgerEntryStore failing = FailingLedgerEntryStore.of(store);
        failing.failUntilReleased();
        try {
            RecordMetadata sent = publish();
            TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());

            Awaitility.await().atMost(AWAIT).until(() -> committedOffset(partition) > sent.offset());

            assertThat(failing.failures()).as("every attempt failed").isEqualTo(BoundedRetries.ATTEMPTS);
            assertThat(rowsWithEventId()).as("nothing stored behind the committed offset").isZero();
        } finally {
            failing.release();
        }
    }

    private int rowsWithEventId() {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE event_id = :eventId")
                .param("eventId", EVENT_ID).query(Integer.class).single();
    }

    private static long committedOffset(TopicPartition partition) throws Exception {
        try (Admin admin = ReconKafka.admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(LedgerEventListener.CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    private static RecordMetadata publish() throws Exception {
        byte[] value = """
                {"transaction_id":"%s","account_id":"%s","amount":125000,"currency":"TRY","tx_type":"TRANSFER",
                 "entry_id":%d,"created_at":"2026-09-24T08:15:42.318204Z"}"""
                .formatted(UUID.randomUUID(), CLEARING_ACCOUNT, ENTRY_ID).getBytes(StandardCharsets.UTF_8);
        try (KafkaProducer<String, byte[]> ledger = ReconKafka.ledgerProducer()) {
            return ledger.send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, CLEARING_ACCOUNT, value,
                    List.of(new RecordHeader("event-id", Long.toString(EVENT_ID).getBytes(StandardCharsets.UTF_8)))))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    /** Swaps the application's unlimited retry for a bounded one: the broken state this proof needs. */
    @TestConfiguration(proxyBeanMethods = false)
    static class BoundedRetries {

        static final int ATTEMPTS = 3;

        @Bean
        static BeanPostProcessor boundTheErrorHandler() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof CommonErrorHandler
                            ? new DefaultErrorHandler(new FixedBackOff(0, ATTEMPTS - 1))
                            : bean;
                }
            };
        }
    }
}
