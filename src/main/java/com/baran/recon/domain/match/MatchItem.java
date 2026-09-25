package com.baran.recon.domain.match;

import java.util.Objects;
import java.util.UUID;

import com.baran.recon.domain.item.ItemSide;

/** One item a match links: its side and its id. */
public record MatchItem(ItemSide side, UUID itemId) {

    public MatchItem {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(itemId, "itemId");
    }
}
