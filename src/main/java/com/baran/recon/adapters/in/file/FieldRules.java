package com.baran.recon.adapters.in.file;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.InvalidCurrencyCodeException;
import com.baran.recon.domain.statement.ValidationCode;

/**
 * The column rules both formats share (TDD 7), each turning one field's text into a typed value or
 * refusing it with the code TDD 7.3 gives that failure. A required column left empty is always
 * {@code REQUIRED_MISSING}, whatever its type.
 */
final class FieldRules {

    /** line_id and batch_id (TDD 7.1, 7.2). */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** TDD 7: dates are YYYY-MM-DD, nothing looser. */
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    /** TDD 7: decimal point, no thousands separator, no exponent, no plus sign, no padding. */
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
    /** Far above any amount a long holds, and short enough that no text makes BigDecimal expensive. */
    private static final int MAX_DECIMAL_LENGTH = 40;

    private FieldRules() {
    }

    static String identifier(String field) {
        required(field);
        if (!IDENTIFIER.matcher(field).matches()) {
            throw new LineRejected(ValidationCode.INVALID_FORMAT);
        }
        return field;
    }

    static LocalDate date(String field) {
        required(field);
        if (!DATE_SHAPE.matcher(field).matches()) {
            throw new LineRejected(ValidationCode.INVALID_DATE);
        }
        try {
            return LocalDate.parse(field, DATE);
        } catch (DateTimeParseException notADate) {
            throw new LineRejected(ValidationCode.INVALID_DATE);
        }
    }

    /** A real ISO 4217 code; whether it is a supported one is matching's business, not this rule's. */
    static CurrencyCode currency(String field) {
        required(field);
        try {
            return CurrencyCode.of(field);
        } catch (InvalidCurrencyCodeException notIso) {
            throw new LineRejected(ValidationCode.INVALID_CURRENCY);
        }
    }

    /** The text is a decimal of the allowed shape; its scale is checked once the currency is known. */
    static BigDecimal decimal(String field) {
        required(field);
        if (field.length() > MAX_DECIMAL_LENGTH || !DECIMAL.matcher(field).matches()) {
            throw new LineRejected(ValidationCode.INVALID_AMOUNT);
        }
        return new BigDecimal(field);
    }

    /**
     * Exact minor units (TDD 6). More decimal places than the currency has is refused, never
     * rounded, trailing zeros included; an amount that does not fit a long is refused too.
     */
    static long minorUnits(BigDecimal amount, CurrencyCode currency) {
        if (amount.scale() > currency.minorUnitDigits()) {
            throw new LineRejected(ValidationCode.SCALE_EXCEEDS_CURRENCY);
        }
        try {
            return amount.movePointRight(currency.minorUnitDigits()).longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new LineRejected(ValidationCode.INVALID_AMOUNT);
        }
    }

    /**
     * Optional free text: absent when empty, as the domain and the database store it. At most
     * {@code maxLength} characters, and no control character, which has no place in a reference and
     * would let a value forge a line wherever it is shown.
     */
    static Optional<String> optionalText(String field, int maxLength) {
        if (field.isEmpty()) {
            return Optional.empty();
        }
        if (field.length() > maxLength || field.chars().anyMatch(Character::isISOControl)) {
            throw new LineRejected(ValidationCode.INVALID_FORMAT);
        }
        return Optional.of(field);
    }

    static void required(String field) {
        if (field.isEmpty()) {
            throw new LineRejected(ValidationCode.REQUIRED_MISSING);
        }
    }

    /**
     * A line refused by a column or cross-field rule. Only thrown and caught inside this adapter, to
     * leave the line at its first failing rule; it carries no stack trace, since a file of invalid
     * lines would otherwise pay for one per line.
     */
    static final class LineRejected extends RuntimeException {

        private final ValidationCode code;

        LineRejected(ValidationCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        ValidationCode code() {
            return code;
        }
    }
}
