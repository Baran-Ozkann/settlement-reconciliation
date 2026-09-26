package com.baran.recon.adapters.in.kafka;

import java.time.Duration;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The break proof for FR-LED-4's second mechanism, as a permanent test: the listener acknowledges
 * only after the projection has committed. Under {@code AckMode.MANUAL} an acknowledgement records
 * the batch's offsets for the next commit, and nothing on the failure path discards them. While the
 * error handler retries they wait; but a container that stops - a shutdown during a database
 * outage - commits every acknowledged offset on its way out (Spring Kafka 4.1.1,
 * {@code ListenerConsumer.wrapUp} → {@code commitPendingAcks}).
 *
 * <p>Here the application's listener is not started. A test-local variant acknowledges each batch
 * first and then delegates it to the application's listener bean. With the store failing, stopping
 * the container commits past a record that is stored nowhere. The same stop with the application's
 * own listener commits nothing: {@code LedgerEventConsumerTest.stopDuringAnOutageCommitsNothing}. No
 * application file is edited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_ALPHA",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].ledger-accounts[0]=" + EarlyAcknowledgeBreakProofTest.CLEARING_ACCOUNT})
@ActiveProfiles("test")
@DirtiesContext
@Import({FailingLedgerEntryStore.Injection.class, EarlyAcknowledgeBreakProofTest.EarlyAcknowledgingListener.class})
@DisplayName("Break proof: FR-LED-4 holds because the listener acknowledges only after the commit")
class EarlyAcknowledgeBreakProofTest {

    static final String CLEARING_ACCOUNT = "00000000-0000-4000-8000-0000000ea41c";

    private static final Duration AWAIT = Duration.ofSeconds(60);
    private static final long LOST_EVENT = 9_400_000_201L;
    private static final String VARIANT = "early-acknowledging-variant";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.register(registry);
    }

    @Autowired
    private LedgerEntryStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    @DisplayName("break proof: acknowledged first, a stop during an outage commits past a record stored nowhere")
    void earlyAcknowledgementIsCommittedOnStop() {
        FailingLedgerEntryStore failing = FailingLedgerEntryStore.of(store);
        MessageListenerContainer container = listeners.getListenerContainer(VARIANT);
        failing.failUntilReleased();
        try {
            RecordMetadata lost = LedgerRelay.publish(CLEARING_ACCOUNT, LOST_EVENT, 9_400_000_202L);
            Awaitility.await().atMost(AWAIT).until(() -> failing.failures() >= 1);

            container.stop();

            assertThat(LedgerRelay.committedOffset(lost)).as("offset after the stop").isGreaterThan(lost.offset());
            assertThat(LedgerRelay.rowsWithEventId(jdbc, LOST_EVENT)).as("the record behind it").isZero();
        } finally {
            failing.release();
        }
    }

    /** The broken state: the application's listener, acknowledged before it runs. */
    @TestConfiguration(proxyBeanMethods = false)
    static class EarlyAcknowledgingListener {

        @Bean
        EarlyAcknowledging earlyAcknowledging(LedgerEventListener application) {
            return new EarlyAcknowledging(application);
        }
    }

    static final class EarlyAcknowledging {

        private final LedgerEventListener application;

        EarlyAcknowledging(LedgerEventListener application) {
            this.application = application;
        }

        @KafkaListener(id = VARIANT, groupId = LedgerEventListener.CONSUMER_GROUP,
                topics = LedgerTopics.ACCOUNT_ACTIVITY, batch = "true", autoStartup = "true")
        void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment acknowledgment) {
            acknowledgment.acknowledge();
            application.onBatch(records, () -> { });
        }
    }
}
