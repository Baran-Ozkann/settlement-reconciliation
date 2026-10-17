package com.baran.recon.config;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.run.AutomaticRunTrigger;
import com.baran.recon.application.run.ScheduledRuns;
import com.baran.recon.domain.source.ConfiguredSources;

/**
 * The daily scheduled run (FR-MAT-1, TDD 5.3, v1.19), when switched on: Spring's scheduler calls
 * {@link ScheduledRuns#queueDailyRuns} once a day at the configured time in the value-date zone. The
 * schedule is kept here, apart from the use case, so a test calls the use case with a fixed clock and
 * another pins the schedule.
 *
 * <p>The scheduled run queues its runs on the automatic trigger's thread, so it needs the automatic
 * trigger: with {@code recon.matching.automatic-trigger.enabled} false there is no such thread, and
 * startup stops naming both keys rather than leave the schedule doing nothing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("recon.matching.scheduled-run.enabled")
@EnableScheduling
class ScheduledRunConfiguration implements SchedulingConfigurer {

    private final ScheduledRuns scheduledRuns;
    private final CronTrigger schedule;

    ScheduledRunConfiguration(ConfiguredSources sources, RunTrigger runTrigger, ScheduledRunProperties properties,
                              Clock clock, @Value("${recon.value-date-zone}") ZoneId valueDateZone) {
        if (!(runTrigger instanceof AutomaticRunTrigger automatic)) {
            throw new IllegalStateException("recon.matching.scheduled-run.enabled needs "
                    + "recon.matching.automatic-trigger.enabled: the scheduled run queues its runs on the automatic "
                    + "trigger's thread");
        }
        this.scheduledRuns = new ScheduledRuns(sources, automatic, clock, valueDateZone, properties.lookBackDays());
        this.schedule = schedule(properties.time(), valueDateZone);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(scheduledRuns::queueDailyRuns, schedule);
    }

    /** Every day at {@code time} in {@code zone}, to the second. */
    static CronTrigger schedule(LocalTime time, ZoneId zone) {
        return new CronTrigger("0 " + time.getMinute() + " " + time.getHour() + " * * *", zone);
    }

    /**
     * {@code recon.matching.scheduled-run} (TDD 5.3, v1.19). Bound and checked at startup whether or not
     * the run is switched on, so a malformed value never waits for the day it is switched on.
     *
     * @param enabled      whether the daily run is scheduled at all. On in the application; off in the
     *                     test profile, as the automatic trigger is, so no run starts behind a test's back
     * @param timeOfDay    when it runs each day, {@code HH:mm} in the value-date zone
     * @param lookBackDays how many days before today each source's run reaches back, at least 1
     */
    @ConfigurationProperties("recon.matching.scheduled-run")
    record ScheduledRunProperties(boolean enabled, String timeOfDay, int lookBackDays) {

        private static final DateTimeFormatter HOURS_AND_MINUTES =
                DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

        ScheduledRunProperties {
            parsed(timeOfDay);
            if (lookBackDays < 1) {
                throw new IllegalArgumentException("recon.matching.scheduled-run.look-back-days must be at least 1");
            }
        }

        LocalTime time() {
            return parsed(timeOfDay);
        }

        private static LocalTime parsed(String timeOfDay) {
            if (timeOfDay == null) {
                throw new IllegalArgumentException(
                        "recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm");
            }
            try {
                return LocalTime.parse(timeOfDay, HOURS_AND_MINUTES);
            } catch (DateTimeParseException malformed) {
                // Not chained: the parser's message quotes the value, and this one names the key.
                throw new IllegalArgumentException(
                        "recon.matching.scheduled-run.time-of-day must be a time of day as HH:mm");
            }
        }
    }
}
