package com.baran.recon.domain.run;

import java.util.Objects;
import java.util.Optional;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;

/**
 * How many of a run's in-scope items one side holds in one currency, and their amounts' sum in
 * minor units: of one status, or of every status when {@code status} is empty. A PSP line counts
 * its gross amount, the amount Stage A compares. A bank line counts its amount, and a batch its net
 * sum, the amount Stage B compares; a batch whose lines mix currencies is counted once in each of
 * them, so its items summed across currencies count it more than once (TDD 8.2).
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
