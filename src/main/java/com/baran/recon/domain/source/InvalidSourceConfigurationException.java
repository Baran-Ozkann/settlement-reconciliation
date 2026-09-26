package com.baran.recon.domain.source;

import com.baran.recon.domain.DomainException;

/** The configured sources contradict each other, so no mapping can be built from them. */
public final class InvalidSourceConfigurationException extends DomainException {

    public InvalidSourceConfigurationException(String message) {
        super(message);
    }
}
