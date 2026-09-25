package com.baran.recon.domain.match;

/** A reversed match is kept, never deleted (FR-MAT-7); its items return to the unmatched pool. */
public enum MatchStatus {
    ACTIVE,
    REVERSED
}
