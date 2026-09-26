package com.baran.recon.domain.money;

import com.baran.recon.domain.DomainException;

/** The configuration names no supported currency, so nothing could ever be reconciled. */
public final class NoSupportedCurrencyException extends DomainException {

    public NoSupportedCurrencyException() {
        super("at least one supported currency must be configured");
    }
}
