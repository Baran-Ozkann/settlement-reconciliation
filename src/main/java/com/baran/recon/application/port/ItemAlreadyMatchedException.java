package com.baran.recon.application.port;

import java.util.UUID;

/**
 * A match named an item that already belongs to an active match (INV-2). Matching is incremental
 * and never changes an existing active match (FR-MAT-2), so this means the engine considered an
 * item it should have excluded; the new match is not recorded.
 */
public final class ItemAlreadyMatchedException extends RuntimeException {

    private final UUID matchId;

    public ItemAlreadyMatchedException(UUID matchId, Throwable cause) {
        super("match " + matchId + " names an item that is already in an active match", cause);
        this.matchId = matchId;
    }

    public UUID matchId() {
        return matchId;
    }
}
