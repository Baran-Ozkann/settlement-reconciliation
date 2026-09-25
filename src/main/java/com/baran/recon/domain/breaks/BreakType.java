package com.baran.recon.domain.breaks;

/** The closed set of discrepancies (TDD 8.3, FR-BRK-1). */
public enum BreakType {
    MISSING_IN_PSP,
    MISSING_IN_LEDGER,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_LINE,
    AMBIGUOUS_MATCH,
    MISSING_SETTLEMENT,
    BATCH_AMOUNT_MISMATCH,
    UNEXPECTED_BANK_LINE
}
