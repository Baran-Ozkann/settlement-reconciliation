package com.baran.recon.adapters.out.persistence;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.baran.recon.domain.breaks.BatchFigures;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.breaks.BreakDetails.BatchChangedAfterMatch;
import com.baran.recon.domain.breaks.BreakDetails.BatchTotalConflict;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.2 (v1.15): a break's details are written with the keys and order of the design doc, and read back")
class BreakDetailsJsonTest {

    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID MATCH_ID = UUID.fromString("ad000000-0000-4000-8000-000000000001");

    private final BreakDetailsJson details = new BreakDetailsJson(JsonMapper.builder().build());

    @Test
    @DisplayName("BATCH_TOTAL_CONFLICT: kind, the bank line's amount and currency, the batch's line count and sums")
    void batchTotalConflictIsWrittenAsDocumented() {
        BreakDetails conflict = new BatchTotalConflict(Money.of(125_000, TRY),
                new BatchFigures(3, List.of(Money.of(124_500, TRY))));

        String written = details.write(conflict);

        assertThat(written).isEqualTo("""
                {"kind":"BATCH_TOTAL_CONFLICT",\
                "bank":{"amount":125000,"currency":"TRY"},\
                "batch":{"lineCount":3,"sums":[{"currency":"TRY","amount":124500}]}}""");
        assertThat(details.read(written)).isEqualTo(conflict);
    }

    @Test
    @DisplayName("BATCH_CHANGED_AFTER_MATCH: kind, the match id, what the match recorded and what the batch is now")
    void batchChangedAfterMatchIsWrittenAsDocumented() {
        BreakDetails changed = new BatchChangedAfterMatch(MATCH_ID,
                new BatchFigures(3, List.of(Money.of(125_000, TRY))), new BatchFigures(4, List.of(Money.of(124_000, TRY))));

        String written = details.write(changed);

        assertThat(written).isEqualTo("""
                {"kind":"BATCH_CHANGED_AFTER_MATCH","matchId":"ad000000-0000-4000-8000-000000000001",\
                "matched":{"lineCount":3,"sums":[{"currency":"TRY","amount":125000}]},\
                "current":{"lineCount":4,"sums":[{"currency":"TRY","amount":124000}]}}""");
        assertThat(details.read(written)).isEqualTo(changed);
    }

    @Test
    @DisplayName("INV-5: a mixed-currency batch's sums are written in currency-code order, with negative amounts exact")
    void twoCurrencySumsInCurrencyCodeOrder() {
        BreakDetails conflict = new BatchTotalConflict(Money.of(9_000, TRY),
                new BatchFigures(3, List.of(Money.of(8_000, TRY), Money.of(-1_500, EUR))));

        String written = details.write(conflict);

        assertThat(written).endsWith("""
                "batch":{"lineCount":3,"sums":[{"currency":"EUR","amount":-1500},{"currency":"TRY","amount":8000}]}}""");
        assertThat(details.read(written)).isEqualTo(conflict);
    }

    @Test
    @DisplayName("INV-8: an amount at the edge of BIGINT is written and read exactly")
    void extremeAmountsAreExact() {
        BreakDetails conflict = new BatchTotalConflict(Money.of(Long.MAX_VALUE, TRY),
                new BatchFigures(2, List.of(Money.of(Long.MIN_VALUE, TRY))));

        assertThat(details.read(details.write(conflict))).isEqualTo(conflict);
    }

    @Test
    @DisplayName("a stored value no code path writes is refused, not guessed at")
    void unexpectedStoredValuesAreRefused() {
        assertThatThrownBy(() -> details.read("""
                {"kind":"BATCH_SOMETHING","bank":{"amount":1,"currency":"TRY"}}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown kind");
        assertThatThrownBy(() -> details.read("""
                {"kind":"BATCH_TOTAL_CONFLICT","bank":{"amount":1.5,"currency":"TRY"},\
                "batch":{"lineCount":1,"sums":[{"currency":"TRY","amount":1}]}}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> details.read("""
                {"kind":"BATCH_TOTAL_CONFLICT","bank":{"amount":1,"currency":"TRY"},\
                "batch":{"lineCount":1,"sums":[{"currency":"TRY","amount":1}]},"extra":1}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("keys");
        assertThatThrownBy(() -> details.read("""
                {"kind":"BATCH_TOTAL_CONFLICT","bank":{"amount":1,"currency":"TRY"},\
                "batch":{"lineCount":1,"sums":{"currency":"TRY","amount":1}}}"""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("list");
    }
}
