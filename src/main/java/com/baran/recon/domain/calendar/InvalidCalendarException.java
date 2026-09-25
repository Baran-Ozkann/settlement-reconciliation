package com.baran.recon.domain.calendar;

import com.baran.recon.domain.DomainException;

/** A calendar configuration that no date could be counted in. */
public final class InvalidCalendarException extends DomainException {

    public InvalidCalendarException(String message) {
        super(message);
    }
}
