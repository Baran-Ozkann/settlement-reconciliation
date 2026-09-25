package com.baran.recon.domain.match;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    @DisplayName("A1 links one ledger entry to one PSP line, confidently")
    void exactReferenceMatch() {
        Match match = match(RuleId.A1_EXACT_REFERENCE, List.of(LEDGER, PSP_1));

        assertThat(match.status()).isEqualTo(MatchStatus.ACTIVE);
        assertThat(match.cardinality()).isEqualTo(Cardinality.ONE_TO_ONE);
        assertThat(match.lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("A3 matches are flagged low confidence")
    void fallbackIsLowConfidence() {
        assertThat(match(RuleId.A3_FALLBACK_UNIQUE, List.of(LEDGER, PSP_1)).lowConfidence()).isTrue();
    }

    @Test
    @DisplayName("B1 links every line of a batch to one bank line")
    void batchTotalMatch() {
        Match match = match(RuleId.B1_BATCH_TOTAL, List.of(PSP_1, PSP_2, BANK));

        assertThat(match.cardinality()).isEqualTo(Cardinality.MANY_TO_ONE);
        assertThat(match.items()).hasSize(3);
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
    @DisplayName("a many-to-one match needs PSP lines and exactly one bank line")
    void manyToOneShape() {
        assertThatThrownBy(() -> match(RuleId.B1_BATCH_TOTAL, List.of(BANK)))
                .isInstanceOf(InvalidMatchException.class);
        assertThatThrownBy(() -> match(RuleId.B1_BATCH_TOTAL, List.of(PSP_1, BANK, LEDGER)))
                .isInstanceOf(InvalidMatchException.class);
    }

    @Test
    @DisplayName("an item cannot appear twice in one match")
    void duplicateItemsAreRejected() {
        assertThatThrownBy(() -> match(RuleId.B1_BATCH_TOTAL, List.of(PSP_1, PSP_1, BANK)))
                .isInstanceOf(InvalidMatchException.class);
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

        assertThat(Match.active(id, runId, RuleId.B1_BATCH_TOTAL, 1, NO_DIFFERENCE, AT, List.of(BANK, PSP_2, PSP_1)))
                .isEqualTo(Match.active(id, runId, RuleId.B1_BATCH_TOTAL, 1, NO_DIFFERENCE, AT, List.of(PSP_1, BANK, PSP_2)));
    }

    private static Match match(RuleId rule, List<MatchItem> items) {
        return Match.active(UUID.randomUUID(), UUID.randomUUID(), rule, 1, NO_DIFFERENCE, AT, items);
    }
}
