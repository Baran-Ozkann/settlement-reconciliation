package com.baran.recon.adapters.in.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The Kafka wiring of the ledger consumer. It lives in the adapter, not in {@code config}, so every
 * use of Kafka's producer API stays inside this one package (INV-9).
 */
@Configuration(proxyBeanMethods = false)
class LedgerKafkaConfiguration {

    /**
     * Created by the admin client at startup if it is missing. Only this topic: the ledger's own
     * topic belongs to the ledger, which creates it, and this service never creates or alters it.
     */
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(LedgerTopics.DEAD_LETTER).partitions(1).replicas(1).build();
    }
}
