package com.baran.recon.archfixture.rawproducer.config;

import java.util.Map;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/** A raw client producer built outside the Kafka adapter, bypassing Spring's factory entirely. */
public class LedgerWriter {

    public void write(String bootstrapServers, String payload) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", bootstrapServers), new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("ledger.account-activity", payload));
        }
    }
}
