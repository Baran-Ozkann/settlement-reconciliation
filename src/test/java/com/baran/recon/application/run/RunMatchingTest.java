package com.baran.recon.application.run;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunAlreadyRunningException;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.domain.item.LedgerEntry;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

/**
 * The run lifecycle of TDD 5.3 through the application's own context and database, as recon_app.
 * The database is shared with the other application tests, so each test uses a source of its own
 * and reads back only its own runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_RUN_SOLO",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].value-date-window-days=1",
        "recon.sources[0].grace-days-psp-unmatched=4",
        "recon.business-calendar.holidays[0]=2026-10-29",
        "recon.business-calendar.holidays[1]=2026-05-19",
        "recon.sources[1].code=PSP_RUN_SCOPE",
        "recon.sources[1].type=PSP_SETTLEMENT",
        "recon.sources[2].code=PSP_RUN_FAIL",
        "recon.sources[2].type=PSP_SETTLEMENT",
        "recon.sources[3].code=PSP_RUN_BUSY",
        "recon.sources[3].type=PSP_SETTLEMENT",
        "recon.sources[4].code=PSP_RUN_LEFT",
        "recon.sources[4].type=PSP_SETTLEMENT",
        "recon.sources[5].code=PSP_RUN_RIGHT",
        "recon.sources[5].type=PSP_SETTLEMENT",
        "recon.sources[6].code=BANK_RUN",
        "recon.sources[6].type=BANK_STATEMENT",
        "recon.sources[6].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[7].code=PSP_RUN_ROLLBACK",
        "recon.sources[7].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({HeldLedgerEntryStore.Injection.class, FailingRunStore.Injection.class})
@DisplayName("TDD 5.3: a matching run is recorded RUNNING, does its work in one transaction, and ends COMPLETED or FAILED")
class RunMatchingTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);
    private static final Duration WAIT = Duration.ofSeconds(20);
    /** Event ids no other test class uses; the shared database outlives each class. */
    private static final AtomicLong IDS = new AtomicLong(9_600_000_000L);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private RunStore runs;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StatementStore statements;

    @Autowired
    private Transactions transactions;

    @Test
    @DisplayName("FR-MAT-8, FR-MAT-10: a run with nothing to match completes with its snapshot and statistics")
    void runCompletesWithSnapshotAndStats() {
        ReconciliationRun completed = matching.run("PSP_RUN_SOLO", FROM, TO, "operator-001");

        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.source()).isEqualTo(SourceCode.of("PSP_RUN_SOLO"));
        assertThat(completed.valueDateFrom()).isEqualTo(FROM);
        assertThat(completed.valueDateTo()).isEqualTo(TO);
        assertThat(completed.triggeredBy()).isEqualTo("operator-001");
        assertThat(completed.configSnapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                RunMatching.VALUE_DATE_ZONE, "Europe/Istanbul",
                RunMatching.BUSINESS_CALENDAR_WEEKEND, "SATURDAY,SUNDAY",
                RunMatching.BUSINESS_CALENDAR_HOLIDAYS, "2026-05-19,2026-10-29",
                RunMatching.VALUE_DATE_WINDOW_DAYS, "1",
                RunMatching.GRACE_DAYS_LEDGER_UNMATCHED, "3",
                RunMatching.GRACE_DAYS_PSP_UNMATCHED, "4",
                "rule_version.A1_EXACT_REFERENCE", "1",
                "rule_version.A2_REFERENCE_CONFLICT", "1",
                "rule_version.A3_FALLBACK_UNIQUE", "1"));
        assertThat(completed.stats()).hasValueSatisfying(stats -> assertThat(stats).containsExactlyInAnyOrderEntriesOf(
                Map.of(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 0L, ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L)));
        assertThat(completed.finishedAt()).hasValueSatisfying(finished -> assertThat(finished).isAfterOrEqualTo(
                completed.startedAt()));
        assertThat(runs.findById(completed.id())).contains(completed);
        assertThat(runs.findRunning(SourceCode.of("PSP_RUN_SOLO"))).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-9, FR-MAT-10: an entry without a value date is out of the run's scope and counted")
    void entryWithoutValueDateIsOutOfScopeAndCounted() {
        SourceCode source = SourceCode.of("PSP_RUN_SCOPE");
        // Each insert must write its row, so an event id another class already used fails here.
        assertThat(ledgerEntries.storeIfAbsent(dated(source, FROM))).isTrue();
        assertThat(ledgerEntries.storeIfAbsent(dated(source, TO.plusDays(3)))).isTrue();
        assertThat(ledgerEntries.storeIfAbsent(undated(source))).isTrue();
        assertThat(ledgerEntries.storeIfAbsent(undated(source))).isTrue();
        assertThat(ledgerEntries.storeIfAbsent(undated(SourceCode.of("PSP_RUN_OTHER")))).isTrue();

        ReconciliationRun completed = matching.run(source.value(), FROM, TO, "system");

        assertThat(completed.stats()).hasValueSatisfying(stats -> {
            assertThat(stats).containsEntry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 1L);
            assertThat(stats).containsEntry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 2L);
        });
        assertThat(runs.findById(completed.id())).contains(completed);
    }

    @Test
    @DisplayName("NFR-REL-2: a failure inside the work transaction leaves nothing but a FAILED run row")
    void failureInsideTheWorkLeavesOnlyAFailedRun() {
        SourceCode source = SourceCode.of("PSP_RUN_FAIL");
        FailingRunStore failing = FailingRunStore.of(runs);
        int failedBefore = failing.completionsWrittenThenFailed();
        failing.failTheNextCompletionOf(source);

        assertThatThrownBy(() -> matching.run(source.value(), FROM, TO, "operator-001"))
                .isInstanceOf(IllegalStateException.class).hasMessage(FailingRunStore.FAILURE);

        assertThat(failing.completionsWrittenThenFailed()).as("COMPLETED was written before the failure")
                .isEqualTo(failedBefore + 1);
        List<UUID> ids = runIds(source);
        assertThat(ids).hasSize(1);
        ReconciliationRun failed = runs.findById(ids.getFirst()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.stats()).as("the statistics written with COMPLETED were rolled back").isEmpty();
        assertThat(failed.finishedAt()).isPresent();
        assertThat(jdbc.sql("SELECT count(*) FROM matches WHERE run_id = :runId").param("runId", failed.id())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM breaks WHERE opened_run_id = :runId").param("runId", failed.id())
                .query(Long.class).single()).isZero();

        assertThat(matching.run(source.value(), FROM, TO, "operator-001").status())
                .as("the failed run no longer holds the source").isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("TDD 5.3: of two runs started together on one source, one runs and the index refuses the other")
    void twoRunsOnOneSourceOneIsRefused() throws Exception {
        SourceCode source = SourceCode.of("PSP_RUN_BUSY");
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        held.hold(source);
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<ReconciliationRun>> started = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                started.add(threads.submit(() -> {
                    together.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                    return matching.run(source.value(), FROM, TO, "operator-001");
                }));
            }

            assertThat(held.awaitEntered(1, WAIT)).as("one run is inside its work").isTrue();
            await().atMost(WAIT).until(() -> started.stream().anyMatch(Future::isDone));
            Future<ReconciliationRun> refused = started.stream().filter(Future::isDone).findFirst().orElseThrow();
            Future<ReconciliationRun> running = started.get(started.indexOf(refused) == 0 ? 1 : 0);
            UUID runningId = runs.findRunning(source).orElseThrow();

            assertThatThrownBy(refused::get).isInstanceOf(ExecutionException.class).cause()
                    .isInstanceOfSatisfying(RunRefusedException.class, refusal -> {
                        assertThat(refusal.reason()).isEqualTo(RunRefusedException.Reason.SOURCE_BUSY);
                        assertThat(refusal.runningRunId()).contains(runningId);
                        assertThat(refusal.getCause()).as("refused by the database's index")
                                .isInstanceOf(RunAlreadyRunningException.class);
                    });
            assertThat(running.isDone()).as("the running run is still held").isFalse();

            held.release(source);
            ReconciliationRun completed = running.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(completed.id()).isEqualTo(runningId);
            assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(held.awaitEntered(1, Duration.ZERO)).as("the refused run never started its work").isFalse();
            assertThat(runIds(source)).as("the refused run left no row").containsExactly(runningId);
        } finally {
            held.release(source);
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("NFR-REL-2: a run that fails after Stage A wrote a match and a break leaves neither; a re-run makes both")
    void failureAfterStageAWroteLeavesNoneOfIt() {
        SourceCode source = SourceCode.of("PSP_RUN_ROLLBACK");
        StageAFixture fixture = new StageAFixture(ledgerEntries, statements, transactions, jdbc, 9_600_500_000L);
        UUID exact = UUID.randomUUID();
        UUID conflicting = UUID.randomUUID();
        fixture.ledger(source, exact, 1_000, "TRY", FROM);
        fixture.psp(source, "L-EXACT", exact.toString(), 1_000, "TRY", FROM);
        fixture.ledger(source, conflicting, 2_000, "TRY", FROM);
        fixture.psp(source, "L-CONFLICT", conflicting.toString(), 2_500, "TRY", FROM);
        FailingRunStore.of(runs).failTheNextCompletionOf(source);

        assertThatThrownBy(() -> matching.run(source.value(), FROM, TO, "operator-001"))
                .isInstanceOf(IllegalStateException.class).hasMessage(FailingRunStore.FAILURE);

        UUID failedId = runIds(source).getFirst();
        assertThat(runs.findById(failedId).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(fixture.matches(source)).as("the A1 match was rolled back").isEmpty();
        assertThat(fixture.breaks(source)).as("the A2 break was rolled back").isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM break_events e JOIN breaks b ON b.id = e.break_id "
                        + "WHERE b.opened_run_id = :runId").param("runId", failedId).query(Long.class).single())
                .isZero();

        ReconciliationRun retried = matching.run(source.value(), FROM, TO, "operator-001");
        assertThat(fixture.matches(source)).extracting(StageAFixture.MatchView::psp, StageAFixture.MatchView::runId)
                .containsExactly(tuple("PSP L-EXACT", retried.id()));
        assertThat(fixture.breaks(source)).extracting(StageAFixture.BreakView::type, StageAFixture.BreakView::subject)
                .containsExactly(tuple("AMOUNT_MISMATCH", "PSP L-CONFLICT"));
    }

    @Test
    @DisplayName("TDD 5.3: runs on two different sources run side by side")
    void runsOnTwoSourcesRunSideBySide() throws Exception {
        SourceCode left = SourceCode.of("PSP_RUN_LEFT");
        SourceCode right = SourceCode.of("PSP_RUN_RIGHT");
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        held.hold(left);
        held.hold(right);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<ReconciliationRun> leftRun = threads.submit(() -> matching.run(left.value(), FROM, TO, "system"));
            Future<ReconciliationRun> rightRun = threads.submit(() -> matching.run(right.value(), FROM, TO, "system"));

            assertThat(held.awaitEntered(2, WAIT)).as("both runs are inside their work at once").isTrue();
            assertThat(runs.findRunning(left)).isPresent();
            assertThat(runs.findRunning(right)).isPresent();

            held.release(left);
            held.release(right);
            assertThat(leftRun.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(rightRun.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
        } finally {
            held.release(left);
            held.release(right);
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("TDD 5.3: a bank source's run completes too; Stage A is for a PSP source only")
    void bankSourceRunCompletes() {
        ReconciliationRun completed = matching.run("BANK_RUN", FROM, TO, "system");

        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.configSnapshot()).as("Stage A's configuration is not read for a bank source")
                .containsOnlyKeys(RunMatching.VALUE_DATE_ZONE);
        assertThat(runs.findById(completed.id())).contains(completed);
    }

    @Test
    @DisplayName("an unknown source and a range that ends before it starts are refused, and nothing is recorded")
    void refusedRunsRecordNothing() {
        assertThatThrownBy(() -> matching.run("PSP_UNKNOWN", FROM, TO, "system"))
                .isInstanceOfSatisfying(RunRefusedException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(RunRefusedException.Reason.UNKNOWN_SOURCE));
        assertThatThrownBy(() -> matching.run("psp run solo", FROM, TO, "system"))
                .isInstanceOfSatisfying(RunRefusedException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(RunRefusedException.Reason.UNKNOWN_SOURCE));
        assertThatThrownBy(() -> matching.run("PSP_RUN_SOLO", TO, FROM, "system"))
                .isInstanceOfSatisfying(RunRefusedException.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(RunRefusedException.Reason.INVALID_DATE_RANGE);
                    assertThat(refusal.runningRunId()).isEmpty();
                });

        assertThat(runIds(SourceCode.of("PSP_UNKNOWN"))).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_runs WHERE value_date_from > value_date_to")
                .query(Long.class).single()).isZero();
    }

    private List<UUID> runIds(SourceCode source) {
        return jdbc.sql("SELECT id FROM reconciliation_runs WHERE source_code = :source")
                .param("source", source.value()).query(UUID.class).list();
    }

    private static LedgerEntry dated(SourceCode source, LocalDate valueDate) {
        Instant createdAt = valueDate.atStartOfDay(ZoneId.of("Europe/Istanbul")).plusHours(10).toInstant();
        long id = IDS.incrementAndGet();
        return new LedgerEntry(UUID.randomUUID(), id, Optional.of(id), UUID.randomUUID(), UUID.randomUUID(), source,
                Money.of(12_500, CurrencyCode.of("TRY")), "TRANSFER", Optional.of(createdAt), Optional.of(valueDate),
                createdAt.plusSeconds(1));
    }

    private static LedgerEntry undated(SourceCode source) {
        return new LedgerEntry(UUID.randomUUID(), IDS.incrementAndGet(), Optional.empty(), UUID.randomUUID(),
                UUID.randomUUID(), source, Money.of(-4_000, CurrencyCode.of("TRY")), "TRANSFER", Optional.empty(),
                Optional.empty(), Instant.parse("2026-09-24T08:15:01Z"));
    }
}
