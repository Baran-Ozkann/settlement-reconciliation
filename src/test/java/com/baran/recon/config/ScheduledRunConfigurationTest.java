package com.baran.recon.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.run.AutomaticRunTrigger;
import com.baran.recon.config.ScheduledRunConfiguration.ScheduledRunProperties;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.source.BatchIdPattern;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.SourceDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@code recon.matching.scheduled-run} (TDD 5.3, v1.19): its settings bound and checked at startup,
 * the schedule built from the time of day and the value-date zone, and the scheduler wired to the
 * daily run only when it is switched on. The daily run's own work is tested in ScheduledRunsTest and
 * ScheduledRunGraceTest.
 */
@DisplayName("TDD 5.3 (v1.19): recon.matching.scheduled-run binds the daily run's switch, time and look-back, and "
        + "schedules it")
class ScheduledRunConfigurationTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final ApplicationContextRunner settings = new ApplicationContextRunner()
            .withUserConfiguration(SettingsOnly.class)
            .withPropertyValues("recon.matching.scheduled-run.enabled=true",
                    "recon.matching.scheduled-run.time-of-day=06:00",
                    "recon.matching.scheduled-run.look-back-days=30");

    @Test
    @DisplayName("the application's values bind: on, at 06:00, looking back 30 days")
    void settingsBind() {
        settings.run(started -> {
            ScheduledRunProperties properties = started.getBean(ScheduledRunProperties.class);
            assertThat(properties).isEqualTo(new ScheduledRunProperties(true, "06:00", 30));
            assertThat(properties.time()).isEqualTo(LocalTime.of(6, 0));
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "time-of-day, 6:00, recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm",
            "time-of-day, 24:00, recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm",
            "time-of-day, 06:60, recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm",
            "time-of-day, 06:00:00, recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm",
            "time-of-day, noon, recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm",
            "look-back-days, 0, recon.matching.scheduled-run.look-back-days must be at least 1",
            "look-back-days, -1, recon.matching.scheduled-run.look-back-days must be at least 1"})
    @DisplayName("TDD 5.3 (v1.19): a malformed time or a look-back below 1 fails the binding, and so startup, naming "
            + "the key")
    void impossibleSettingFails(String property, String value, String message) {
        settings.withPropertyValues("recon.matching.scheduled-run." + property + "=" + value)
                .run(started -> assertThatThrownBy(() -> started.getBean(ScheduledRunProperties.class))
                        .hasRootCauseMessage(message));
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): a missing time of day fails the binding, naming the key, even with the run switched "
            + "off")
    void missingTimeFails() {
        new ApplicationContextRunner().withUserConfiguration(SettingsOnly.class)
                .withPropertyValues("recon.matching.scheduled-run.enabled=false",
                        "recon.matching.scheduled-run.look-back-days=30")
                .run(started -> assertThatThrownBy(() -> started.getBean(ScheduledRunProperties.class))
                        .hasRootCauseMessage("recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm"));
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): the schedule fires every day at the configured time in the value-date zone")
    void scheduleIsTheTimeOfDayInTheValueDateZone() {
        CronTrigger schedule = ScheduledRunConfiguration.schedule(LocalTime.of(6, 0), ISTANBUL);

        assertThat(schedule.getExpression()).isEqualTo("0 0 6 * * *");
        assertThat(nextAfter(schedule, "2026-10-12T00:00:00Z")).isEqualTo("2026-10-12T03:00:00Z");
        assertThat(nextAfter(schedule, "2026-10-12T03:00:00Z")).isEqualTo("2026-10-13T03:00:00Z");
        CronTrigger tokyo = ScheduledRunConfiguration.schedule(LocalTime.of(6, 30), ZoneId.of("Asia/Tokyo"));
        assertThat(nextAfter(tokyo, "2026-10-12T00:00:00Z")).isEqualTo("2026-10-12T21:30:00Z");
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): switched on, the scheduler holds one task on that schedule, and the task queues the "
            + "daily run of every source")
    void switchedOnTheDailyRunIsScheduled() {
        ConcurrentLinkedQueue<String> started = new ConcurrentLinkedQueue<>();
        AutomaticRunTrigger trigger = AutomaticRunTrigger.onOneThread((source, from, to, by) -> {
            started.add(source + " " + from + " " + to + " " + by);
            return completed(source);
        }, 10, Duration.ofMillis(1), Duration.ofMinutes(30), Clock.systemUTC());
        wiring(trigger).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScheduledRunConfiguration.class);
            Set<ScheduledTask> tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            TriggerTask task = (TriggerTask) tasks.iterator().next().getTask();
            CronTrigger schedule = (CronTrigger) task.getTrigger();
            assertThat(schedule.getExpression()).isEqualTo("0 15 7 * * *");
            assertThat(nextAfter(schedule, "2026-10-12T00:00:00Z")).isEqualTo("2026-10-12T04:15:00Z");

            task.getRunnable().run();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(started).containsExactly(
                    "PSP_SCHEDULE_WIRED 2026-09-12 2026-10-12 system",
                    "BANK_SCHEDULE_WIRED 2026-09-12 2026-10-12 system"));
        });
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): switched off, nothing is scheduled")
    void switchedOffNothingIsScheduled() {
        wiring(RunTrigger.NONE).withPropertyValues("recon.matching.scheduled-run.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ScheduledRunConfiguration.class)
                        .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("TDD 5.3 (v1.19): switched on without the automatic trigger, whose thread it queues on, startup stops "
            + "naming both keys")
    void switchedOnWithoutTheAutomaticTriggerFails() {
        wiring(RunTrigger.NONE).run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessage("recon.matching.scheduled-run.enabled needs recon.matching.automatic-trigger.enabled: the "
                        + "scheduled run queues its runs on the automatic trigger's thread"));
    }

    /** The scheduling configuration with the beans it takes, switched on at 07:15 in Istanbul. */
    private static ApplicationContextRunner wiring(RunTrigger runTrigger) {
        BatchIdPattern pattern = BatchIdPattern.of("BATCH[-_]?([A-Za-z0-9_-]{1,64})");
        ConfiguredSources sources = ConfiguredSources.of(List.of(
                SourceDefinition.bank(SourceCode.of("BANK_SCHEDULE_WIRED"), pattern),
                SourceDefinition.psp(SourceCode.of("PSP_SCHEDULE_WIRED"), Set.of(), SourceCode.of("BANK_SCHEDULE_WIRED"))));
        return new ApplicationContextRunner()
                .withUserConfiguration(SettingsOnly.class, ScheduledRunConfiguration.class)
                .withBean(ConfiguredSources.class, () -> sources)
                .withBean(RunTrigger.class, () -> runTrigger)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC))
                .withPropertyValues("recon.value-date-zone=Europe/Istanbul",
                        "recon.matching.scheduled-run.enabled=true",
                        "recon.matching.scheduled-run.time-of-day=07:15",
                        "recon.matching.scheduled-run.look-back-days=30");
    }

    private static String nextAfter(CronTrigger schedule, String instant) {
        return schedule.nextExecution(new SimpleTriggerContext(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)))
                .toString();
    }

    private static ReconciliationRun completed(String source) {
        Instant now = Instant.parse("2026-10-12T09:00:00Z");
        return ReconciliationRun.start(UUID.randomUUID(), SourceCode.of(source), now.atZone(ISTANBUL).toLocalDate(),
                now.atZone(ISTANBUL).toLocalDate(), Map.of(), now, "system").complete(Map.of(), now);
    }

    @EnableConfigurationProperties(ScheduledRunProperties.class)
    static class SettingsOnly {
    }
}
