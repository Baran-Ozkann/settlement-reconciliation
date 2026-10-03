package com.baran.recon.domain.match;

import java.util.Optional;

/**
 * The rules of Stage A in the fixed order they run (TDD 8.2, FR-MAT-3). A rule's version changes
 * whenever what it decides changes, so a match records the version that made it (FR-MAT-6) and a
 * run's snapshot records the versions it ran (FR-MAT-8).
 */
public enum StageARule {
    /** Same reference, currency and amount, value dates within the window: a match. */
    A1_EXACT_REFERENCE(1, Optional.of(RuleId.A1_EXACT_REFERENCE)),
    /** Same reference, but the currency or the amount differs: a break, never a match. */
    A2_REFERENCE_CONFLICT(1, Optional.empty()),
    /** No reference A1 could use, and exactly one candidate by currency, amount and window: a match. */
    A3_FALLBACK_UNIQUE(1, Optional.of(RuleId.A3_FALLBACK_UNIQUE));

    private final int version;
    private final Optional<RuleId> matchRule;

    StageARule(int version, Optional<RuleId> matchRule) {
        this.version = version;
        this.matchRule = matchRule;
    }

    public int version() {
        return version;
    }

    /** The rule id a match made by this rule records; empty for a rule that only opens breaks. */
    public Optional<RuleId> matchRule() {
        return matchRule;
    }
}
