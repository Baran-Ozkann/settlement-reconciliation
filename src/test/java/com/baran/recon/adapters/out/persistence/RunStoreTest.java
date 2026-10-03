package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.RunAlreadyRunningException;
import com.baran.recon.application.port.RunNotRunningException;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Reconciliation runs are recorded with their configuration and statistics, as recon_app")
class RunStoreTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);
    private static final Instant STARTED = Instant.parse("2026-09-26T10:00:00.000001Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunStore store;

    @Test
    @DisplayName("FR-MAT-8: a started run is read back with its configuration snapshot")
    void startedRunRoundTrips() {
        ReconciliationRun run = ReconciliationRun.start(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), FROM, TO,
                Map.of("value_date_zone", "Europe/Istanbul", "value_date_window_days", "2"), STARTED, "operator-001");

        store.insert(run);

        assertThat(store.findById(run.id())).contains(run);
    }

    @Test
    @DisplayName("FR-MAT-10: a completed run is read back with its statistics")
    void completedRunRoundTrips() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), FROM, TO,
                RunStatus.COMPLETED, new TreeMap<>(Map.of("value_date_zone", "Europe/Istanbul")),
                Optional.of(new TreeMap<>(Map.of(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 12L))),
                STARTED, Optional.of(STARTED.plusSeconds(90)), "system");

        store.insert(run);

        assertThat(store.findById(run.id())).contains(run);
    }

    @Test
    @DisplayName("TDD 5.3: a second RUNNING run of a source is refused, and the first is the source's running run")
    void secondRunningRunIsRefused() {
        SourceCode source = SourceCode.of("PSP_STORE_BUSY");
        ReconciliationRun first = running(source, STARTED);
        store.insert(first);

        assertThatThrownBy(() -> store.insert(running(source, STARTED.plusSeconds(1))))
                .isInstanceOfSatisfying(RunAlreadyRunningException.class,
                        refused -> assertThat(refused.source()).isEqualTo(source));
        assertThat(store.findRunning(source)).contains(first.id());

        store.recordOutcome(first.fail(STARTED.plusSeconds(2)));
        assertThat(store.findRunning(source)).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-10: a run's outcome is recorded once, with its statistics, and never overwritten")
    void outcomeIsRecordedOnce() {
        ReconciliationRun run = running(SourceCode.of("PSP_STORE_OUTCOME"), STARTED);
        store.insert(run);
        ReconciliationRun completed = run.complete(Map.of(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 4L),
                STARTED.plusSeconds(30));

        store.recordOutcome(completed);

        assertThat(store.findById(run.id())).contains(completed);
        assertThatThrownBy(() -> store.recordOutcome(run.fail(STARTED.plusSeconds(31))))
                .isInstanceOfSatisfying(RunNotRunningException.class,
                        refused -> assertThat(refused.runId()).isEqualTo(run.id()));
        assertThat(store.findById(run.id())).contains(completed);
    }

    @Test
    @DisplayName("TDD 5.3: every RUNNING run is set FAILED, never finishing before its start")
    void allRunningRunsAreFailed() {
        Instant now = STARTED.plusSeconds(60);
        ReconciliationRun earlier = running(SourceCode.of("PSP_STORE_LEFT_A"), STARTED);
        ReconciliationRun aheadOfThisClock = running(SourceCode.of("PSP_STORE_LEFT_B"), now.plusSeconds(5));
        store.insert(earlier);
        store.insert(aheadOfThisClock);

        assertThat(store.failAllRunning(now)).contains(earlier.id(), aheadOfThisClock.id());

        assertThat(store.findById(earlier.id())).contains(earlier.fail(now));
        assertThat(store.findById(aheadOfThisClock.id())).contains(aheadOfThisClock.fail(now.plusSeconds(5)));
    }

    private static ReconciliationRun running(SourceCode source, Instant startedAt) {
        return ReconciliationRun.start(UUID.randomUUID(), source, FROM, TO, Map.of("value_date_zone", "Europe/Istanbul"),
                startedAt, "operator-001");
    }
}
