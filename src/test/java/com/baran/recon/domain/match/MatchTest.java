package com.baran.recon.domain.match;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-MAT-6: a match records its rule, version, run, cardinality and difference")
class MatchTest {

    private static final Instant AT = Instant.parse("2026-09-24T10:00:00Z");
    private static final Money NO_DIFFERENCE = Money.zero(CurrencyCode.of("TRY"));

    private static final MatchItem LEDGER = new MatchItem(ItemSide.LEDGER, UUID.fromString("00000000-0000-4000-8000-000000000001"));
    private static final MatchItem PSP_1 = new MatchItem(ItemSide.PSP, UUID.fromString("00000000-0000-4000-8000-000000000002"));
    private static final MatchItem PSP_2 = new MatchItem(ItemSide.PSP, UUID.fromString("00000000-0000-4000-8000-000000000003"));
    private static final MatchItem BANK = new MatchItem(ItemSide.BANK, UUID.fromString("00000000-0000-4000-8000-000000000004"));
    private static final MatchItem BANK_2 = new MatchItem(ItemSide.BANK, UUID.fromString("00000000-0000-4000-8000-000000000005"));
    private static final MatchItem BATCH = new MatchItem(ItemSide.BATCH, UUID.fromString("00000000-0000-3000-8000-000000000006"));
    private static final MatchItem BATCH_2 = new MatchItem(ItemSide.BATCH, UUID.fromString("00000000-0000-3000-8000-000000000007"));

    @Test
    @DisplayName("A1 links one ledger entry to one PSP line, confidently, and carries no batch line count")
    void exactReferenceMatch() {
        Match match = match(RuleId.A1_EXACT_REFERENCE, List.of(LEDGER, PSP_1));

        assertThat(match.status()).isEqualTo(MatchStatus.ACTIVE);
        assertThat(match.cardinality()).isEqualTo(Cardinality.ONE_TO_ONE);
        assertThat(match.lowConfidence()).isFalse();
        assertThat(match.batchLineCount()).isEmpty();
    }

    @Test
    @DisplayName("A3 matches are flagged low confidence")
    void fallbackIsLowConfidence() {
        assertThat(match(RuleId.A3_FALLBACK_UNIQUE, List.of(LEDGER, PSP_1)).lowConfidence()).isTrue();
    }

    @Test
    @DisplayName("TDD 8.2 (v1.14): B1 links one batch to one bank line, confidently, and carries the batch's line count")
    void batchTotalMatch() {
        Match match = batchMatch(List.of(BATCH, BANK), 12);

        assertThat(match.cardinality()).isEqualTo(Cardinality.MANY_TO_ONE);
        assertThat(match.lowConfidence()).isFalse();
        assertThat(match.items()).containsExactly(BANK, BATCH);
        assertThat(match.batchLineCount()).hasValue(12);
    }

