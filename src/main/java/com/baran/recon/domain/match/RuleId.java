package com.baran.recon.domain.match;

/**
 * The rules that produce a match (TDD 8.2). A2 and B2 only ever open breaks, so they are not here.
 * Each rule fixes the shape of what it matches and whether the match is a confident one.
 */
public enum RuleId {
    A1_EXACT_REFERENCE(Cardinality.ONE_TO_ONE, false),
    /** Matched on amount, currency and date alone, so it is flagged for review. */
    A3_FALLBACK_UNIQUE(Cardinality.ONE_TO_ONE, true),
    B1_BATCH_TOTAL(Cardinality.MANY_TO_ONE, false);

    private final Cardinality cardinality;
    private final boolean lowConfidence;

    RuleId(Cardinality cardinality, boolean lowConfidence) {
        this.cardinality = cardinality;
        this.lowConfidence = lowConfidence;
    }

    public Cardinality cardinality() {
        return cardinality;
    }

    public boolean lowConfidence() {
        return lowConfidence;
    }
}
