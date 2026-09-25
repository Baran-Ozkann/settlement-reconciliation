package com.baran.recon.domain.breaks;

import com.baran.recon.domain.DomainException;

/** A break, or a request to change one, that breaks a lifecycle rule other than the transition itself. */
public final class InvalidBreakException extends DomainException {

    public InvalidBreakException(String message) {
        super(message);
    }
}
