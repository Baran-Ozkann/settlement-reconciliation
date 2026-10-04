package com.baran.recon.config;

import java.time.Clock;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StageAStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.application.run.ViewRun;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.source.ConfiguredSources;

/** The matching run use cases, built from the ports they need (TDD 5.3). */
@Configuration(proxyBeanMethods = false)
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
}
