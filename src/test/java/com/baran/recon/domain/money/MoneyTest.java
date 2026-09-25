package com.baran.recon.domain.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("INV-8: money is exact integer minor units")
class MoneyTest {

    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("adds and subtracts in minor units")
    void addsAndSubtracts() {
        assertThat(Money.of(1250, TRY).plus(Money.of(-250, TRY))).isEqualTo(Money.of(1000, TRY));
        assertThat(Money.of(1000, TRY).minus(Money.of(1250, TRY))).isEqualTo(Money.of(-250, TRY));
    }

    @Test
    @DisplayName("INV-8: addition that overflows a long throws instead of wrapping")
    void additionOverflowThrows() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, TRY).plus(Money.of(1, TRY)))
                .isInstanceOf(MoneyOverflowException.class)
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("INV-8: subtraction that overflows a long throws instead of wrapping")
    void subtractionOverflowThrows() {
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, TRY).minus(Money.of(1, TRY)))
                .isInstanceOf(MoneyOverflowException.class);
    }

    @Test
    @DisplayName("INV-8: negating the most negative amount throws instead of wrapping")
    void negationOverflowThrows() {
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, TRY).negated())
                .isInstanceOf(MoneyOverflowException.class);
    }

    @Test
    @DisplayName("arithmetic between two currencies throws")
    void currenciesNeverCombine() {
        assertThatThrownBy(() -> Money.of(100, TRY).plus(Money.of(100, EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.of(100, TRY).minus(Money.of(100, EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("sign and currency queries")
    void signAndCurrency() {
        assertThat(Money.zero(TRY).isZero()).isTrue();
        assertThat(Money.of(1, TRY).isPositive()).isTrue();
        assertThat(Money.of(-1, TRY).isNegative()).isTrue();
        assertThat(Money.of(-1, TRY).negated()).isEqualTo(Money.of(1, TRY));
        assertThat(Money.of(1, TRY).hasCurrency(TRY)).isTrue();
        assertThat(Money.of(1, TRY).hasCurrency(EUR)).isFalse();
    }

    @Test
    @DisplayName("an amount has a currency")
    void currencyRequired() {
        assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
    }
}
