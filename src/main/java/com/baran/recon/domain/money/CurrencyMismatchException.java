package com.baran.recon.domain.money;

import com.baran.recon.domain.DomainException;

/** Arithmetic between two currencies. There is no FX conversion (TDD 2.2), so it has no answer. */
public final class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("cannot combine " + left + " with " + right);
    }
}
