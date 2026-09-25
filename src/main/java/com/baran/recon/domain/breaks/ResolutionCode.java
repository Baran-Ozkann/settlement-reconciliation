package com.baran.recon.domain.breaks;

/** How a break was closed (TDD 8.3). */
public enum ResolutionCode {
    /** A later run matched the item. Only the system resolves with it, and only with it (FR-BRK-5). */
    MATCHED_LATE,
    MATCHED_MANUALLY,
    ADJUSTMENT_REQUIRED_IN_LEDGER,
    PSP_ERROR_CONFIRMED,
    BANK_ERROR_CONFIRMED,
    WRITTEN_OFF,
    FALSE_POSITIVE,
    DUPLICATE_CONFIRMED;

    public boolean isSystemOnly() {
        return this == MATCHED_LATE;
    }
}
