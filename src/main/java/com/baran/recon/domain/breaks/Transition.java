package com.baran.recon.domain.breaks;

import java.util.Objects;

/**
 * A break after a change, together with the event that records the change. They come as one value
 * so the state and its audit row cannot be persisted apart (FR-BRK-6, FR-BRK-7).
 */
public record Transition(Break result, BreakEvent event) {

    public Transition {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(event, "event");
        if (!event.breakId().equals(result.id()) || event.to() != result.status()) {
            throw new InvalidBreakException("the event does not describe this break's new status");
        }
    }
}
