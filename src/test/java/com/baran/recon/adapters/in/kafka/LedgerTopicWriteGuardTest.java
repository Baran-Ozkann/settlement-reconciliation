package com.baran.recon.adapters.in.kafka;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import com.baran.recon.support.LoopbackContainers;
import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INV-9 at run time, through the application's own producer factory, and its break proof: the same
 * producer configuration without the guard writes to the ledger's topic, so the guard is what stops
 * it. The proof writes to a broker of its own, so the record it leaves cannot reach a consumer of
 * the shared test broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("INV-9: no producer of this service can write to a ledger topic")
class LedgerTopicWriteGuardTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.register(registry);
    }

    @Autowired
    private ProducerFactory<String, byte[]> producerFactory;

    @Autowired
    private KafkaTemplate<String, byte[]> template;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Test
    @DisplayName("INV-9: the application's KafkaTemplate refuses a send to ledger.account-activity, and nothing lands")
    void templateRefusesTheLedgerTopic() {
        long before = recordsOnLedgerTopic(ReconKafka.bootstrapServers());

        assertThatThrownBy(() -> template.send(LedgerTopics.ACCOUNT_ACTIVITY, "key", new byte[] {1}))
                .isInstanceOf(TopicWriteRefusedException.class);

        assertThat(recordsOnLedgerTopic(ReconKafka.bootstrapServers())).isEqualTo(before);
    }

    @Test
    @DisplayName("INV-9: a producer taken from the factory directly refuses it too")
    void factoryProducerRefusesTheLedgerTopic() {
        try (Producer<String, byte[]> producer = producerFactory.createProducer()) {
            assertThatThrownBy(() -> producer.send(
                    new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, "key", new byte[] {1})))
                    .isInstanceOf(TopicWriteRefusedException.class);
        }
    }

    @Test
    @DisplayName("the guard is registered on the application's producer factory")
    void guardIsRegistered() {
        assertThat(producerFactory).isInstanceOf(DefaultKafkaProducerFactory.class);
        assertThat(((DefaultKafkaProducerFactory<String, byte[]>) producerFactory).getPostProcessors()).hasSize(1);
    }

    /**
     * The break proof. The application's producer properties, pointed at a throwaway broker, in a
     * factory without the post-processor: the send the guard refuses above lands on the ledger's
     * topic. Nothing is edited, and nothing the proof writes is readable by any other test.
     */
    @Test
    @DisplayName("break proof: without the guard, the same configuration writes to ledger.account-activity")
    void withoutTheGuardTheSendLands() throws Exception {
        try (KafkaContainer throwaway = LoopbackContainers.kafka()) {
            throwaway.start();
            Map<String, Object> properties = kafkaProperties.buildProducerProperties();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, throwaway.getBootstrapServers());
            DefaultKafkaProducerFactory<String, byte[]> unguarded = new DefaultKafkaProducerFactory<>(properties);
            try (Producer<String, byte[]> producer = unguarded.createProducer()) {
                producer.send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, UUID.randomUUID().toString(),
                        new byte[] {1})).get(30, TimeUnit.SECONDS);
            } finally {
                unguarded.destroy();
            }

            assertThat(recordsOnLedgerTopic(throwaway.getBootstrapServers())).isEqualTo(1);
        }
    }

    /** The number of records on the ledger topic, summed over its partitions. */
    private static long recordsOnLedgerTopic(String bootstrapServers) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                Map.of("bootstrap.servers", bootstrapServers), new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(LedgerTopics.ACCOUNT_ACTIVITY).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            return consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
        }
    }
}
