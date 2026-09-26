package com.baran.recon.adapters.in.kafka;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("INV-9: a guarded producer sends to the dead-letter topic and nowhere else")
class DeadLetterOnlyProducerTest {

    private final MockProducer<String, byte[]> sent =
            new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    private final DeadLetterOnlyProducer<String, byte[]> producer = new DeadLetterOnlyProducer<>(sent);

    @Test
    @DisplayName("a send to the dead-letter topic goes through, with and without a callback")
    void deadLetterGoesThrough() {
        producer.send(new ProducerRecord<>(LedgerTopics.DEAD_LETTER, "k", new byte[] {1}));
        producer.send(new ProducerRecord<>(LedgerTopics.DEAD_LETTER, "k", new byte[] {2}), (metadata, failure) -> { });

        assertThat(sent.history()).hasSize(2).allMatch(record -> record.topic().equals(LedgerTopics.DEAD_LETTER));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ledger.account-activity", "ledger.some-future-topic", "recon.other",
            "recon.ledger-account-activity.dlq2"})
    @DisplayName("INV-9: a send to any other topic is refused before the producer sees it")
    void everyOtherTopicIsRefused(String topic) {
        assertThatThrownBy(() -> producer.send(new ProducerRecord<>(topic, "k", new byte[] {1})))
                .isInstanceOf(TopicWriteRefusedException.class)
                .hasMessageContaining(topic);
        assertThatThrownBy(() -> producer.send(new ProducerRecord<>(topic, "k", new byte[] {1}), (metadata, failure) -> { }))
                .isInstanceOf(TopicWriteRefusedException.class);

        assertThat(sent.history()).isEmpty();
    }

    @Test
    @DisplayName("everything but send is passed through unchanged")
    void otherCallsAreDelegated() {
        producer.flush();
        producer.close();

        assertThat(sent.flushed()).isTrue();
        assertThat(sent.closed()).isTrue();
    }
}