    @Test
    @DisplayName("a one-to-one match with the wrong items is rejected")
    void oneToOneShape() {
        assertThatThrownBy(() -> match(RuleId.A1_EXACT_REFERENCE, List.of(LEDGER, PSP_1, PSP_2)))
                .isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> match(RuleId.A1_EXACT_REFERENCE, List.of(PSP_1, PSP_2)))
                .isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> match(RuleId.A1_EXACT_REFERENCE, List.of(LEDGER, BANK)))
                .isInstanceOf(InvalidMatchException.class);
    }

    @Test
    @DisplayName("TDD 8.2 (v1.14), INV-2: a many-to-one match is exactly one batch and one bank line; PSP lines are "
            + "never its items")
    void manyToOneShape() {
        assertThatThrownBy(() -> batchMatch(List.of(PSP_1, PSP_2, BANK), 2))
                .as("PSP lines with a bank line, the shape before v1.14")
                .isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, BANK, PSP_1), 1))
                .as("a batch, its bank line and one of its PSP lines")
                .isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, PSP_1), 1)).isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, LEDGER), 1)).isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, BATCH_2, BANK), 1)).isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, BANK, BANK_2), 1)).isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BATCH), 1)).isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> batchMatch(List.of(BANK), 1)).isInstanceOf(InvalidMatchException.class);
    }

    @Test
    @DisplayName("TDD 10 (v1.14): a B1 match without a line count, or with none above zero, is rejected, and a "
            + "one-to-one match cannot carry one")
    void batchLineCountGoesWithB1Only() {
        assertThatThrownBy(() -> match(RuleId.B1_BATCH_TOTAL, List.of(BATCH, BANK)))
                .isInstanceOf(InvalidMatchException.class).hasMessageContaining("line count");
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, BANK), 0))
                .isInstanceOf(InvalidMatchException.class).hasMessageContaining("at least one line");
        assertThatThrownBy(() -> new Match(UUID.randomUUID(), UUID.randomUUID(), RuleId.A1_EXACT_REFERENCE, 1,
                MatchStatus.ACTIVE, NO_DIFFERENCE, AT, List.of(LEDGER, PSP_1), OptionalInt.of(1)))
                .isInstanceOf(InvalidMatchException.class).hasMessageContaining("only a many-to-one match");
    }

    @Test
    @DisplayName("an item cannot appear twice in one match")
    void duplicateItemsAreRejected() {
        assertThatThrownBy(() -> batchMatch(List.of(BATCH, BATCH, BANK), 1))
                .isInstanceOf(InvalidMatchException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> match(RuleId.A1_EXACT_REFERENCE, List.of(LEDGER, PSP_1, PSP_1)))
                .isInstanceOf(InvalidMatchException.class).hasMessageContaining("twice");
    }

    @Test
    @DisplayName("rule versions start at 1")
    void ruleVersionStartsAtOne() {
        assertThatThrownBy(() -> Match.active(UUID.randomUUID(), UUID.randomUUID(), RuleId.A1_EXACT_REFERENCE, 0,
                NO_DIFFERENCE, AT, List.of(LEDGER, PSP_1)))
                .isInstanceOf(InvalidMatchException.class);
    }

    @Test
    @DisplayName("the item list is copied, so the caller cannot change a match afterwards")
    void itemsAreCopied() {
        List<MatchItem> items = new ArrayList<>(List.of(LEDGER, PSP_1));
        Match match = match(RuleId.A1_EXACT_REFERENCE, items);
        items.clear();

        assertThat(match.items()).containsExactly(LEDGER, PSP_1);
    }

    @Test
    @DisplayName("INV-5: the order items are listed in does not change the match")
    void itemOrderIsCanonical() {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        assertThat(Match.activeBatch(id, runId, RuleId.B1_BATCH_TOTAL, 1, NO_DIFFERENCE, AT, List.of(BANK, BATCH), 3))
                .isEqualTo(Match.activeBatch(id, runId, RuleId.B1_BATCH_TOTAL, 1, NO_DIFFERENCE, AT, List.of(BATCH, BANK), 3));
        assertThat(Match.active(id, runId, RuleId.A1_EXACT_REFERENCE, 1, NO_DIFFERENCE, AT, List.of(PSP_1, LEDGER)))
                .isEqualTo(Match.active(id, runId, RuleId.A1_EXACT_REFERENCE, 1, NO_DIFFERENCE, AT, List.of(LEDGER, PSP_1)));
    }

    private static Match match(RuleId rule, List<MatchItem> items) {
        return Match.active(UUID.randomUUID(), UUID.randomUUID(), rule, 1, NO_DIFFERENCE, AT, items);
    }

    private static Match batchMatch(List<MatchItem> items, int batchLineCount) {
        return Match.activeBatch(UUID.randomUUID(), UUID.randomUUID(), RuleId.B1_BATCH_TOTAL, 1, NO_DIFFERENCE, AT,
                items, batchLineCount);
    }
}
