package com.baran.recon.domain.money;

import java.util.Currency;
import java.util.regex.Pattern;

/**
 * An ISO 4217 currency with a fixed number of minor-unit digits. Which currencies the service
 * accepts is configuration (TDD 6); this type only guarantees the code is a real currency whose
 * minor units are defined, which is what makes a {@link Money} amount mean something.
 */
public record CurrencyCode(String code) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}");

    public CurrencyCode {
        if (code == null || !FORMAT.matcher(code).matches()) {
            throw new InvalidCurrencyCodeException(code);
        }
        if (minorUnitDigitsOf(code) < 0) {
            throw new InvalidCurrencyCodeException(code);
        }
    }

    public static CurrencyCode of(String code) {
        return new CurrencyCode(code);
    }

    /** Digits after the decimal point in the currency's ordinary notation: 2 for TRY. */
    public int minorUnitDigits() {
        return minorUnitDigitsOf(code);
    }

    @Override
    public String toString() {
        return code;
    }

    /**
     * -1 for a code the JDK does not know, and for the ISO pseudo-currencies (gold, testing codes)
     * that have no minor unit, so neither can carry an amount.
     */
    private static int minorUnitDigitsOf(String code) {
        try {
            return Currency.getInstance(code).getDefaultFractionDigits();
        } catch (IllegalArgumentException unknown) {
            return -1;
        }
    }
}
