package com.baran.recon.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.baran.recon.config.MatchingConfiguration.AutomaticTriggerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settings alone, bound as MatchingConfiguration binds them: its beans need the whole application,
 * and its startup recovery is built even in a lazy context.
 */
@DisplayName("FR-MAT-1: recon.matching.automatic-trigger binds the background run's switch, queue, retry interval and "
        + "give-up bound")
class AutomaticTriggerConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SettingsOnly.class)
            .withPropertyValues("recon.matching.automatic-trigger.enabled=true",
                    "recon.matching.automatic-trigger.queue-capacity=100",
                    "recon.matching.automatic-trigger.busy-retry-interval=5s",
                    "recon.matching.automatic-trigger.busy-give-up-after=30m");

    @Test
    @DisplayName("the application's values bind: on, 100 files queued at most, 5 seconds between tries, given up after "
            + "30 minutes busy")
    void settingsBind() {
        context.run(started -> assertThat(started.getBean(AutomaticTriggerProperties.class))
                .isEqualTo(new AutomaticTriggerProperties(true, 100, Duration.ofSeconds(5), Duration.ofMinutes(30))));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "queue-capacity, 0, recon.matching.automatic-trigger.queue-capacity must be positive",
            "busy-retry-interval, 0s, recon.matching.automatic-trigger.busy-retry-interval must be a positive duration",
            "busy-retry-interval, -1s, recon.matching.automatic-trigger.busy-retry-interval must be a positive duration",
            "busy-give-up-after, 0s, recon.matching.automatic-trigger.busy-give-up-after must be a positive duration",
            "busy-give-up-after, -1m, recon.matching.automatic-trigger.busy-give-up-after must be a positive duration"})
    @DisplayName("a setting that cannot hold fails the binding, and so the application's startup")
    void impossibleSettingFails(String property, String value, String message) {
        context.withPropertyValues("recon.matching.automatic-trigger." + property + "=" + value)
                .run(started -> assertThatThrownBy(() -> started.getBean(AutomaticTriggerProperties.class))
                        .hasRootCauseMessage(message));
    }

    @EnableConfigurationProperties(AutomaticTriggerProperties.class)
    static class SettingsOnly {
    }
}
