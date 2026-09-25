package com.baran.recon.domain.match;

import com.baran.recon.domain.DomainException;

/** A match whose items do not have the shape its rule produces. */
public final class InvalidMatchException extends DomainException {

    public InvalidMatchException(String message) {
        super(message);
    }
}
