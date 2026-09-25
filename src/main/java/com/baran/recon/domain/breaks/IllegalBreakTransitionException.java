package com.baran.recon.domain.breaks;

import com.baran.recon.domain.DomainException;

/** The lifecycle has no edge for this action from the break's current status (FR-BRK-3). */
public final class IllegalBreakTransitionException extends DomainException {

    private final BreakStatus from;
    private final BreakAction action;

    public IllegalBreakTransitionException(BreakStatus from, BreakAction action) {
        super("cannot " + action.describe() + " a break that is " + from);
        this.from = from;
        this.action = action;
    }

    public BreakStatus from() {
        return from;
    }

    public BreakAction action() {
        return action;
    }
}
