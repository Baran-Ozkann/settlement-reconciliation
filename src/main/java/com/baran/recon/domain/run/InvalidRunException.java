package com.baran.recon.domain.run;

import com.baran.recon.domain.DomainException;

/** A run record that breaks a rule every run must keep. */
public final class InvalidRunException extends DomainException {

    public InvalidRunException(String message) {
        super(message);
    }
}
