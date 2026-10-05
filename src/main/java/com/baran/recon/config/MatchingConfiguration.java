package com.baran.recon.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.port.StageAStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.AutomaticRunTrigger;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.application.run.ViewRun;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.source.ConfiguredSources;

/** The matching run use cases, built from the ports they need (TDD 5.3). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingConfiguration.AutomaticTriggerProperties.class)
class MatchingConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MatchingConfiguration.class);

    /** {@code recon.value-date-zone} and the business calendar are snapshotted onto a run (FR-MAT-8). */
    @Bean
    RunMatching runMatching(ConfiguredSources sources, RunStore runs, LedgerEntryStore ledgerEntries, StageAStore stageA,
                            Transactions transactions, Clock clock,
                            @Value("${recon.value-date-zone}") ZoneId valueDateZone, BusinessCalendar calendar) {
        return new RunMatching(sources, runs, ledgerEntries, stageA, transactions, clock, valueDateZone, calendar);
    }

    @Bean
    ViewRun viewRun(RunStore runs) {
        return new ViewRun(runs);
    }

    /**
     * FR-MAT-1: the run after each ingestion, on one background thread, unless switched off. The
     * context closes the trigger before the run use case and the database it needs.
     */
    @Bean
    RunTrigger runTrigger(RunMatching runMatching, AutomaticTriggerProperties properties, Clock clock) {
        if (!properties.enabled()) {
            return RunTrigger.NONE;
        }
        return AutomaticRunTrigger.onOneThread(runMatching::run, properties.queueCapacity(),
                properties.busyRetryInterval(), properties.busyGiveUpAfter(), clock);
    }

    /**
     * TDD 5.3: a run a stopped JVM left RUNNING is set FAILED at startup, so its source is free
     * again. This runs once every singleton exists and before the context starts its lifecycle
     * beans, the web server and the Kafka listener among them, so no run of this instance can have
     * started yet. Only run ids are logged.
     */
    @Bean
    SmartInitializingSingleton failRunsLeftRunning(RunMatching runMatching) {
        return () -> runMatching.failRunsLeftRunning()
                .forEach(id -> LOG.warn("Run {} was left RUNNING by a stopped instance and is now FAILED", id));
    }

    /**
     * {@code recon.matching.automatic-trigger} (FR-MAT-1): the run started after each ingestion, on a
     * single background thread. A value that cannot hold stops startup.
     *
     * @param enabled           whether an ingested file starts a run at all. On in the application;
     *                          off in the test profile, whose classes share sources and would race a
     *                          background run they did not ask for
     * @param queueCapacity     ingested files whose runs may wait for the thread. One more is refused
     *                          and logged, never held: the upload does not wait for a place
     * @param busyRetryInterval how long a run waits before trying again while its source has a
     *                          running run; each try is one refused insert
     * @param busyGiveUpAfter   how long a run's source may stay busy before the run is given up,
     *                          logged with the file's id, and left to be started by hand, so one
     *                          stuck run cannot hold every later triggered run
     */
    @ConfigurationProperties("recon.matching.automatic-trigger")
    record AutomaticTriggerProperties(boolean enabled, int queueCapacity, Duration busyRetryInterval,
                                      Duration busyGiveUpAfter) {

        AutomaticTriggerProperties {
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("recon.matching.automatic-trigger.queue-capacity must be positive");
            }
            if (busyRetryInterval == null || busyRetryInterval.isNegative() || busyRetryInterval.isZero()) {
                throw new IllegalArgumentException(
                        "recon.matching.automatic-trigger.busy-retry-interval must be a positive duration");
            }
            if (busyGiveUpAfter == null || busyGiveUpAfter.isNegative() || busyGiveUpAfter.isZero()) {
                throw new IllegalArgumentException(
                        "recon.matching.automatic-trigger.busy-give-up-after must be a positive duration");
            }
        }
    }
}
