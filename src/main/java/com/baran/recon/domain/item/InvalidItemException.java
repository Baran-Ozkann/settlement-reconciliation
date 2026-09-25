package com.baran.recon.domain.item;

import com.baran.recon.domain.DomainException;

/** An item that breaks a rule every item of its kind must keep. */
public final class InvalidItemException extends DomainException {

    public InvalidItemException(String message) {
        super(message);
    }
}
