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
 */
public record StageASettings(int valueDateWindowDays, int graceDaysLedgerUnmatched, int graceDaysPspUnmatched) {

    /** The values TDD 8.1 configures, for a PSP source that sets none of its own. */
    public static final StageASettings TDD_DEFAULTS = new StageASettings(2, 3, 1);

    public StageASettings {
        requireNotNegative("value-date-window-days", valueDateWindowDays);
        requireNotNegative("grace-days-ledger-unmatched", graceDaysLedgerUnmatched);
        requireNotNegative("grace-days-psp-unmatched", graceDaysPspUnmatched);
    }

    private static void requireNotNegative(String key, int days) {
        if (days < 0) {
            throw new InvalidSourceConfigurationException(key + " is a number of business days, not negative");
        }
    }
}
