package com.baran.recon.domain.breaks;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One state change of a break, as written to the append-only break_events (FR-BRK-6). The event
 * that opens a break has no {@code from}.
 */
public record BreakEvent(
        UUID breakId,
        Optional<BreakStatus> from,
        BreakStatus to,
        Optional<ResolutionCode> resolutionCode,
        Actor actor,
        Optional<String> reason,
        Instant occurredAt) {

    public BreakEvent {
        Objects.requireNonNull(breakId, "breakId");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (resolutionCode.isPresent() != to.isResolved()) {
            throw new InvalidBreakException("an event carries a resolution code exactly when it resolves");
        }
    }
}
