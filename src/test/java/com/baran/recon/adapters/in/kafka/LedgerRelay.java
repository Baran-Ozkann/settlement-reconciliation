package com.baran.recon.adapters.in.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.recon.support.ReconKafka;

/**
 * What the break-proof classes share: publishing a valid event as the ledger's relay would, and
 * reading back the consumer group's committed offset and the projection's rows.
 */
final class LedgerRelay {

    private LedgerRelay() {
    }

    /** A valid seven-field event on the given account, with its event-id header. */
    static RecordMetadata publish(String account, long eventId, long entryId) {
        byte[] value = """
                {"transaction_id":"%s","account_id":"%s","amount":125000,"currency":"TRY","tx_type":"TRANSFER",
                 "entry_id":%d,"created_at":"2026-09-24T08:15:42.318204Z"}"""
                .formatted(UUID.randomUUID(), account, entryId).getBytes(StandardCharsets.UTF_8);
        try (KafkaProducer<String, byte[]> ledger = ReconKafka.ledgerProducer()) {
            return ledger.send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, account, value,
                    List.of(new RecordHeader("event-id", Long.toString(eventId).getBytes(StandardCharsets.UTF_8)))))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** The application's consumer group's committed offset on the record's partition, or -1. */
    static long committedOffset(RecordMetadata record) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        try (Admin admin = ReconKafka.admin()) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(LedgerEventListener.CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(partition);
            return committed == null ? -1 : committed.offset();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    static int rowsWithEventId(JdbcClient jdbc, long eventId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE event_id = :eventId")
                .param("eventId", eventId).query(Integer.class).single();
    }
}
