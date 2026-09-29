package com.baran.recon.domain.source;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A bank source's {@code batch-id-pattern} (TDD 8.1): finds the PSP batch id in a bank line's
 * reference. The first capturing group is the batch id. It is extracted once, at ingestion, and
 * kept with the line so Stage B never has to run the pattern again.
 *
 * <p>What the group captures must still be a batch id as TDD 7.1 writes one, 1-64 characters of
 * {@code [A-Za-z0-9_-]}. Anything else means no batch id could be extracted, which Stage B reports
 * as an unexpected bank line rather than guessing.
 */
public final class BatchIdPattern {

    private static final Pattern BATCH_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Pattern pattern;

    private BatchIdPattern(Pattern pattern) {
        this.pattern = pattern;
    }

    public static BatchIdPattern of(String regex) {
        if (regex == null || regex.isEmpty()) {
            throw new InvalidSourceConfigurationException("a batch-id-pattern is required");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException invalid) {
            throw new InvalidSourceConfigurationException("batch-id-pattern is not a valid regular expression");
        }
        if (pattern.matcher("").groupCount() < 1) {
            throw new InvalidSourceConfigurationException("batch-id-pattern needs a capturing group for the batch id");
        }
        return new BatchIdPattern(pattern);
    }

    /** The batch id in the reference, if the pattern finds one there that is a valid batch id. */
    public Optional<String> extract(Optional<String> reference) {
        return reference.flatMap(text -> {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return Optional.empty();
            }
            String batchId = matcher.group(1);
            return batchId != null && BATCH_ID.matcher(batchId).matches() ? Optional.of(batchId) : Optional.empty();
        });
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BatchIdPattern that && pattern.pattern().equals(that.pattern.pattern());
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern.pattern());
    }

    @Override
    public String toString() {
        return pattern.pattern();
    }
}
