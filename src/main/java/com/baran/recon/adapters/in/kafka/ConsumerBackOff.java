package com.baran.recon.adapters.in.kafka;

import java.time.Duration;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * How long the consumer waits before retrying a batch that failed for a reason other than its
 * records: doubling from half a second to thirty seconds, then every thirty seconds, with no limit.
 * The failure is in the database or the broker, and giving up would mean dead-lettering records
 * that are fine, or skipping them.
 */
final class ConsumerBackOff {

    private static final long INITIAL_MILLIS = 500;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_MILLIS = 30_000;

    BackOff policy() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_MILLIS, MULTIPLIER);
        backOff.setMaxInterval(MAX_MILLIS);
        return backOff;
    }

    /** The wait the policy applies after the given failed attempt, for the log. */
    Duration delayAfter(int failedAttempt) {
        long delay = INITIAL_MILLIS;
        for (int i = 1; i < failedAttempt && delay < MAX_MILLIS; i++) {
            delay = (long) (delay * MULTIPLIER);
        }
        return Duration.ofMillis(Math.min(delay, MAX_MILLIS));
    }
}
