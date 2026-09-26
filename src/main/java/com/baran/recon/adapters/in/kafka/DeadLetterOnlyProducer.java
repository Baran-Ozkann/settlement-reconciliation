package com.baran.recon.adapters.in.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.metrics.KafkaMetric;

/**
 * INV-9, the run-time half: a producer that sends to the dead-letter topic and refuses every other
 * topic before anything leaves this process. Every producer the application's factory creates is
 * wrapped in one ({@link LedgerKafkaConfiguration}), so a send to a ledger topic fails whichever
 * code path issues it. ArchUnit keeps producers inside the Kafka adapter; this covers what a type
 * check cannot see, which is the topic a send names.
 *
 * <p>An allow-list of one rather than a deny-list of the ledger's topics: the ledger may add a
 * topic, and a list of what is forbidden would not know about it.
 */
final class DeadLetterOnlyProducer<K, V> implements Producer<K, V> {

    private final Producer<K, V> delegate;

    DeadLetterOnlyProducer(Producer<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
        refuseUnlessDeadLetter(record);
        return delegate.send(record);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
        refuseUnlessDeadLetter(record);
        return delegate.send(record, callback);
    }

    private static void refuseUnlessDeadLetter(ProducerRecord<?, ?> record) {
        if (!LedgerTopics.DEAD_LETTER.equals(record.topic())) {
            throw new TopicWriteRefusedException(record.topic());
        }
    }

    @Override
    public void initTransactions() {
        delegate.initTransactions();
    }

    @Override
    public void beginTransaction() {
        delegate.beginTransaction();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
                                         ConsumerGroupMetadata groupMetadata) {
        delegate.sendOffsetsToTransaction(offsets, groupMetadata);
    }

    @Override
    public void commitTransaction() {
        delegate.commitTransaction();
    }

    @Override
    public void abortTransaction() {
        delegate.abortTransaction();
    }

    @Override
    public void registerMetricForSubscription(KafkaMetric metric) {
        delegate.registerMetricForSubscription(metric);
    }

    @Override
    public void unregisterMetricFromSubscription(KafkaMetric metric) {
        delegate.unregisterMetricFromSubscription(metric);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        return delegate.partitionsFor(topic);
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return delegate.metrics();
    }

    @Override
    public Uuid clientInstanceId(Duration timeout) {
        return delegate.clientInstanceId(timeout);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Duration timeout) {
        delegate.close(timeout);
    }
}
