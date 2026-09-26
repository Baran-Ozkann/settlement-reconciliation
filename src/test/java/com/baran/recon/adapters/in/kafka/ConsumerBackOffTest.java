package com.baran.recon.adapters.in.kafka;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The consumer's retry backoff, and the delay its log reports")
class ConsumerBackOffTest {

    private final ConsumerBackOff backOff = new ConsumerBackOff();

    @Test
    @DisplayName("the logged delay after each failed attempt is the one the policy waits")
    void loggedDelayMatchesThePolicy() {
        BackOffExecution execution = backOff.policy().start();

        for (int attempt = 1; attempt <= 12; attempt++) {
            assertThat(backOff.delayAfter(attempt)).as("after attempt %d", attempt)
                    .isEqualTo(Duration.ofMillis(execution.nextBackOff()));
        }
    }

    @Test
    @DisplayName("doubling from half a second, capped at thirty seconds, and never giving up")
    void doublesToACapAndNeverStops() {
        BackOffExecution execution = backOff.policy().start();
        for (int attempt = 1; attempt < 10_000; attempt++) {
            execution.nextBackOff();
        }

        assertThat(backOff.delayAfter(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(backOff.delayAfter(2)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backOff.delayAfter(8)).isEqualTo(Duration.ofSeconds(30));
        assertThat(execution.nextBackOff()).as("after 10,000 failures").isEqualTo(30_000);
    }
}
