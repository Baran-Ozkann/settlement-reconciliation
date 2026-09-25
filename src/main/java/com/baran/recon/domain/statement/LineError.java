package com.baran.recon.domain.statement;

import java.util.Objects;

/**
 * One invalid line: its number and why. Never its content (FR-ING-7), which may carry references
 * of a personal nature.
 */
public record LineError(long lineNumber, ValidationCode code) {

    public LineError {
        if (lineNumber < 1) {
            throw new InvalidStatementFileException("line numbers start at 1");
        }
        Objects.requireNonNull(code, "code");
    }
}
