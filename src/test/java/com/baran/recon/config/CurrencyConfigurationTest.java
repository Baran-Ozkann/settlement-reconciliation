package com.baran.recon.config;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.InvalidCurrencyCodeException;
import com.baran.recon.domain.money.NoSupportedCurrencyException;
import com.baran.recon.domain.money.SupportedCurrencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 6: recon.supported-currencies")
class CurrencyConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CurrencyConfiguration.class);

    @Test
    @DisplayName("TDD 6: with TRY configured, TRY is supported and USD is not")
    void tryAloneSupportsOnlyTry() {
        context.withPropertyValues("recon.supported-currencies=TRY").run(started -> {
            SupportedCurrencies supported = started.getBean(SupportedCurrencies.class);
            assertThat(supported.codes()).containsExactly(CurrencyCode.of("TRY"));
            assertThat(supported.supports(CurrencyCode.of("USD"))).isFalse();
        });
    }

    @Test
    @DisplayName("the set is configurable")
    void setIsConfigurable() {
        context.withPropertyValues("recon.supported-currencies=TRY,EUR").run(started ->
                assertThat(started.getBean(SupportedCurrencies.class).supports(CurrencyCode.of("EUR"))).isTrue());
    }

    @Test
    @DisplayName("a code that is not ISO 4217 stops the application at startup")
    void nonIsoCodeFailsStartup() {
        context.withPropertyValues("recon.supported-currencies=TRY,ABC").run(started ->
                assertThat(started).hasFailed().getFailure().rootCause().isInstanceOf(InvalidCurrencyCodeException.class));
    }

    @Test
    @DisplayName("an empty set is refused: nothing could ever be reconciled")
    void emptySetIsRefused() {
        assertThatThrownBy(() -> new SupportedCurrencies(Set.of())).isInstanceOf(NoSupportedCurrencyException.class);
    }
}
