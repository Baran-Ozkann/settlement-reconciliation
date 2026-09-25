package com.baran.recon.domain.item;

import java.util.regex.Pattern;

/** A configured source such as {@code PSP_ALPHA} or {@code BANK_MAIN} (TDD 8.1). */
public record SourceCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public SourceCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidItemException("source code must be 1-64 characters of [A-Z0-9_], starting with a letter");
        }
    }

    public static SourceCode of(String value) {
        return new SourceCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
