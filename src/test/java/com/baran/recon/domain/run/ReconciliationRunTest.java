package com.baran.recon.domain.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.domain.item.SourceCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FR-MAT-8, FR-MAT-10: a run records its window, configuration and statistics")
class ReconciliationRunTest {

    private static final SourceCode PSP = SourceCode.of("PSP_ALPHA");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);
    private static final Instant STARTED = Instant.parse("2026-09-26T10:00:00Z");

    @Test
    @DisplayName("FR-MAT-8: a started run is RUNNING and carries its configuration snapshot")
    void startedRun() {
        Map<String, String> config = new HashMap<>(Map.of("value_date_zone", "Europe/Istanbul"));
        ReconciliationRun run = ReconciliationRun.start(UUID.randomUUID(), PSP, FROM, TO, config, STARTED, "system");
        config.put("value_date_zone", "UTC");

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.configSnapshot()).containsEntry("value_date_zone", "Europe/Istanbul");
        assertThat(run.stats()).isEmpty();
        assertThat(run.finishedAt()).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-10: a completed run carries the count of entries without a value date")
    void completedRunHasStats() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), PSP, FROM, TO, RunStatus.COMPLETED,
                new TreeMap<>(), Optional.of(new TreeMap<>(Map.of(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 3L))),
                STARTED, Optional.of(STARTED.plusSeconds(60)), "system");

        assertThat(run.stats()).hasValueSatisfying(stats ->
                assertThat(stats).containsEntry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 3L));
    }

    @Test
    @DisplayName("a completed run without statistics is refused")
    void completedWithoutStatsIsRefused() {
        assertThatThrownBy(() -> new ReconciliationRun(UUID.randomUUID(), PSP, FROM, TO, RunStatus.COMPLETED,
                new TreeMap<>(), Optional.empty(), STARTED, Optional.of(STARTED.plusSeconds(60)), "system"))
                .isInstanceOf(InvalidRunException.class);
    }

    @Test
    @DisplayName("a run has a finish time exactly when it is no longer running, and never before its start")
    void finishTime() {
        assertThatThrownBy(() -> new ReconciliationRun(UUID.randomUUID(), PSP, FROM, TO, RunStatus.RUNNING,
                new TreeMap<>(), Optional.empty(), STARTED, Optional.of(STARTED), "system"))
                .isInstanceOf(InvalidRunException.class);
        assertThatThrownBy(() -> new ReconciliationRun(UUID.randomUUID(), PSP, FROM, TO, RunStatus.FAILED,
                new TreeMap<>(), Optional.empty(), STARTED, Optional.empty(), "system"))
                .isInstanceOf(InvalidRunException.class);
        assertThatThrownBy(() -> new ReconciliationRun(UUID.randomUUID(), PSP, FROM, TO, RunStatus.FAILED,
                new TreeMap<>(), Optional.empty(), STARTED, Optional.of(STARTED.minusSeconds(1)), "system"))
                .isInstanceOf(InvalidRunException.class);
    }

    @Test
    @DisplayName("the value-date window cannot end before it starts, and a trigger is named")
    void windowAndTrigger() {
        assertThatThrownBy(() -> ReconciliationRun.start(UUID.randomUUID(), PSP, TO, FROM, Map.of(), STARTED, "system"))
                .isInstanceOf(InvalidRunException.class);
        assertThatThrownBy(() -> ReconciliationRun.start(UUID.randomUUID(), PSP, FROM, TO, Map.of(), STARTED, " "))
                .isInstanceOf(InvalidRunException.class);
    }
}
