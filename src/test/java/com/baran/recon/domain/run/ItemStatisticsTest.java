package com.baran.recon.domain.run;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.money.CurrencyCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("INV-1, INV-4: a run's items by status must add up to its scope, per side and currency")
class ItemStatisticsTest {

    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("statuses that add up give a count and a sum per side, currency and status, zero where none")
    void conservedTotalsBecomeStatistics() {
        Map<String, Long> statistics = ItemStatistics.conserved(
                List.of(status(ItemSide.LEDGER, TRY, ItemStatus.MATCHED, 2, 3_000),
                        status(ItemSide.LEDGER, TRY, ItemStatus.BROKEN, 1, -500),
                        status(ItemSide.PSP, EUR, ItemStatus.PENDING, 1, 700)),
                List.of(scope(ItemSide.LEDGER, TRY, 3, 2_500), scope(ItemSide.PSP, EUR, 1, 700)));

        assertThat(statistics).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("ledger.TRY.matched.count", 2L), Map.entry("ledger.TRY.matched.sum", 3_000L),
                Map.entry("ledger.TRY.pending.count", 0L), Map.entry("ledger.TRY.pending.sum", 0L),
                Map.entry("ledger.TRY.broken.count", 1L), Map.entry("ledger.TRY.broken.sum", -500L),
                Map.entry("psp.EUR.matched.count", 0L), Map.entry("psp.EUR.matched.sum", 0L),
                Map.entry("psp.EUR.pending.count", 1L), Map.entry("psp.EUR.pending.sum", 700L),
                Map.entry("psp.EUR.broken.count", 0L), Map.entry("psp.EUR.broken.sum", 0L)));
        assertThat(ItemStatistics.countKey(ItemSide.PSP, EUR, ItemStatus.PENDING)).isEqualTo("psp.EUR.pending.count");
        assertThat(ItemStatistics.sumKey(ItemSide.LEDGER, TRY, ItemStatus.BROKEN)).isEqualTo("ledger.TRY.broken.sum");
    }

    @Test
    @DisplayName("break proof, INV-1: an item counted twice, or missed, fails the check")
    void countThatDoesNotAddUpIsRefused() {
        assertThatThrownBy(() -> ItemStatistics.conserved(
                List.of(status(ItemSide.LEDGER, TRY, ItemStatus.MATCHED, 2, 2_000),
                        status(ItemSide.LEDGER, TRY, ItemStatus.BROKEN, 1, 0)),
                List.of(scope(ItemSide.LEDGER, TRY, 2, 2_000))))
                .isInstanceOf(ScopeNotConservedException.class).hasMessageContaining("LEDGER TRY");
        assertThatThrownBy(() -> ItemStatistics.conserved(
                List.of(status(ItemSide.PSP, TRY, ItemStatus.PENDING, 1, 1_000)),
                List.of(scope(ItemSide.PSP, TRY, 2, 1_000))))
                .isInstanceOf(ScopeNotConservedException.class);
    }

    @Test
    @DisplayName("break proof, INV-4: amounts by status that do not sum to the scope's fail the check")
    void sumThatDoesNotAddUpIsRefused() {
        assertThatThrownBy(() -> ItemStatistics.conserved(
                List.of(status(ItemSide.PSP, TRY, ItemStatus.MATCHED, 1, 1_000),
                        status(ItemSide.PSP, TRY, ItemStatus.PENDING, 1, 999)),
                List.of(scope(ItemSide.PSP, TRY, 2, 2_000))))
                .isInstanceOf(ScopeNotConservedException.class).hasMessageContaining("summing to 2000");
    }

    @Test
    @DisplayName("break proof: items of a status in a currency the scope does not have fail the check")
    void statusOutsideTheScopeIsRefused() {
        assertThatThrownBy(() -> ItemStatistics.conserved(
                List.of(status(ItemSide.PSP, EUR, ItemStatus.BROKEN, 1, 1_000)),
                List.of(scope(ItemSide.PSP, TRY, 1, 1_000))))
                .isInstanceOf(ScopeNotConservedException.class).hasMessageContaining("PSP EUR");
    }

    @Test
    @DisplayName("an empty scope has no statistics")
    void emptyScopeHasNone() {
        assertThat(ItemStatistics.conserved(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a total without a status among the statuses, or one with a status in the scope, is a caller's mistake")
    void misplacedTotalsAreRefused() {
        assertThatThrownBy(() -> ItemStatistics.conserved(List.of(scope(ItemSide.PSP, TRY, 1, 1)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemStatistics.conserved(List.of(),
                List.of(status(ItemSide.PSP, TRY, ItemStatus.MATCHED, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemTotal(ItemSide.PSP, TRY, Optional.empty(), 0, 0))
                .isInstanceOf(InvalidRunException.class);
    }

    private static ItemTotal status(ItemSide side, CurrencyCode currency, ItemStatus status, long items, long amount) {
        return new ItemTotal(side, currency, Optional.of(status), items, amount);
    }

    private static ItemTotal scope(ItemSide side, CurrencyCode currency, long items, long amount) {
        return new ItemTotal(side, currency, Optional.empty(), items, amount);
    }
}
