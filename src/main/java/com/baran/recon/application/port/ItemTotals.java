package com.baran.recon.application.port;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baran.recon.domain.run.ItemTotal;

/**
 * A run's in-scope items, totalled per side and currency by status and of every status (INV-1,
 * INV-4). The two agree unless an item was counted twice or not at all, which the run's
 * finalization check finds.
 */
public record ItemTotals(List<ItemTotal> byStatus, List<ItemTotal> inScope) {

    public ItemTotals {
        byStatus = List.copyOf(byStatus);
        inScope = List.copyOf(inScope);
    }

    /** Totals read together, split into those of one status and those of every status. */
    public static ItemTotals partitioned(List<ItemTotal> totals) {
        Map<Boolean, List<ItemTotal>> byKind = totals.stream()
                .collect(Collectors.partitioningBy(total -> total.status().isPresent()));
        return new ItemTotals(byKind.get(true), byKind.get(false));
    }
}
