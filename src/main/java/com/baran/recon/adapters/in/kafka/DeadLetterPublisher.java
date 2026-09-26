package com.baran.recon.adapters.in.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes a ledger record that cannot be projected to the dead-letter topic (FR-LED-5), so the
 * partition moves on while the record stays available to whoever fixes its cause.
 *
 * <p>The dead letter keeps the original key, value and headers unchanged, and adds why and where
 * from. The send is awaited: the consumer commits the original's offset only after this returns, so
 * a record is never both skipped and lost. A failure here is not a fault of the record, and is
 * thrown for the consumer to retry.
 */
@Component
class DeadLetterPublisher {

    static final String ERROR_CODE = "x-error-code";
    static final String ERROR_MESSAGE = "x-error-message";
    static final String ORIGINAL_TOPIC = "x-original-topic";
    static final String ORIGINAL_OFFSET = "x-original-offset";

    /** Not in FR-LED-5's list: an offset alone names no record on a topic with several partitions. */
    static final String ORIGINAL_PARTITION = "x-original-partition";

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, byte[]> template;

    DeadLetterPublisher(KafkaTemplate<String, byte[]> template) {
        this.template = template;
    }

    void publish(ConsumerRecord<String, byte[]> original, DeadLetterReason reason, String message) {
        ProducerRecord<String, byte[]> deadLetter =
                new ProducerRecord<>(LedgerTopics.DEAD_LETTER, original.key(), original.value());
        for (Header header : original.headers()) {
            deadLetter.headers().add(header.key(), header.value());
        }
        deadLetter.headers()
                .add(ERROR_CODE, utf8(reason.name()))
                .add(ERROR_MESSAGE, utf8(message))
                .add(ORIGINAL_TOPIC, utf8(original.topic()))
                .add(ORIGINAL_PARTITION, utf8(Integer.toString(original.partition())))
                .add(ORIGINAL_OFFSET, utf8(Long.toString(original.offset())));
        try {
            template.send(deadLetter).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeadLetterNotWrittenException(original, interrupted);
        } catch (ExecutionException | TimeoutException | KafkaException failed) {
            // KafkaException: the send can fail before it returns a future, e.g. when no broker
            // answers the metadata request within max.block.ms.
            throw new DeadLetterNotWrittenException(original, failed);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
