package com.baran.recon.application.port;

import java.util.UUID;

/**
 * A change was made from a status the break no longer has: another operator or a run changed it
 * first. Applying it anyway would overwrite that change and record an event that did not happen.
 */
public final class StaleBreakTransitionException extends RuntimeException {

    private final UUID breakId;

    public StaleBreakTransitionException(UUID breakId) {
        super("break " + breakId + " was changed by someone else first");
        this.breakId = breakId;
    }

    public UUID breakId() {
        return breakId;
    }
}
