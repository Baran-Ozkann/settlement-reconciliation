package com.baran.recon.application.port;

import com.baran.recon.domain.item.SourceCode;

/**
 * The source already has a RUNNING run, so the database refused a second one (TDD 5.3). The
 * statement failed, and the transaction it ran in with it.
 */
public final class RunAlreadyRunningException extends RuntimeException {

    private final SourceCode source;

    public RunAlreadyRunningException(SourceCode source, Throwable cause) {
        super("source " + source + " already has a running run", cause);
        this.source = source;
    }

    public SourceCode source() {
        return source;
    }
}
