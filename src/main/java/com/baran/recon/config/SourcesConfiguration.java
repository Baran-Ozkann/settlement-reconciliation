package com.baran.recon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.domain.source.LedgerAccountSources;

/** Builds the account-to-source mapping once, at startup, so a contradictory configuration fails there. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourcesProperties.class)
class SourcesConfiguration {

    @Bean
    LedgerAccountSources ledgerAccountSources(SourcesProperties properties) {
        return LedgerAccountSources.of(properties.definitions());
    }
}
