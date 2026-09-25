package com.baran.recon.domain.match;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.Money;

/**
 * A link between items that reconcile (FR-MAT-6): which run made it, by which rule and rule version,
 * and any amount difference. The rule fixes the cardinality and the confidence, so neither can be
 * stated wrongly, and the items must have the shape the rule produces:
 * <ul>
 *   <li>one to one: one ledger entry and one PSP line;</li>
 *   <li>many to one: one or more PSP lines and one bank line.</li>
 * </ul>
 */
public record Match(
        UUID id,
        UUID runId,
        RuleId rule,
        int ruleVersion,
        MatchStatus status,
        Money amountDifference,
        Instant createdAt,
        List<MatchItem> items) {

    public Match {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amountDifference, "amountDifference");
        Objects.requireNonNull(createdAt, "createdAt");
        if (ruleVersion < 1) {
            throw new InvalidMatchException("rule version starts at 1");
        }
        items = List.copyOf(items);
        if (new HashSet<>(items).size() != items.size()) {
            throw new InvalidMatchException("an item appears twice in one match");
        }
        requireShape(rule.cardinality(), countBySide(items));
    }

    /** A new match, active from the moment the run records it. */
    public static Match active(UUID id, UUID runId, RuleId rule, int ruleVersion, Money amountDifference,
                               Instant createdAt, List<MatchItem> items) {
        return new Match(id, runId, rule, ruleVersion, MatchStatus.ACTIVE, amountDifference, createdAt, items);
    }

    public Cardinality cardinality() {
        return rule.cardinality();
    }

    public boolean lowConfidence() {
        return rule.lowConfidence();
    }

    private static Map<ItemSide, Long> countBySide(List<MatchItem> items) {
        return items.stream().collect(Collectors.groupingBy(MatchItem::side, Collectors.counting()));
    }

    private static void requireShape(Cardinality cardinality, Map<ItemSide, Long> sides) {
        Function<ItemSide, Long> count = side -> sides.getOrDefault(side, 0L);
        boolean fits = switch (cardinality) {
            case ONE_TO_ONE -> sides.size() == 2 && count.apply(ItemSide.LEDGER) == 1 && count.apply(ItemSide.PSP) == 1;
            case MANY_TO_ONE -> sides.size() == 2 && count.apply(ItemSide.PSP) >= 1 && count.apply(ItemSide.BANK) == 1;
        };
        if (!fits) {
            throw new InvalidMatchException(cardinality + " match cannot link " + sides);
        }
    }
}
