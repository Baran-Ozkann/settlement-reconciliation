package com.baran.recon.application.port;

import com.baran.recon.domain.item.ItemRef;

/** The item already has a break that is not resolved (INV-7, FR-BRK-2); a second is not opened. */
public final class ItemAlreadyHasOpenBreakException extends RuntimeException {

    private final ItemRef item;

    public ItemAlreadyHasOpenBreakException(ItemRef item, Throwable cause) {
        super(item.side() + " item " + item.id() + " already has an unresolved break", cause);
        this.item = item;
    }

    public ItemRef item() {
        return item;
    }
}
