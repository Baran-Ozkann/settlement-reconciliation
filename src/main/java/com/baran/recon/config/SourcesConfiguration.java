package com.baran.recon.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.LedgerAccountSources;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;

/**
 * Builds the sources and the business calendar of TDD 8.1 once, at startup, so a contradictory
 * configuration fails there.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourcesProperties.class, BusinessCalendarProperties.class})
class SourcesConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SourcesConfiguration.class);

    /**
     * A bank source that no PSP source settles to is allowed (TDD 8.1, v1.15): its run has no batch
     * to pair a line with, so every line it carries is an unexpected bank line, which is true. It is
     * logged once here, naming the source, so a forgotten settles-to is seen.
     */
    @Bean
    ConfiguredSources configuredSources(SourcesProperties properties) {
        ConfiguredSources sources = ConfiguredSources.of(properties.definitions());
        sources.all().stream()
                .filter(source -> source.type() == SourceType.BANK_STATEMENT)
                .map(SourceDefinition::code)
                .filter(bank -> sources.settlingTo(bank).isEmpty())
                .forEach(bank -> LOG.warn("Bank statement source {} has no PSP source settling to it, so each of its "
                        + "lines can only be an unexpected bank line", bank.value()));
        return sources;
    }

    @Bean
    LedgerAccountSources ledgerAccountSources(ConfiguredSources sources) {
        return LedgerAccountSources.of(sources.all());
    }

    @Bean
    BusinessCalendar businessCalendar(BusinessCalendarProperties properties) {
        return properties.toCalendar();
    }
}
