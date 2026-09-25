package com.baran.recon.domain.money;

import com.baran.recon.domain.DomainException;

/** The exact result does not fit in a long. A wrapped amount would be a wrong amount (INV-8). */
public final class MoneyOverflowException extends DomainException {

    public MoneyOverflowException(String operation, ArithmeticException cause) {
        super("amount overflow in " + operation, cause);
    }
}
