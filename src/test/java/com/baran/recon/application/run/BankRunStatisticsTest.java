package com.baran.recon.application.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.ItemTotals;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StageBStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageBFixture.Bank;
import com.baran.recon.application.run.StageBFixture.Psp;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.BatchFigures;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.breaks.ResolutionCode;
import com.baran.recon.domain.breaks.Transition;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ItemStatistics;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.domain.run.ScopeNotConservedException;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.run.StageBFixture.bank;
import static com.baran.recon.application.run.StageBFixture.batch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * A bank source's run (TDD 5.3, 8.2): its bank lines dated in the range and the batches, of every PSP
 * source that settles to it, whose latest line is dated in the range, each MATCHED, BROKEN or PENDING
 * in Stage A's order, and counted per side and currency through the run's finalization check (INV-1,
 * INV-4). The main test builds its matches and breaks directly, as Stage B's rules write them, and
 * counts them straight from the store with the check the run applies, so the rules leave its states
 * as built; {@link StageBMatchingTest} covers what the rules write. Runs are at
 * {@link FixedRunClock#NOW}, over the working week before it. Each test has sources of its own; the
 * database is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_STATS_ALPHA",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].settles-to=BANK_STATS",
        "recon.sources[1].code=PSP_STATS_BETA",
        "recon.sources[1].type=PSP_SETTLEMENT",
        "recon.sources[1].settles-to=BANK_STATS",
        "recon.sources[2].code=BANK_STATS",
        "recon.sources[2].type=BANK_STATEMENT",
        "recon.sources[2].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[3].code=PSP_STATS_ELSEWHERE",
        "recon.sources[3].type=PSP_SETTLEMENT",
        "recon.sources[3].settles-to=BANK_STATS_ELSEWHERE",
        "recon.sources[4].code=BANK_STATS_ELSEWHERE",
        "recon.sources[4].type=BANK_STATEMENT",
        "recon.sources[4].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[5].code=BANK_STATS_ALONE",
        "recon.sources[5].type=BANK_STATEMENT",
        "recon.sources[5].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[6].code=PSP_STATS_HUGE",
        "recon.sources[6].type=PSP_SETTLEMENT",
        "recon.sources[6].settles-to=BANK_STATS_HUGE",
        "recon.sources[7].code=BANK_STATS_HUGE",
        "recon.sources[7].type=BANK_STATEMENT",
        "recon.sources[7].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[8].code=PSP_STATS_MISCOUNT",
        "recon.sources[8].type=PSP_SETTLEMENT",
        "recon.sources[8].settles-to=BANK_STATS_MISCOUNT",
        "recon.sources[9].code=BANK_STATS_MISCOUNT",
        "recon.sources[9].type=BANK_STATEMENT",
        "recon.sources[9].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[10].code=PSP_STATS_DATED",
        "recon.sources[10].type=PSP_SETTLEMENT",
        "recon.sources[10].settles-to=BANK_STATS_DATED",
        "recon.sources[11].code=BANK_STATS_DATED",
        "recon.sources[11].type=BANK_STATEMENT",
        "recon.sources[11].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        // A batch grace no batch of the dating test reaches, so its batch stays PENDING: grace is
        // StageBOverTimeTest's (TDD 8.2), and this test counts where a batch is dated.
        "recon.sources[11].grace-days-batch-unpaid=30"})
@ActiveProfiles("test")
@Import({FixedRunClock.class, MiscountingStageBStore.Injection.class})
@DisplayName("TDD 8.2, INV-1, INV-4: a bank run's lines and batches, each by status, conserved per currency")
class BankRunStatisticsTest {

    private static final SourceCode ALPHA = SourceCode.of("PSP_STATS_ALPHA");
    private static final SourceCode BETA = SourceCode.of("PSP_STATS_BETA");
    private static final SourceCode BANK = SourceCode.of("BANK_STATS");
    private static final SourceCode ELSEWHERE = SourceCode.of("PSP_STATS_ELSEWHERE");
    private static final SourceCode BANK_ELSEWHERE = SourceCode.of("BANK_STATS_ELSEWHERE");
    private static final SourceCode ALONE = SourceCode.of("BANK_STATS_ALONE");
    private static final SourceCode HUGE = SourceCode.of("PSP_STATS_HUGE");
    private static final SourceCode BANK_HUGE = SourceCode.of("BANK_STATS_HUGE");
    private static final SourceCode MISCOUNT = SourceCode.of("PSP_STATS_MISCOUNT");
    private static final SourceCode BANK_MISCOUNT = SourceCode.of("BANK_STATS_MISCOUNT");
    private static final SourceCode DATED = SourceCode.of("PSP_STATS_DATED");
    private static final SourceCode BANK_DATED = SourceCode.of("BANK_STATS_DATED");

