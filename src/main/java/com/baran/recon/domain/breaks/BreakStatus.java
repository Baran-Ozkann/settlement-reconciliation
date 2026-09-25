package com.baran.recon.domain.breaks;

/** Where a break is in its lifecycle (FR-BRK-3). RESOLVED is terminal. */
public enum BreakStatus {
    OPEN,
    INVESTIGATING,
    RESOLVED;

    public boolean isResolved() {
        return this == RESOLVED;
    }
}
