package com.baran.recon.config;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.SupportedCurrencies;

/**
 * {@code recon.supported-currencies} (TDD 6). A code that is not ISO 4217, or no code at all, stops
 * the application at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CurrencyConfiguration.CurrencyProperties.class)
class CurrencyConfiguration {

    @Bean
    SupportedCurrencies supportedCurrencies(CurrencyProperties properties) {
        return new SupportedCurrencies(properties.supportedCurrencies().stream()
                .map(CurrencyCode::of)
                .collect(Collectors.toSet()));
    }

    @ConfigurationProperties("recon")
    record CurrencyProperties(List<String> supportedCurrencies) {

        CurrencyProperties {
            supportedCurrencies = supportedCurrencies == null ? List.of() : List.copyOf(supportedCurrencies);
        }
    }
}
