package com.baran.recon.domain.run;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;

/**
 * A run's statistics of its in-scope items per side, currency and status (TDD 8.2, FR-API-4), after
 * the run's finalization check: per side and currency, the items of each status must add up to the
 * items in scope, in number (INV-1: each item is exactly one of MATCHED, PENDING and BROKEN) and in
 * amount (INV-4: Σ matched + Σ pending + Σ broken = Σ in scope).
 *
 * <p>Keys are {@code <side>.<currency>.<status>.count} and {@code .sum}, e.g.
 * {@code psp.TRY.broken.sum}, the sum in minor units. Every status of a side and currency in scope
 * is present, zero when no item has it.
 */
public final class ItemStatistics {

    private ItemStatistics() {
    }

    /**
     * @param byStatus totals of one status each
     * @param inScope  totals of every status, one per side and currency in scope
     * @throws ScopeNotConservedException if the statuses do not add up to the scope
     */
    public static SortedMap<String, Long> conserved(List<ItemTotal> byStatus, List<ItemTotal> inScope) {
        Map<Group, ItemTotal> scope = new HashMap<>();
        for (ItemTotal total : inScope) {
            if (total.status().isPresent() || scope.put(new Group(total.side(), total.currency()), total) != null) {
                throw new IllegalArgumentException("one scope total per side and currency, of every status");
            }
        }
        SortedMap<String, Long> statistics = new TreeMap<>();
        Map<Group, long[]> added = new HashMap<>();
        for (ItemTotal total : byStatus) {
            ItemStatus status = total.status()
                    .orElseThrow(() -> new IllegalArgumentException("a status total names its status"));
            Group group = new Group(total.side(), total.currency());
            if (!scope.containsKey(group)) {
                throw new ScopeNotConservedException(group + ": items of status " + status + " but none in scope");
            }
            if (statistics.putIfAbsent(key(group, status, "count"), total.items()) != null) {
                throw new IllegalArgumentException(group + ": status " + status + " totalled twice");
            }
            statistics.put(key(group, status, "sum"), total.amount());
            long[] sums = added.computeIfAbsent(group, any -> new long[2]);
            sums[0] = Math.addExact(sums[0], total.items());
            sums[1] = Math.addExact(sums[1], total.amount());
        }
        scope.forEach((group, total) -> {
            long[] sums = added.getOrDefault(group, new long[2]);
            if (sums[0] != total.items() || sums[1] != total.amount()) {
                throw new ScopeNotConservedException(group + ": " + total.items() + " items summing to " + total.amount()
                        + " in scope, but " + sums[0] + " summing to " + sums[1] + " by status");
            }
            for (ItemStatus status : ItemStatus.values()) {
                statistics.putIfAbsent(key(group, status, "count"), 0L);
                statistics.putIfAbsent(key(group, status, "sum"), 0L);
            }
        });
        return statistics;
    }

    /** The key of the number of items of the side, currency and status. */
    public static String countKey(ItemSide side, CurrencyCode currency, ItemStatus status) {
        return key(new Group(side, currency), status, "count");
    }

    /** The key of the sum, in minor units, of the amounts of the items of the side, currency and status. */
    public static String sumKey(ItemSide side, CurrencyCode currency, ItemStatus status) {
        return key(new Group(side, currency), status, "sum");
    }

    private static String key(Group group, ItemStatus status, String measure) {
        return group.side().name().toLowerCase(Locale.ROOT) + "." + group.currency().code() + "."
                + status.name().toLowerCase(Locale.ROOT) + "." + measure;
    }

    private record Group(ItemSide side, CurrencyCode currency) {

        @Override
        public String toString() {
            return side + " " + currency.code();
        }
    }
}
