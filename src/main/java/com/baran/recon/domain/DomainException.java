package com.baran.recon.domain;

/**
 * A rule of the domain was broken. Each rule has its own subtype, so a caller can tell a currency
 * mismatch from an illegal break transition without reading a message.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
