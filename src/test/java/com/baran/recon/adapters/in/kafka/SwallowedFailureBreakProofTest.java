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
 * The break proof for FR-LED-4's first mechanism, as a permanent test: a projection failure
 * propagates out of the listener. Under {@code AckMode.MANUAL}, Spring Kafka commits a batch's
 * offsets when the listener returns normally, or after the error handler has handled what it threw
 * - which, with unlimited retries, means after a retry went through. A failure that never leaves the
 * listener gives the error handler nothing to retry.
 *
 * <p>Here the application's listener is not started. A test-local variant consumes in its place and
 * delegates each batch to the application's listener bean, but swallows what it throws. With the
 * store failing, the failure of {@code LedgerEventConsumerTest.crashBeforeCommitIsRedelivered} is
 * then never redelivered: the batch after it is acknowledged, its offset commits past the lost
 * record, and the record is stored nowhere. No application file is edited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_ALPHA",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].ledger-accounts[0]=" + SwallowedFailureBreakProofTest.CLEARING_ACCOUNT})
@ActiveProfiles("test")
@DirtiesContext
@Import({FailingLedgerEntryStore.Injection.class, SwallowedFailureBreakProofTest.SwallowingListener.class})
@DisplayName("Break proof: FR-LED-4 holds because a projection failure propagates out of the listener")
class SwallowedFailureBreakProofTest {

    static final String CLEARING_ACCOUNT = "00000000-0000-4000-8000-00000005a110";

    private static final Duration AWAIT = Duration.ofSeconds(60);
    private static final long LOST_EVENT = 9_400_000_101L;
    private static final long SENTINEL_EVENT = 9_400_000_103L;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.register(registry);
    }

    @Autowired
    private LedgerEntryStore store;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("break proof: with the failure swallowed, the record is never redelivered and the offset commits past it")
    void swallowedFailureLosesTheRecord() {
        FailingLedgerEntryStore failing = FailingLedgerEntryStore.of(store);
        failing.failUntilReleased();
        RecordMetadata lost;
        try {
            lost = LedgerRelay.publish(CLEARING_ACCOUNT, LOST_EVENT, 9_400_000_102L);
            Awaitility.await().atMost(AWAIT).until(() -> failing.failures() >= 1);
        } finally {
            failing.release();
        }

        LedgerRelay.publish(CLEARING_ACCOUNT, SENTINEL_EVENT, 9_400_000_104L);
        Awaitility.await().atMost(AWAIT).until(() -> LedgerRelay.rowsWithEventId(jdbc, SENTINEL_EVENT) == 1);
        Awaitility.await().atMost(AWAIT).until(() -> LedgerRelay.committedOffset(lost) > lost.offset());

        assertThat(failing.failures()).as("tried once, never again").isEqualTo(1);
        assertThat(LedgerRelay.rowsWithEventId(jdbc, LOST_EVENT)).as("the record behind the committed offset").isZero();
    }

    /** The broken state: the application's listener, with its failures caught and dropped. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SwallowingListener {

        @Bean
        Swallowing swallowing(LedgerEventListener application) {
            return new Swallowing(application);
        }
    }

    static final class Swallowing {

        private final LedgerEventListener application;

        Swallowing(LedgerEventListener application) {
            this.application = application;
        }

        @KafkaListener(id = "swallowing-variant", groupId = LedgerEventListener.CONSUMER_GROUP,
                topics = LedgerTopics.ACCOUNT_ACTIVITY, batch = "true", autoStartup = "true")
        void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment acknowledgment) {
            try {
                application.onBatch(records, acknowledgment);
            } catch (RuntimeException swallowed) {
                // The broken state under test: the batch ends normally, unacknowledged.
            }
        }
    }
}
