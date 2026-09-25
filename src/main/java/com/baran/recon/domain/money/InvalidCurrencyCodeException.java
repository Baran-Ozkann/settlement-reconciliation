package com.baran.recon.domain.money;

import com.baran.recon.domain.DomainException;

/** The text is not an ISO 4217 code with defined minor units. */
public final class InvalidCurrencyCodeException extends DomainException {

    public InvalidCurrencyCodeException(String code) {
        super("not an ISO 4217 currency with minor units: " + code);
    }
}
