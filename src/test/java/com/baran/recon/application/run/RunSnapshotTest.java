package com.baran.recon.application.run;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.StageAStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageAFixture.BreakView;
import com.baran.recon.application.run.StageAFixture.MatchView;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * TDD 5.3: the run's work transaction is one snapshot. A run is held after A1, its first
 * statement, while the test commits; whatever it commits is seen by none of the run's later
 * statements, and a change to a row the run then writes fails the run. Runs are at
 * {@link FixedRunClock#NOW}, Monday 2026-10-12, with TDD 8.1's window and grace periods.
 * {@link RunSnapshotBreakProofTest} holds the same run at READ COMMITTED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_SNAPSHOT_UNSEEN",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=PSP_SNAPSHOT_SERIAL",
        "recon.sources[1].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({FixedRunClock.class, HeldStageAStore.Injection.class})
@DisplayName("TDD 5.3: every statement of a run reads the snapshot its work transaction took")
class RunSnapshotTest {

    private static final SourceCode UNSEEN = SourceCode.of("PSP_SNAPSHOT_UNSEEN");
    private static final SourceCode SERIAL = SourceCode.of("PSP_SNAPSHOT_SERIAL");
    /** Event ids no other test class uses; the shared database outlives each class. */
    private static final long FIRST_EVENT_ID = 9_850_000_000L;

    /** A working week whose items are all past both grace periods on Monday 2026-10-12. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private StageAStore stageA;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private StatementStore statements;

    @Autowired
    private BreakStore breaks;

    @Autowired
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("TDD 5.3: an entry and a line committed while a run works get no match, no grace break and no "
            + "count from it; the next run matches them")
    void itemsCommittedDuringTheRunAreSeenByNoneOfItsStatements() throws Exception {
        StageAFixture fixture = fixture(0);
        UUID before = UUID.randomUUID();
        UUID during = UUID.randomUUID();
        String entryBefore = fixture.ledger(UNSEEN, before, 500, "TRY", MONDAY);
        fixture.psp(UNSEEN, "L-BEFORE", before.toString(), 500, "TRY", MONDAY);
        String[] entryDuring = new String[1];

        ReconciliationRun held = HeldStageAStore.of(stageA).runHeldWhile(UNSEEN, () -> run(UNSEEN), () -> {
            entryDuring[0] = fixture.ledger(UNSEEN, during, 1_000, "TRY", MONDAY);
            fixture.psp(UNSEEN, "L-DURING", during.toString(), 1_000, "TRY", MONDAY);
        });

        assertThat(held.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.matches(UNSEEN)).extracting(MatchView::ledger, MatchView::psp, MatchView::runId)
                .containsExactly(tuple(entryBefore, "PSP L-BEFORE", held.id()));
        assertThat(fixture.breaks(UNSEEN)).as("the grace statement did not see the items past their grace").isEmpty();
        assertThat(held.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "ledger.TRY.matched.count", 1L, "ledger.TRY.pending.count", 0L, "ledger.TRY.broken.count", 0L,
                "psp.TRY.matched.count", 1L, "psp.TRY.pending.count", 0L, "psp.TRY.broken.count", 0L,
                ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 1L)));

        ReconciliationRun next = run(UNSEEN);

        assertThat(fixture.matches(UNSEEN)).extracting(MatchView::ledger, MatchView::psp, MatchView::runId)
                .containsExactlyInAnyOrder(
                        tuple(entryBefore, "PSP L-BEFORE", held.id()),
                        tuple(entryDuring[0], "PSP L-DURING", next.id()));
        assertThat(fixture.breaks(UNSEEN)).isEmpty();
    }

    @Test
    @DisplayName("TDD 5.3: a break an operator changes while a run works fails the run with a serialization failure "
            + "when MATCHED_LATE would resolve it; nothing of the run is kept, and the next run resolves it")
    void serializationFailureFailsTheRun() throws Exception {
        StageAFixture fixture = fixture(1);
        UUID transaction = UUID.randomUUID();
        fixture.psp(SERIAL, "L-001", transaction.toString(), 800, "TRY", FRIDAY);
        UUID missing = fixture.openBreak(breaks, SERIAL, "L-001", BreakType.MISSING_IN_LEDGER);
        String entry = fixture.ledger(SERIAL, transaction, 800, "TRY", FRIDAY);

        assertThatThrownBy(() -> HeldStageAStore.of(stageA).runHeldWhile(SERIAL, () -> run(SERIAL),
                () -> fixture.investigate(breaks, missing)))
                .isInstanceOf(ConcurrencyFailureException.class)
                .hasMessageContaining("could not serialize access due to concurrent update");

        assertThat(jdbc.sql("SELECT status FROM reconciliation_runs WHERE source_code = :source")
                .param("source", SERIAL.value()).query(String.class).list()).containsExactly("FAILED");
        assertThat(fixture.matches(SERIAL)).as("A1's match was rolled back").isEmpty();
        assertThat(fixture.eventsOf(missing)).containsExactly("->OPEN:-:operator-001", "OPEN>INVESTIGATING:-:operator-001");

        ReconciliationRun next = run(SERIAL);

        assertThat(next.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.matches(SERIAL)).extracting(MatchView::ledger, MatchView::psp)
                .containsExactly(tuple(entry, "PSP L-001"));
        assertThat(fixture.breaks(SERIAL)).extracting(BreakView::id, BreakView::status, BreakView::resolutionCode)
                .containsExactly(tuple(missing, "RESOLVED", Optional.of("MATCHED_LATE")));
    }

    private ReconciliationRun run(SourceCode source) {
        return matching.run(source.value(), MONDAY, FRIDAY, "operator-001");
    }

    private StageAFixture fixture(int block) {
        return new StageAFixture(ledgerEntries, statements, transactions, jdbc, FIRST_EVENT_ID + 1_000L * block);
    }
}
