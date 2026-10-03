package com.baran.recon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.domain.source.LedgerAccountSources;

/**
 * Builds the sources and the business calendar of TDD 8.1 once, at startup, so a contradictory
 * configuration fails there.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourcesProperties.class, BusinessCalendarProperties.class})
class SourcesConfiguration {

    @Bean
    ConfiguredSources configuredSources(SourcesProperties properties) {
        return ConfiguredSources.of(properties.definitions());
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
