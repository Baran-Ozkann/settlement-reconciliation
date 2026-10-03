package com.baran.recon.application.port;

import java.util.UUID;

/**
 * An outcome was recorded for a run that is no longer RUNNING: something else finished it first,
 * such as the startup recovery of another instance. Recording it anyway would overwrite that.
 */
public final class RunNotRunningException extends RuntimeException {

    private final UUID runId;

    public RunNotRunningException(UUID runId) {
        super("run " + runId + " is no longer running");
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
