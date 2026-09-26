package com.baran.recon.adapters.in.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.support.ReconKafka.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("FR-LED-5: a dead letter carries the original record and why it could not be projected")
class DeadLetterPublisherTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.register(registry);
    }

    @Autowired
    private DeadLetterPublisher publisher;

    @Test
    @DisplayName("FR-LED-5: key, value and headers are kept; error code, message, topic, partition and offset are added")
    void deadLetterCarriesOriginalAndCause() {
        String key = UUID.randomUUID().toString();
        byte[] value = {'{', (byte) 0xFF, '}'};
        RecordHeaders headers = new RecordHeaders();
        headers.add("event-id", "8101".getBytes(StandardCharsets.UTF_8));
        headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, byte[]> original = new ConsumerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, 2, 41L,
                0L, TimestampType.CREATE_TIME, 0, value.length, key, value, headers, Optional.empty());

        publisher.publish(original, DeadLetterReason.SCHEMA_INVALID, "the value is not valid UTF-8");

        try (KafkaConsumer<String, byte[]> reader = ReconKafka.reader(LedgerTopics.DEAD_LETTER)) {
            ConsumerRecord<String, byte[]> deadLetter = ReconKafka.awaitRecords(reader,
                    record -> key.equals(record.key()), 1).getFirst();

            assertThat(deadLetter.value()).isEqualTo(value);
            assertThat(header(deadLetter, "event-id")).isEqualTo("8101");
            assertThat(header(deadLetter, "traceparent")).startsWith("00-0af7651916cd43dd");
            assertThat(header(deadLetter, DeadLetterPublisher.ERROR_CODE)).isEqualTo("SCHEMA_INVALID");
            assertThat(header(deadLetter, DeadLetterPublisher.ERROR_MESSAGE)).isEqualTo("the value is not valid UTF-8");
            assertThat(header(deadLetter, DeadLetterPublisher.ORIGINAL_TOPIC)).isEqualTo("ledger.account-activity");
            assertThat(header(deadLetter, DeadLetterPublisher.ORIGINAL_PARTITION)).isEqualTo("2");
            assertThat(header(deadLetter, DeadLetterPublisher.ORIGINAL_OFFSET)).isEqualTo("41");
        }
    }

    @Test
    @DisplayName("a dead letter the broker does not acknowledge is an exception, never a silent loss")
    void unacknowledgedDeadLetterThrows() {
        // Port 9 on loopback: nothing listens there, so the producer cannot reach any broker.
        DefaultKafkaProducerFactory<String, byte[]> unreachable = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500),
                new StringSerializer(), new ByteArraySerializer());
        DeadLetterPublisher failing = new DeadLetterPublisher(new KafkaTemplate<>(unreachable));
        ConsumerRecord<String, byte[]> original = new ConsumerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, 0, 7L, "k", new byte[0]);

        try {
            assertThatThrownBy(() -> failing.publish(original, DeadLetterReason.INVALID_EVENT_ID, "absent"))
                    .isInstanceOf(DeadLetterNotWrittenException.class)
                    .hasMessage("could not dead-letter ledger.account-activity-0@7");
        } finally {
            unreachable.destroy();
        }
    }

    @Test
    @DisplayName("INV-9: the dead-letter topic is outside the ledger's namespace")
    void deadLetterTopicIsNotALedgerTopic() {
        assertThat(LedgerTopics.DEAD_LETTER).doesNotStartWith("ledger.");
        assertThat(LedgerTopics.DEAD_LETTER).isNotEqualTo(LedgerTopics.ACCOUNT_ACTIVITY);
    }
}
