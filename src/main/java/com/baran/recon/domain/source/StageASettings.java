package com.baran.recon.domain.source;

/**
 * What Stage A reads from a PSP source's configuration (TDD 8.1), all in business days of the
 * configured calendar:
 * <ul>
 *   <li>{@code valueDateWindowDays}: how far apart a ledger entry's and a PSP line's value dates may
 *       be and still match;</li>
 *   <li>{@code graceDaysLedgerUnmatched}: how long a ledger entry may stay unmatched before it is
 *       MISSING_IN_PSP;</li>
 *   <li>{@code graceDaysPspUnmatched}: how long a PSP line may stay unmatched before it is
 *       MISSING_IN_LEDGER.</li>
 * </ul>
 * None may be negative. {@link SourceDefinition} refuses a negative one, naming the source and the
 * key, as it refuses a negative grace-days-batch-unpaid: only the source knows its own code.
 */
public record StageASettings(int valueDateWindowDays, int graceDaysLedgerUnmatched, int graceDaysPspUnmatched) {

    /** The values TDD 8.1 configures, for a PSP source that sets none of its own. */
    public static final StageASettings TDD_DEFAULTS = new StageASettings(2, 3, 1);
}
