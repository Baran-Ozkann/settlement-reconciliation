package com.baran.recon.adapters.in.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.StringJoiner;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

/**
 * Keeps an infrastructure failure the consumer is retrying visible for as long as it lasts: every
 * failed attempt is logged at ERROR with the backoff state, not only the first, because the retries
 * are unlimited and a quiet log would look like a quiet topic.
 *
 * <p>The failure is named by its exception classes, never its messages: a PostgreSQL error detail
 * can quote a whole row, account id included, and the log must not carry that (CLAUDE.md 3.2).
 */
final class RetryLogger implements RetryListener {

    private static final Logger LOG = LoggerFactory.getLogger(RetryLogger.class);

    private final ConsumerBackOff backOff;
    private final Clock clock;
    private Instant firstFailure;

    RetryLogger(ConsumerBackOff backOff, Clock clock) {
        this.backOff = backOff;
        this.clock = clock;
    }

    /** The interface's one abstract method. The batch listener reports through the overload below. */
    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception failure, int deliveryAttempt) {
        LOG.error("Ledger record at {}-{}@{} failed on attempt {}, caused by {}; retrying in {}",
                record.topic(), record.partition(), record.offset(), deliveryAttempt, causes(failure),
                backOff.delayAfter(deliveryAttempt));
    }

    @Override
    public synchronized void failedDelivery(ConsumerRecords<?, ?> records, Exception failure, int deliveryAttempt) {
        Instant now = clock.instant();
        // Attempt 1 is the first failure of a new episode; the retries are unlimited, so no episode
        // ends in recovery, only in a delivery that goes through.
        if (deliveryAttempt <= 1 || firstFailure == null) {
            firstFailure = now;
        }
        LOG.error("Ledger batch of {} records failed on attempt {}, caused by {}; retrying in {}, failing for {} so far",
                records.count(), deliveryAttempt, causes(failure), backOff.delayAfter(deliveryAttempt),
                Duration.between(firstFailure, now));
    }

    /** The chain of exception classes, outermost first. */
    static String causes(Throwable failure) {
        StringJoiner chain = new StringJoiner(" <- ");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getName());
            if (cause.getCause() == cause) {
                break;
            }
        }
        return chain.toString();
    }
}
