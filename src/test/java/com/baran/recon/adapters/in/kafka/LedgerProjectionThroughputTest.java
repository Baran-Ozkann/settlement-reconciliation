package com.baran.recon.adapters.in.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-PERF-3: projection throughput, measured rather than estimated. Runs only under -Pperf.
 *
 * <p>The events are published first, with the listener stopped; the clock starts when the listener
 * starts and stops when the last row is in the database. What is measured is therefore the sustained
 * rate at which a backlog is drained - poll, parse, validate against the schema, insert, commit,
 * acknowledge - and not the producer's rate. Every event is on a mapped account, so every one is an
 * insert: the costliest case, since an event on an unmapped account is skipped without touching the
 * database.
 *
 * <p>The ledger keys by account, so a source's events arrive on as many partitions as it has
 * accounts. Twelve mapped accounts spread the backlog over the topic's three partitions, read by the
 * one consumer the application runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_ALPHA",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].ledger-accounts=" + LedgerProjectionThroughputTest.ACCOUNTS})
@ActiveProfiles("test")
@DirtiesContext
@Tag("perf")
@DisplayName("NFR-PERF-3: ledger projection throughput")
class LedgerProjectionThroughputTest {

    static final String ACCOUNTS = "00000000-0000-4000-8000-00000000f001,00000000-0000-4000-8000-00000000f002,"
            + "00000000-0000-4000-8000-00000000f003,00000000-0000-4000-8000-00000000f004,"
            + "00000000-0000-4000-8000-00000000f005,00000000-0000-4000-8000-00000000f006,"
            + "00000000-0000-4000-8000-00000000f007,00000000-0000-4000-8000-00000000f008,"
            + "00000000-0000-4000-8000-00000000f009,00000000-0000-4000-8000-00000000f00a,"
            + "00000000-0000-4000-8000-00000000f00b,00000000-0000-4000-8000-00000000f00c";

    /** NFR-PERF-3. A miss fails this test; the number is printed first either way. */
    private static final int TARGET_EVENTS_PER_SECOND = 5_000;

    private static final int EVENTS = 200_000;
    private static final long FIRST_ID = 9_500_000_000L;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.register(registry);
    }

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("NFR-PERF-3: a backlog of 200,000 mapped events is projected at 5,000 events/s or more")
    void drainsABacklogAtTheTargetRate() throws Exception {
        ReconKafka.createTopic(LedgerTopics.ACCOUNT_ACTIVITY, ReconKafka.LEDGER_TOPIC_PARTITIONS);
        MessageListenerContainer container = listeners.getListenerContainer(LedgerEventListener.LISTENER_ID);
        assertThat(container.isRunning()).as("stopped until the backlog is published").isFalse();
        int rowsBefore = rows();

        long publishStart = System.nanoTime();
        publishBacklog();
        Duration publishing = Duration.ofNanos(System.nanoTime() - publishStart);

        long drainStart = System.nanoTime();
        container.start();
        Awaitility.await().atMost(Duration.ofMinutes(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rows() - rowsBefore >= EVENTS);
        Duration draining = Duration.ofNanos(System.nanoTime() - drainStart);

        double perSecond = EVENTS / (draining.toNanos() / 1e9);
        System.out.printf(Locale.ROOT, "NFR-PERF-3 RESULT: %d events projected in %.3f s = %.0f events/s "
                        + "(target %d); backlog published in %.3f s; max.poll.records %s%n",
                EVENTS, draining.toNanos() / 1e9, perSecond, TARGET_EVENTS_PER_SECOND,
                publishing.toNanos() / 1e9, container.getContainerProperties().getKafkaConsumerProperties()
                        .getProperty("max.poll.records", "default (500)"));

        assertThat(rows() - rowsBefore).isEqualTo(EVENTS);
        assertThat(perSecond).as("events per second").isGreaterThanOrEqualTo(TARGET_EVENTS_PER_SECOND);
    }

    private int rows() {
        return jdbc.sql("SELECT count(*) FROM ledger_entries").query(Integer.class).single();
    }

    private static void publishBacklog() throws Exception {
        String[] accounts = ACCOUNTS.split(",");
        try (KafkaProducer<String, byte[]> ledger = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ReconKafka.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.LINGER_MS_CONFIG, 20,
                ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024),
                new StringSerializer(), new ByteArraySerializer())) {
            List<Future<RecordMetadata>> sent = new ArrayList<>(EVENTS);
            for (int i = 0; i < EVENTS; i++) {
                String account = accounts[i % accounts.length];
                long eventId = FIRST_ID + i;
                byte[] value = """
                        {"transaction_id":"%s","account_id":"%s","amount":%d,"currency":"TRY","tx_type":"TRANSFER",\
                        "entry_id":%d,"created_at":"2026-09-24T08:15:42.318204Z"}"""
                        .formatted(UUID.randomUUID(), account, 100 + i, eventId).getBytes(StandardCharsets.UTF_8);
                sent.add(ledger.send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, account, value,
                        List.of(new RecordHeader("event-id", Long.toString(eventId).getBytes(StandardCharsets.UTF_8))))));
            }
            for (Future<RecordMetadata> each : sent) {
                each.get(60, TimeUnit.SECONDS);
            }
        }
    }
}
