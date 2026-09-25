package com.baran.recon.application.port;

import java.util.Optional;
import java.util.UUID;

import com.baran.recon.domain.match.Match;

/** Matches with their items and audit events. Reversal (FR-MAT-7) is Phase 7 work. */
public interface MatchStore {

    /**
     * Records the match, its items and its creation event together, or none of them.
     *
     * @throws ItemAlreadyMatchedException if an item already belongs to an active match (INV-2)
     */
    void record(Match match);

    Optional<Match> findById(UUID id);
}
