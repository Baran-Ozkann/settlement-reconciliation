package com.baran.recon.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A broker for the application contexts under test, shared per test JVM like {@link ReconPostgres}
 * and started on first use by {@link #register}. It also plays the ledger's part: it creates the
 * ledger's topic the way the ledger does, and gives tests a producer to publish on it and a
 * consumer to read what this service wrote.
 */
public final class ReconKafka {

    /** As the ledger creates it (docs/ledger-integration-notes.md 5.1). */
    public static final int LEDGER_TOPIC_PARTITIONS = 3;

    private static final KafkaContainer SHARED = LoopbackContainers.kafka();
    private static final Duration AWAIT = Duration.ofSeconds(60);

    private ReconKafka() {
    }

    /**
     * For {@code @DynamicPropertySource}: the shared broker, with topic creation on and the listener
     * still off. A context that consumes uses {@link #registerListening} instead.
     */
    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.kafka.bootstrap-servers", SHARED::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
    }

    /**
     * As {@link #register}, with the ledger listener started. Every listening context joins the same
     * consumer group and would take a share of the ledger topic's partitions, so a class that uses
     * this must close its context when it ends ({@code @DirtiesContext}), and only one such context
     * may be open at a time.
     */
    public static void registerListening(DynamicPropertyRegistry registry) {
        register(registry);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    public static String bootstrapServers() {
        start();
        return SHARED.getBootstrapServers();
    }

    public static void createTopic(String topic, int partitions) {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        } catch (ExecutionException exists) {
            if (!(exists.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException(exists);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    public static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    }

    /** A producer standing in for the ledger's relay. */
    public static KafkaProducer<String, byte[]> ledgerProducer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"),
                new StringSerializer(), new ByteArraySerializer());
    }

    /** A consumer of its own group, reading the whole topic from the start. */
    public static KafkaConsumer<String, byte[]> reader(String topic) {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /**
     * Polls until {@code count} records matching {@code wanted} have arrived, and returns them. Fails
     * after a bounded wait rather than sleeping for a fixed time.
     */
    public static List<ConsumerRecord<String, byte[]>> awaitRecords(
            KafkaConsumer<String, byte[]> reader, Predicate<ConsumerRecord<String, byte[]>> wanted, int count) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        Awaitility.await().atMost(AWAIT).pollInterval(Duration.ZERO).until(() -> {
            reader.poll(Duration.ofMillis(200)).forEach(record -> {
                if (wanted.test(record)) {
                    found.add(record);
                }
            });
            return found.size() >= count;
        });
        return found;
    }

    /** The value of a header written as UTF-8 text, or null when absent. */
    public static String header(ConsumerRecord<?, ?> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static synchronized void start() {
        if (!SHARED.isRunning()) {
            SHARED.start();
        }
    }
}