    private static final LocalDate FRIDAY_BEFORE = LocalDate.of(2026, 10, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate MONDAY_AFTER = LocalDate.of(2026, 10, 12);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private StatementStore statements;

    @Autowired
    private MatchStore matches;

    @Autowired
    private BreakStore breaks;

    @Autowired
    private RunStore runs;

    @Autowired
    private StageBStore stageB;

    @Autowired
    private Transactions transactions;

    @Test
    @DisplayName("INV-1, INV-4 (TDD 8.2): each bank line and batch in scope is MATCHED, BROKEN or PENDING in Stage A's "
            + "order and counted once per currency: a batch over two files, a mixed-currency batch, a refund batch, "
            + "and the same batch id from two PSP sources; only breaks about a BANK or BATCH item make one BROKEN. "
            + "Counted straight from the store, as the run's finalization check counts them")
    void bankRunCountsEachStatusPerCurrency() {
        StageBFixture fixture = fixture();
        fixture.pspFile(ALPHA, List.of(
                new Psp("A-M-1", "B-M", 600, 100, "TRY", FRIDAY_BEFORE),
                new Psp("A-N-1", "B-N", 2_100, 100, "TRY", TUESDAY),
                new Psp("A-X-1", "B-X", 3_000, 0, "TRY", WEDNESDAY),
                new Psp("A-LATE-1", "B-LATE", 900, 0, "TRY", THURSDAY),
                new Psp("A-EARLY-1", "B-EARLY", 900, 0, "TRY", FRIDAY_BEFORE)));
        fixture.pspFile(ALPHA, List.of(
                new Psp("A-M-2", "B-M", 500, 0, "TRY", MONDAY),
                new Psp("A-P-1", "B-P", 1_000, 0, "TRY", THURSDAY),
                new Psp("A-P-2", "B-P", 250, 50, "EUR", THURSDAY),
                new Psp("A-P-3", "B-P", 300, 0, "EUR", FRIDAY),
                new Psp("A-M2-1", "B-M2", 800, 0, "TRY", FRIDAY),
                new Psp("A-Q-1", "B-Q", 600, 0, "TRY", TUESDAY),
                new Psp("A-R-1", "B-R", -400, 0, "TRY", WEDNESDAY)));
        fixture.pspFile(ALPHA, List.of(new Psp("A-LATE-2", "B-LATE", 100, 0, "TRY", MONDAY_AFTER)));
        fixture.pspFile(BETA, List.of(new Psp("B-P-1", "B-P", 400, 0, "TRY", FRIDAY)));
        fixture.pspFile(ELSEWHERE, List.of(new Psp("E-1", "B-E", 700, 0, "TRY", WEDNESDAY)));
        Map<String, UUID> line = fixture.bankFile(BANK, List.of(
                new Bank("S-1", 1_000, "TRY", MONDAY, Optional.of("B-M")),
                new Bank("S-2", 2_000, "TRY", TUESDAY, Optional.empty()),
                new Bank("S-3", 3_000, "TRY", WEDNESDAY, Optional.of("B-N")),
                new Bank("S-4", 4_000, "TRY", WEDNESDAY, Optional.of("B-N")),
                new Bank("S-5", 500, "EUR", THURSDAY, Optional.empty()),
                new Bank("S-6", 999, "TRY", FRIDAY_BEFORE, Optional.empty()),
                new Bank("S-7", 700, "TRY", FRIDAY, Optional.empty()),
                new Bank("S-8", 800, "TRY", FRIDAY, Optional.of("B-M2")),
                new Bank("S-9", 50, "TRY", THURSDAY, Optional.empty())));
        fixture.bankFile(BANK_ELSEWHERE, List.of(new Bank("S-E", 700, "TRY", WEDNESDAY, Optional.of("B-E"))));

        UUID earlier = fixture.completedRun(BANK);
        fixture.matchBatch(earlier, batch(ALPHA, "B-M"), bank(line.get("S-1")), "TRY", 2);
        fixture.matchBatch(earlier, batch(ALPHA, "B-M2"), bank(line.get("S-8")), "TRY", 1);
        // Matched beats broken (TDD 8.2): S-8 keeps an open break, and is still MATCHED.
        fixture.openBreak(BreakType.UNEXPECTED_BANK_LINE, bank(line.get("S-8")), List.of());
        fixture.openBreak(BreakType.UNEXPECTED_BANK_LINE, bank(line.get("S-2")), List.of());
        fixture.openBreak(BreakType.DUPLICATE_LINE, bank(line.get("S-4")),
                List.of(bank(line.get("S-3")), batch(ALPHA, "B-N")));
        fixture.openBreak(BreakType.BATCH_AMOUNT_MISMATCH, batch(ALPHA, "B-X"), List.of(),
                Optional.of(new BreakDetails.BatchChangedAfterMatch(UUID.randomUUID(),
                        new BatchFigures(1, List.of(Money.of(2_500, CurrencyCode.of("TRY")))),
                        new BatchFigures(1, List.of(Money.of(3_000, CurrencyCode.of("TRY")))))));
        // A break about a PSP line naming S-7 and B-Q is Stage A's, and leaves both PENDING (v1.15).
        fixture.openBreak(BreakType.AMOUNT_MISMATCH, new ItemRef(ItemSide.PSP, UUID.randomUUID()),
                List.of(bank(line.get("S-7")), batch(ALPHA, "B-Q")));
        resolvedBreakOn(bank(line.get("S-9")));

        ItemTotals totals = stageB.itemTotals(BANK, List.of(ALPHA, BETA), MONDAY, FRIDAY);

        assertThat(ItemStatistics.conserved(totals.byStatus(), totals.inScope())).containsOnly(
                entry("bank.TRY.matched.count", 2L), entry("bank.TRY.matched.sum", 1_800L),
                entry("bank.TRY.broken.count", 3L), entry("bank.TRY.broken.sum", 9_000L),
                entry("bank.TRY.pending.count", 2L), entry("bank.TRY.pending.sum", 750L),
                entry("bank.EUR.matched.count", 0L), entry("bank.EUR.matched.sum", 0L),
                entry("bank.EUR.broken.count", 0L), entry("bank.EUR.broken.sum", 0L),
                entry("bank.EUR.pending.count", 1L), entry("bank.EUR.pending.sum", 500L),
                entry("batch.TRY.matched.count", 2L), entry("batch.TRY.matched.sum", 1_800L),
                entry("batch.TRY.broken.count", 2L), entry("batch.TRY.broken.sum", 5_000L),
                entry("batch.TRY.pending.count", 4L), entry("batch.TRY.pending.sum", 1_600L),
                entry("batch.EUR.matched.count", 0L), entry("batch.EUR.matched.sum", 0L),
                entry("batch.EUR.broken.count", 0L), entry("batch.EUR.broken.sum", 0L),
                entry("batch.EUR.pending.count", 1L), entry("batch.EUR.pending.sum", 500L));
    }

    @Test
    @DisplayName("TDD 8.2: a batch is dated by its latest line, so a line arriving after the range moves it out; a "
            + "line arriving inside it brings it in")
    void batchDateIsItsLatestLine() {
        StageBFixture fixture = fixture();
        fixture.pspFile(DATED, List.of(new Psp("D-1", "B-DATED", 300, 0, "TRY", FRIDAY_BEFORE)));

        assertThat(batchStatistics(MONDAY, FRIDAY)).as("dated before the range").isEmpty();
        assertThat(batchStatistics(FRIDAY_BEFORE, FRIDAY_BEFORE))
                .contains(entry("batch.TRY.pending.count", 1L), entry("batch.TRY.pending.sum", 300L));

        fixture.pspFile(DATED, List.of(new Psp("D-2", "B-DATED", 200, 0, "TRY", THURSDAY)));

        assertThat(batchStatistics(MONDAY, FRIDAY)).as("its earlier line still counts in its sum")
                .contains(entry("batch.TRY.pending.count", 1L), entry("batch.TRY.pending.sum", 500L));
        assertThat(batchStatistics(FRIDAY_BEFORE, FRIDAY_BEFORE)).as("no longer dated by its first line").isEmpty();
    }

    @Test
    @DisplayName("TDD 8.1 (v1.15), FR-MAT-8 (v1.17): a bank source no PSP source settles to snapshots none, counts its "
            + "bank lines and no batch, and each line it carries is UNEXPECTED_BANK_LINE")
    void bankSourceNothingSettlesToCountsItsLinesOnly() {
        fixture().bankFile(ALONE, List.of(new Bank("S-ALONE-1", 1_250, "TRY", TUESDAY, Optional.of("B-NONE"))));

        ReconciliationRun completed = matching.run(ALONE.value(), MONDAY, FRIDAY, "operator-001");

        assertThat(completed.configSnapshot()).containsEntry(RunMatching.SETTLING_PSP_SOURCES, "");

        assertThat(completed.stats()).hasValueSatisfying(stats -> assertThat(stats).containsOnly(
                entry("bank.TRY.matched.count", 0L), entry("bank.TRY.matched.sum", 0L),
                entry("bank.TRY.broken.count", 1L), entry("bank.TRY.broken.sum", 1_250L),
                entry("bank.TRY.pending.count", 0L), entry("bank.TRY.pending.sum", 0L),
                entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 0L),
                entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L)));
    }

    @Test
    @DisplayName("TDD 5.3: a PSP source's run does Stage A only; its statistics hold no bank line and no batch")
    void pspRunCountsNoBankLineOrBatch() {
        fixture().pspFile(ELSEWHERE, List.of(new Psp("E-PSP-1", "B-E-PSP", 300, 0, "TRY", TUESDAY)));

        ReconciliationRun completed = matching.run(ELSEWHERE.value(), MONDAY, FRIDAY, "operator-001");

        assertThat(completed.stats()).hasValueSatisfying(stats -> assertThat(stats.keySet())
                .contains("psp.TRY.pending.count", "psp.TRY.broken.count")
                .noneMatch(key -> key.startsWith("bank.") || key.startsWith("batch.")));
    }

    @Test
    @DisplayName("INV-8 (TDD 8.2): a batch whose net sum does not fit a BIGINT fails the run, and nothing is kept")
    void batchSumPastBigintFailsTheRun() {
        fixture().pspFile(HUGE, List.of(
                new Psp("H-1", "B-HUGE", 5_000_000_000_000_000_000L, 0, "TRY", TUESDAY),
                new Psp("H-2", "B-HUGE", 5_000_000_000_000_000_000L, 0, "TRY", TUESDAY)));

        assertThatThrownBy(() -> matching.run(BANK_HUGE.value(), MONDAY, FRIDAY, "operator-001"))
                .isInstanceOfSatisfying(RunFailedException.class, failed -> assertThat(runs.findById(failed.runId()))
                        .hasValueSatisfying(run -> {
                            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                            assertThat(run.stats()).isEmpty();
                        }))
                .rootCause().hasMessageContaining("bigint out of range");
    }

    @Test
    @DisplayName("break proof, INV-1/INV-4 (TDD 9.1): a bank run whose bank lines or batches by status miscount the "
            + "scope by one item fails, keeping no statistics; the same run counted right completes, its line and "
            + "batch matched by B1")
    void miscountedBankRunFails() {
        StageBFixture fixture = fixture();
        fixture.pspFile(MISCOUNT, List.of(new Psp("M-1", "B-MISCOUNT", 900, 0, "TRY", TUESDAY)));
        fixture.bankFile(BANK_MISCOUNT, List.of(new Bank("S-MISCOUNT-1", 900, "TRY", TUESDAY, Optional.of("B-MISCOUNT"))));

        for (ItemSide side : List.of(ItemSide.BANK, ItemSide.BATCH)) {
            MiscountingStageBStore.of(stageB).countOneTooManyInTheNextRunOf(BANK_MISCOUNT, side);
            assertThatThrownBy(() -> matching.run(BANK_MISCOUNT.value(), MONDAY, FRIDAY, "operator-001"))
                    .as(side.name())
                    .isInstanceOfSatisfying(RunFailedException.class, failed -> assertThat(runs.findById(failed.runId()))
                            .hasValueSatisfying(run -> assertThat(run.stats()).isEmpty()))
                    .cause().isInstanceOf(ScopeNotConservedException.class).hasMessageContaining(side + " TRY");
        }

        ReconciliationRun counted = matching.run(BANK_MISCOUNT.value(), MONDAY, FRIDAY, "operator-001");
        assertThat(counted.stats()).hasValueSatisfying(stats -> assertThat(stats)
                .containsEntry("bank.TRY.matched.count", 1L).containsEntry("batch.TRY.matched.count", 1L));
    }

    /** The batch statistics of a run of the dated bank source over the range. */
    private Map<String, Long> batchStatistics(LocalDate from, LocalDate to) {
        return matching.run(BANK_DATED.value(), from, to, "operator-001").stats().orElseThrow().entrySet().stream()
                .filter(stat -> stat.getKey().startsWith("batch."))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** A break on the item that an operator has resolved: it no longer makes the item BROKEN. */
    private void resolvedBreakOn(ItemRef item) {
        Instant at = Instant.parse("2026-10-12T08:00:00Z");
        Transition opened = Break.open(UUID.randomUUID(), BreakType.UNEXPECTED_BANK_LINE, item, List.of(),
                Optional.empty(), Actor.SYSTEM, Optional.empty(), at);
        breaks.open(opened);
        breaks.apply(opened.result().resolve(ResolutionCode.FALSE_POSITIVE, "A fee the bank books monthly",
                Actor.operator("operator-001"), at.plusSeconds(60)));
    }

    private StageBFixture fixture() {
        return new StageBFixture(statements, matches, breaks, runs, transactions);
    }
}
