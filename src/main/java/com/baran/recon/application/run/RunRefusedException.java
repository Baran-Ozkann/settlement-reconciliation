package com.baran.recon.application.run;

import java.util.Optional;
import java.util.UUID;

/**
 * A run refused before it was recorded, so nothing of it exists. The reason is one of a closed set,
 * so a caller can answer each its own way and never has to read a message to decide.
 */
public final class RunRefusedException extends RuntimeException {

    public enum Reason {
        /** The source code names no configured source. */
        UNKNOWN_SOURCE,
        /** The value-date range ends before it starts. */
        INVALID_DATE_RANGE,
        /** The source has a RUNNING run, and the database allows one per source (TDD 5.3). */
        SOURCE_BUSY
    }

    private final Reason reason;
    /** Null unless the source is busy: kept plain because an exception is serializable and Optional is not. */
    private final UUID runningRunId;

    private RunRefusedException(Reason reason, Optional<UUID> runningRunId, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.runningRunId = runningRunId.orElse(null);
    }

    static RunRefusedException unknownSource() {
        return new RunRefusedException(Reason.UNKNOWN_SOURCE, Optional.empty(), "no configured source has this code",
                null);
    }

    static RunRefusedException invalidDateRange() {
        return new RunRefusedException(Reason.INVALID_DATE_RANGE, Optional.empty(),
                "the value-date range ends before it starts", null);
    }

    static RunRefusedException sourceBusy(Optional<UUID> runningRunId, Throwable cause) {
        return new RunRefusedException(Reason.SOURCE_BUSY, runningRunId, "the source already has a running run", cause);
    }

    public Reason reason() {
        return reason;
    }

    /**
     * For a busy source, the run that holds it. Empty if that run finished between the refusal and
     * the look-up.
     */
    public Optional<UUID> runningRunId() {
        return Optional.ofNullable(runningRunId);
    }
}
