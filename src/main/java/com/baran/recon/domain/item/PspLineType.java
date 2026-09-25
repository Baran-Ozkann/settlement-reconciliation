package com.baran.recon.domain.item;

/** The kind of a PSP settlement line (TDD 7.1), which fixes the sign of its gross amount. */
public enum PspLineType {
    PAYMENT(true),
    REFUND(false),
    CHARGEBACK(false);

    private final boolean positiveGross;

    PspLineType(boolean positiveGross) {
        this.positiveGross = positiveGross;
    }

    /** A payment brings money in; a refund or chargeback takes it back out. */
    public boolean hasPositiveGross() {
        return positiveGross;
    }
}
