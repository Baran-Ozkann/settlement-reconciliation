package com.baran.recon.domain.run;

import java.util.Objects;
import java.util.Optional;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;

/**
 * How many of a run's in-scope items one side holds in one currency, and their amounts' sum in
 * minor units: of one status, or of every status when {@code status} is empty. A PSP line counts
 * its gross amount, the amount Stage A compares.
 */
public record ItemTotal(ItemSide side, CurrencyCode currency, Optional<ItemStatus> status, long items, long amount) {

    public ItemTotal {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        if (items < 1) {
            throw new InvalidRunException("a total counts at least one item");
        }
    }
}
