package com.baran.recon.domain.breaks;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.breaks.BreakDetails.BatchChangedAfterMatch;
import com.baran.recon.domain.breaks.BreakDetails.BatchTotalConflict;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.2 (v1.15), TDD 10: a BATCH_AMOUNT_MISMATCH carries its figures as details, and no other break does")
class BreakDetailsTest {

    private static final Instant OPENED = Instant.parse("2026-10-12T10:00:00Z");
    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final ItemRef BANK_LINE = new ItemRef(ItemSide.BANK, UUID.fromString("ba000000-0000-4000-8000-000000000001"));
    private static final ItemRef BATCH = new ItemRef(ItemSide.BATCH, UUID.fromString("ba7c0000-0000-4000-8000-000000000001"));
    private static final UUID MATCH_ID = UUID.fromString("ad000000-0000-4000-8000-000000000001");

    private static final BatchTotalConflict CONFLICT = new BatchTotalConflict(Money.of(125_000, TRY),
            new BatchFigures(3, List.of(Money.of(124_500, TRY))));
    private static final BatchChangedAfterMatch CHANGED = new BatchChangedAfterMatch(MATCH_ID,
            new BatchFigures(3, List.of(Money.of(125_000, TRY))), new BatchFigures(4, List.of(Money.of(124_000, TRY))));

    @Test
    @DisplayName("a batch's sums are kept in currency-code order whatever order they were given in (INV-5)")
    void sumsAreInCurrencyCodeOrder() {
        BatchFigures given = new BatchFigures(3, List.of(Money.of(700, TRY), Money.of(-200, EUR)));

        assertThat(given.sums()).containsExactly(Money.of(-200, EUR), Money.of(700, TRY));
        assertThat(given).isEqualTo(new BatchFigures(3, List.of(Money.of(-200, EUR), Money.of(700, TRY))));
    }

    @Test
    @DisplayName("a batch's figures need a line, one sum per currency, and no more currencies than lines")
    void batchFiguresHold() {
        assertThatThrownBy(() -> new BatchFigures(0, List.of(Money.of(1, TRY)))).isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> new BatchFigures(1, List.of())).isInstanceOf(InvalidBreakException.class);
        assertThatThrownBy(() -> new BatchFigures(2, List.of(Money.of(1, TRY), Money.of(2, TRY))))
                .isInstanceOf(InvalidBreakException.class).hasMessageContaining("one sum per currency");
        assertThatThrownBy(() -> new BatchFigures(1, List.of(Money.of(1, TRY), Money.of(2, EUR))))
                .isInstanceOf(InvalidBreakException.class);
        assertThat(new BatchFigures(1, List.of(Money.zero(TRY))).sums()).containsExactly(Money.zero(TRY));
    }

    @Test
    @DisplayName("what a match recorded is one amount, its bank line's")
    void matchedFiguresHoldOneAmount() {
        assertThatThrownBy(() -> new BatchChangedAfterMatch(MATCH_ID,
                new BatchFigures(2, List.of(Money.of(1, TRY), Money.of(1, EUR))),
                new BatchFigures(2, List.of(Money.of(1, TRY)))))
                .isInstanceOf(InvalidBreakException.class);
    }

    @Test
    @DisplayName("TDD 10: a BATCH_AMOUNT_MISMATCH opens with its details, on the side its kind is about, and keeps "
            + "them through every transition")
    void mismatchCarriesItsDetails() {
        Transition conflict = open(BreakType.BATCH_AMOUNT_MISMATCH, BANK_LINE, Optional.of(CONFLICT));
        Transition changed = open(BreakType.BATCH_AMOUNT_MISMATCH, BATCH, Optional.of(CHANGED));

        assertThat(conflict.result().details()).contains(CONFLICT);
        assertThat(changed.result().details()).contains(CHANGED);
        Break investigating = conflict.result()
                .startInvestigation(Actor.operator("operator-001"), Optional.empty(), OPENED.plusSeconds(1)).result();
        Break resolved = investigating.resolveAsMatchedLate("Matched by a later run", OPENED.plusSeconds(2)).result();
        assertThat(investigating.details()).contains(CONFLICT);
        assertThat(resolved.details()).contains(CONFLICT);
        assertThat(resolved.reopen(UUID.randomUUID(), Actor.operator("operator-001"), "The bank reversed it",
                OPENED.plusSeconds(3)).result().details()).contains(CONFLICT);
    }

    @Test
    @DisplayName("TDD 10: a BATCH_AMOUNT_MISMATCH without details, or another type with them, cannot be built")
    void detailsExactlyForBatchAmountMismatch() {
        assertThatThrownBy(() -> open(BreakType.BATCH_AMOUNT_MISMATCH, BANK_LINE, Optional.empty()))
                .isInstanceOf(InvalidBreakException.class).hasMessageContaining("exactly when");
        for (BreakType type : BreakType.values()) {
            if (type != BreakType.BATCH_AMOUNT_MISMATCH) {
                assertThatThrownBy(() -> open(type, BANK_LINE, Optional.of(CONFLICT)))
                        .as(type.name()).isInstanceOf(InvalidBreakException.class);
                assertThat(open(type, BANK_LINE, Optional.empty()).result().details()).as(type.name()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("TDD 8.2: a batch total conflict is about a bank line and a change after the match about a batch")
    void detailsFixTheSubjectSide() {
        assertThatThrownBy(() -> open(BreakType.BATCH_AMOUNT_MISMATCH, BATCH, Optional.of(CONFLICT)))
                .isInstanceOf(InvalidBreakException.class).hasMessageContaining("BANK");
        assertThatThrownBy(() -> open(BreakType.BATCH_AMOUNT_MISMATCH, BANK_LINE, Optional.of(CHANGED)))
                .isInstanceOf(InvalidBreakException.class).hasMessageContaining("BATCH");
    }

    private static Transition open(BreakType type, ItemRef item, Optional<BreakDetails> details) {
        return Break.open(UUID.randomUUID(), type, item, List.of(), details, Optional.empty(), Actor.SYSTEM,
                Optional.empty(), OPENED);
    }
}
