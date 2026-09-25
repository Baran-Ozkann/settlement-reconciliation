package com.baran.recon.domain.item;

import java.util.Objects;
import java.util.UUID;

/** A reference to one item of any side, as a break names the item it is about and those related to it. */
public record ItemRef(ItemSide side, UUID id) {

    public ItemRef {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(id, "id");
    }
}
