package com.baran.recon.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.application.ledger.ProjectLedgerEvents;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.source.LedgerAccountSources;

/** The ledger projection use case, built from the ports it needs. */
@Configuration(proxyBeanMethods = false)
class LedgerProjectionConfiguration {

    /** CLAUDE.md 7.2: time is injected, so a test can fix it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * {@code recon.value-date-zone}: the zone a ledger entry's created_at is read in to give its
     * value date (TDD 6). A zone id, not a fixed offset, so a change of the zone's rules is followed.
     */
    @Bean
    ProjectLedgerEvents projectLedgerEvents(LedgerAccountSources sources, LedgerEntryStore store,
                                            Transactions transactions, Clock clock,
                                            @Value("${recon.value-date-zone}") ZoneId valueDateZone) {
        return new ProjectLedgerEvents(sources, store, transactions, clock, valueDateZone);
    }
}
