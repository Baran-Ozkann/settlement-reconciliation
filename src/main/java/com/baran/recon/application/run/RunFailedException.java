package com.baran.recon.application.run;

import java.util.UUID;

/**
 * A run whose work failed, was rolled back, and was recorded FAILED (TDD 5.3). It carries the run's
 * id, so a caller can point to the run, and the failure as its cause. Its message names the run
 * alone: the cause's message can quote a row.
 */
public final class RunFailedException extends RuntimeException {

    /** Kept plain: the run exists, recorded FAILED, before this is thrown. */
    private final UUID runId;

    RunFailedException(UUID runId, RuntimeException failure) {
        super("run " + runId + " failed and is recorded FAILED", failure);
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
