package com.baran.recon.domain.statement;

import com.baran.recon.domain.DomainException;

/** A statement file record that breaks a rule every stored file must keep. */
public final class InvalidStatementFileException extends DomainException {

    public InvalidStatementFileException(String message) {
        super(message);
    }
}
