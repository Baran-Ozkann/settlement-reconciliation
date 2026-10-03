package com.baran.recon.domain.run;

import com.baran.recon.domain.DomainException;

/**
 * A run's items by status do not add up to its scope (INV-1, INV-4): an item was counted twice, or
 * not at all. The run fails rather than record statistics that cannot be true.
 */
public final class ScopeNotConservedException extends DomainException {

    public ScopeNotConservedException(String message) {
        super(message);
    }
}
