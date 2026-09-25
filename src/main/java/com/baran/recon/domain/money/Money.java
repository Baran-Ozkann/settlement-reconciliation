package com.baran.recon.domain.money;

import java.util.Objects;
import java.util.function.LongBinaryOperator;

/**
 * An amount in integer minor units of one currency (INV-8, TDD 6), the same representation the
 * ledger uses. Arithmetic is exact: a result that does not fit throws rather than wrapping, and two
 * currencies never combine.
 */
public record Money(long minorUnits, CurrencyCode currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minorUnits, CurrencyCode currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        return combine(other, "addition", Math::addExact);
    }

    public Money minus(Money other) {
        return combine(other, "subtraction", Math::subtractExact);
    }

    public Money negated() {
        try {
            return new Money(Math.negateExact(minorUnits), currency);
        } catch (ArithmeticException overflow) {
            throw new MoneyOverflowException("negation", overflow);
        }
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean hasCurrency(CurrencyCode code) {
        return currency.equals(code);
    }

    private Money combine(Money other, String operation, LongBinaryOperator exact) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
        try {
            return new Money(exact.applyAsLong(minorUnits, other.minorUnits), currency);
        } catch (ArithmeticException overflow) {
            throw new MoneyOverflowException(operation, overflow);
        }
    }
}
