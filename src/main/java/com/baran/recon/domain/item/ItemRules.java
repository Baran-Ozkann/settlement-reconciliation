package com.baran.recon.domain.item;

import java.util.Optional;
import java.util.regex.Pattern;

/** The identifier and text-length rules TDD 7 gives the file columns, shared by every item type. */
final class ItemRules {

    /** line_id and batch_id: 1-64 characters of [A-Za-z0-9_-]. */
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private ItemRules() {
    }

    static void identifier(String name, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new InvalidItemException(name + " must be 1-64 characters of [A-Za-z0-9_-]");
        }
    }

    /**
     * Optional text is absent or non-empty, never an empty string, so there is one way to say
     * "none" and the database stores it as NULL.
     */
    static void optionalText(String name, Optional<String> value, int maxLength) {
        value.ifPresent(text -> {
            if (text.isEmpty() || text.length() > maxLength) {
                throw new InvalidItemException(name + " must be absent or 1-" + maxLength + " characters");
            }
        });
    }
}
