package com.baran.recon.domain.match;

import java.util.Optional;

/**
 * The rules of Stage B in the fixed order they run (TDD 8.2, FR-MAT-3). As for Stage A, a rule's
 * version changes whenever what it decides changes, so a match records the version that made it
 * (FR-MAT-6) and a bank source's run snapshots the versions it ran (FR-MAT-8).
 */
public enum StageBRule {
    /**
     * The bank line's batch id names exactly one batch, of one currency equal to the line's, whose
     * net sum is the line's amount: a match of the batch and the line.
     */
    B1_BATCH_TOTAL(1, Optional.of(RuleId.B1_BATCH_TOTAL)),
    /** The id names exactly one batch, but its sum or its currency differs: a break, never a match. */
    B2_BATCH_CONFLICT(1, Optional.empty());

    private final int version;
    private final Optional<RuleId> matchRule;

    StageBRule(int version, Optional<RuleId> matchRule) {
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
