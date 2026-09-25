package com.baran.recon.application.port;

import java.util.List;
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
     * Applies a change to a stored break and appends its event.
     *
     * @throws StaleBreakTransitionException if the stored status is no longer the one the change
     *         was made from: someone else changed the break first
     */
    void apply(Transition transition);

    Optional<Break> findById(UUID id);

    /** The break's events in the order they happened. */
    List<BreakEvent> eventsOf(UUID breakId);
}
