package com.baran.recon.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.source.ConfiguredSources;

/** The matching run use case, built from the ports it needs (TDD 5.3). */
@Configuration(proxyBeanMethods = false)
class MatchingConfiguration {

    /** {@code recon.value-date-zone} is snapshotted onto every run (FR-MAT-8). */
    @Bean
    RunMatching runMatching(ConfiguredSources sources, RunStore runs, LedgerEntryStore ledgerEntries,
                            Transactions transactions, Clock clock,
                            @Value("${recon.value-date-zone}") ZoneId valueDateZone) {
        return new RunMatching(sources, runs, ledgerEntries, transactions, clock, valueDateZone);
    }
}
