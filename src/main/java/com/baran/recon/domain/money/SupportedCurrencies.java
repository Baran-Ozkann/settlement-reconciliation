package com.baran.recon.domain.money;

import java.util.Set;

/**
 * The currencies this service is configured to reconcile (TDD 6): TRY alone by default, matching the
 * ledger's single currency. The set is configuration, so a PSP or bank line in another currency can
 * still be ingested and reported as a CURRENCY_MISMATCH rather than refused.
 */
public record SupportedCurrencies(Set<CurrencyCode> codes) {

    public SupportedCurrencies {
        codes = Set.copyOf(codes);
        if (codes.isEmpty()) {
            throw new NoSupportedCurrencyException();
        }
    }

    public boolean supports(CurrencyCode currency) {
        return codes.contains(currency);
    }
}
