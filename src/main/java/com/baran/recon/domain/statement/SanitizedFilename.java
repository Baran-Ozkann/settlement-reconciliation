package com.baran.recon.domain.statement;

import java.util.Objects;

/**
 * The only form of an uploaded file's name that is ever stored or logged (FR-ING-9): characters of
 * {@code [A-Za-z0-9._-]}, at most 100 of them. The client's name is hostile input. It may carry a
 * path, control characters that forge a log line, or text of a personal nature, and it is never
 * used as a path here in any form.
 *
 * <p>Whatever precedes the last {@code /} or {@code \} is dropped, so a browser that sends the full
 * client-side path still yields the file's own name. Each other character outside the allow-list
 * becomes {@code _}, one per code point, and the result is cut to 100 characters. A name with
 * nothing left becomes {@value #UNNAMED}.
 */
public final class SanitizedFilename {

    public static final int MAX_LENGTH = 100;
    static final String UNNAMED = "unnamed";

    private SanitizedFilename() {
    }

    public static String of(String original) {
        if (original == null) {
            return UNNAMED;
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        StringBuilder sanitized = new StringBuilder(Math.min(name.length(), MAX_LENGTH));
        name.codePoints()
                .limit(MAX_LENGTH)
                .forEach(codePoint -> sanitized.append(isAllowed(codePoint) ? (char) codePoint : '_'));
        return sanitized.isEmpty() ? UNNAMED : sanitized.toString();
    }

    /** Whether the text is already in stored form, as {@link StatementFile} requires. */
    static boolean isSanitized(String name) {
        Objects.requireNonNull(name, "name");
        return !name.isEmpty() && name.length() <= MAX_LENGTH && name.chars().allMatch(SanitizedFilename::isAllowed);
    }

    private static boolean isAllowed(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-';
    }
}
