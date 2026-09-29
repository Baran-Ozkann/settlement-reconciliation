package com.baran.recon.application.port;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakEvent;
import com.baran.recon.domain.breaks.Transition;

/**
 * Breaks and their append-only history (FR-BRK-6). Every write takes a {@link Transition}, so a
 * status change and the event recording it are always stored together or not at all.
 */
public interface BreakStore {

    /**
     * Stores a new break - opened, or reopened from a resolved one - with its opening event.
     *
     * @throws ItemAlreadyHasOpenBreakException if the item already has an unresolved break (INV-7)
     */
    void open(Transition opened);

    /**
     * Stores a new break with its opening event, unless its item already has an unresolved break
     * (INV-7), in which case nothing is written. Unlike {@link #open}, finding one is an answer, not
     * a failure, so the caller's transaction carries on: ingestion meets it when a line is repeated
     * again while its first repetition's break is still open.
     *
     * @return the break that now stands for the item: the new one, or the unresolved one it already had
     */
    Opening openUnlessUnresolved(Transition opened);

    /**
     * Applies a change to a stored break and appends its event.
     *
     * @throws StaleBreakTransitionException if the stored status is no longer the one the change
     *         was made from: someone else changed the break first
     */
    void apply(Transition transition);

    Optional<Break> findById(UUID id);

    /** The break's events in the order they happened. */
    List<BreakEvent> eventsOf(UUID breakId);

    /**
     * @param breakId the break that stands for the item
     * @param opened  whether that break was opened by this call
     */
    record Opening(UUID breakId, boolean opened) {

        public Opening {
            Objects.requireNonNull(breakId, "breakId");
        }
    }
}
