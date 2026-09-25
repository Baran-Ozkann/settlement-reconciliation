package com.baran.recon.domain.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 6: currency codes are ISO 4217 with defined minor units")
class CurrencyCodeTest {

    @Test
    @DisplayName("TRY has two minor-unit digits")
    void tryHasTwoMinorDigits() {
        assertThat(CurrencyCode.of("TRY").minorUnitDigits()).isEqualTo(2);
    }

    @Test
    @DisplayName("minor-unit digits follow ISO 4217, not an assumed two")
    void minorDigitsFollowIso() {
        assertThat(CurrencyCode.of("JPY").minorUnitDigits()).isZero();
        assertThat(CurrencyCode.of("KWD").minorUnitDigits()).isEqualTo(3);
    }

    @Test
    @DisplayName("the code is its text")
    void printsAsItsCode() {
        assertThat(CurrencyCode.of("TRY")).hasToString("TRY");
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @NullSource
    @ValueSource(strings = {"", "try", "TR", "TRYY", "T1Y", "ZZZ", "XAU", "XXX"})
    @DisplayName("a malformed code, an unknown code or one with no minor unit is rejected")
    void rejectsInvalidCodes(String code) {
        assertThatThrownBy(() -> CurrencyCode.of(code)).isInstanceOf(InvalidCurrencyCodeException.class);
    }
}
