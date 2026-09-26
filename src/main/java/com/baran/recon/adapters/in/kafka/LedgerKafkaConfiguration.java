package com.baran.recon.adapters.in.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

/**
 * The Kafka wiring of the ledger consumer. It lives in the adapter, not in {@code config}, so every
 * use of Kafka's producer API stays inside this one package (INV-9).
 */
@Configuration(proxyBeanMethods = false)
class LedgerKafkaConfiguration {

    /**
     * INV-9: every producer the application's factory creates can write the dead-letter topic and
     * nothing else. Registered on the factory Boot builds, so it holds for the KafkaTemplate and for
     * any producer taken from the factory directly.
     */
    @Bean
    DefaultKafkaProducerFactoryCustomizer deadLetterOnlyProducers() {
        return LedgerKafkaConfiguration::guard;
    }

    /**
     * Created by the admin client at startup if it is missing. Only this topic: the ledger's own
     * topic belongs to the ledger, which creates it, and this service never creates or alters it.
     */
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(LedgerTopics.DEAD_LETTER).partitions(1).replicas(1).build();
    }

    private static <K, V> void guard(DefaultKafkaProducerFactory<K, V> factory) {
        factory.addPostProcessor(DeadLetterOnlyProducer::new);
    }
}
