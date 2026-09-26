package com.baran.recon.adapters.in.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * The dead-letter topic did not acknowledge a record in time. The fault is in the infrastructure,
 * not in the record, so the record's offset must not be committed: the consumer retries the batch.
 */
final class DeadLetterNotWrittenException extends RuntimeException {

    DeadLetterNotWrittenException(ConsumerRecord<?, ?> original, Throwable cause) {
        super("could not dead-letter " + original.topic() + "-" + original.partition() + "@" + original.offset(), cause);
    }
}
