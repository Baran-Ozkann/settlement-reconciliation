package com.baran.recon.domain.match;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
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
 *   <li>many to one: one batch and one bank line. The batch's PSP lines are not items of the match:
 *       each keeps its own Stage A match, and an item is in one active match at most (INV-2). The N
 *       of N:1 is {@code batchLineCount}, the number of lines the batch had when it was matched,
 *       which a many-to-one match carries and no other does (TDD 8.2, 10).</li>
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
        List<MatchItem> items,
        OptionalInt batchLineCount) {

    private static final Comparator<MatchItem> CANONICAL_ORDER =
            Comparator.comparing(MatchItem::side).thenComparing(MatchItem::itemId);

    public Match {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amountDifference, "amountDifference");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(batchLineCount, "batchLineCount");
        if (ruleVersion < 1) {
            throw new InvalidMatchException("rule version starts at 1");
        }
        // The order a caller lists items in means nothing, so a match keeps one canonical order and
        // two matches of the same items are equal however they were listed or read back.
        items = items.stream().sorted(CANONICAL_ORDER).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw new InvalidMatchException("an item appears twice in one match");
        }
        requireShape(rule.cardinality(), countBySide(items));
        boolean manyToOne = rule.cardinality() == Cardinality.MANY_TO_ONE;
        if (manyToOne != batchLineCount.isPresent()) {
            throw new InvalidMatchException(manyToOne
                    ? "a " + rule + " match carries its batch's line count"
                    : "only a many-to-one match carries a batch line count");
        }
        if (manyToOne && batchLineCount.getAsInt() < 1) {
            throw new InvalidMatchException("a matched batch has at least one line");
        }
    }

    /** A new one-to-one match, active from the moment the run records it. */
    public static Match active(UUID id, UUID runId, RuleId rule, int ruleVersion, Money amountDifference,
                               Instant createdAt, List<MatchItem> items) {
        return new Match(id, runId, rule, ruleVersion, MatchStatus.ACTIVE, amountDifference, createdAt, items,
                OptionalInt.empty());
    }

    /** A new many-to-one match of a batch of {@code batchLineCount} lines, active once recorded. */
    public static Match activeBatch(UUID id, UUID runId, RuleId rule, int ruleVersion, Money amountDifference,
                                    Instant createdAt, List<MatchItem> items, int batchLineCount) {
        return new Match(id, runId, rule, ruleVersion, MatchStatus.ACTIVE, amountDifference, createdAt, items,
                OptionalInt.of(batchLineCount));
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
            case MANY_TO_ONE -> sides.size() == 2 && count.apply(ItemSide.BATCH) == 1 && count.apply(ItemSide.BANK) == 1;
        };
        if (!fits) {
            throw new InvalidMatchException(cardinality + " match cannot link " + sides);
        }
    }
}
