package com.baran.recon.application.run;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageBFixture.Bank;
import com.baran.recon.application.run.StageBFixture.Psp;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.Match;
import com.baran.recon.domain.match.MatchItem;
import com.baran.recon.domain.match.MatchStatus;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.run.StageBFixture.bank;
import static com.baran.recon.application.run.StageBFixture.batch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Stage B's rules and details (TDD 8.2) through the run use case, against the application's own
 * database as recon_app. Every test takes a bank source of its own, with two PSP sources settling to
 * it, so tests never see each other's items in the shared database. One more PSP source, shared,
 * settles to another bank; a test that stores lines in it uses batch ids of its own. Runs are at
 * {@link FixedRunClock#NOW}, Monday 2026-10-12, over the working week before it.
 *
 * <p>Matches and breaks are read back through the application's own stores, so what the rules
 * write is checked against the domain's shapes: a {@link Match} of one BATCH and one BANK item with
 * the batch's line count, a break with the details its type requires.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(FixedRunClock.class)
@DisplayName("TDD 8.2: Stage B matches batches and bank lines by its rules, in their precedence")
class StageBMatchingTest {

    /** Source codes no other test class uses; the shared database outlives each class. */
    private static final String PREFIX = "STAGE_B_";
    private static final int BANKS = 40;
    private static final AtomicInteger NEXT_BANK = new AtomicInteger();
    /** A PSP source settling to a bank no test here runs (TDD 8.1). */
    private static final SourceCode ELSEWHERE = SourceCode.of("PSP_" + PREFIX + "ELSEWHERE");

    // A week with a weekend after it: 2026-10-05 is a Monday.
    private static final LocalDate FRIDAY_BEFORE = LocalDate.of(2026, 10, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate MONDAY_AFTER = LocalDate.of(2026, 10, 12);

    private static final String TRY = "TRY";
    private static final String EUR = "EUR";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        int index = 0;
        for (int i = 0; i < BANKS; i++) {
            String bank = bankCode(i);
            index = pspSource(registry, index, alphaCode(i), bank);
            index = pspSource(registry, index, betaCode(i), bank);
            index = bankSource(registry, index, bank);
        }
        String other = "BANK_" + PREFIX + "ELSEWHERE";
        index = pspSource(registry, index, ELSEWHERE.value(), other);
        bankSource(registry, index, other);
    }

    private static int pspSource(DynamicPropertyRegistry registry, int index, String code, String bank) {
        String source = "recon.sources[" + index + "].";
        registry.add(source + "code", () -> code);
        registry.add(source + "type", () -> "PSP_SETTLEMENT");
        registry.add(source + "settles-to", () -> bank);
        return index + 1;
    }

    private static int bankSource(DynamicPropertyRegistry registry, int index, String code) {
        String source = "recon.sources[" + index + "].";
        registry.add(source + "code", () -> code);
        registry.add(source + "type", () -> "BANK_STATEMENT");
        registry.add(source + "batch-id-pattern", () -> "BATCH[-_]?([A-Za-z0-9_-]{1,64})");
        return index + 1;
    }

    private static String bankCode(int i) {
        return String.format("BANK_%s%03d", PREFIX, i);
    }

    private static String alphaCode(int i) {
        return String.format("PSP_%s%03d_ALPHA", PREFIX, i);
    }

    private static String betaCode(int i) {
        return String.format("PSP_%s%03d_BETA", PREFIX, i);
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
    private Transactions transactions;

    @Autowired
    private JdbcClient jdbc;

    private StageBFixture fixture;
    private SourceCode bankSource;
    private SourceCode alpha;
    private SourceCode beta;

    @BeforeEach
    void takeABankSourceOfItsOwn() {
        int i = NEXT_BANK.getAndIncrement();
        bankSource = SourceCode.of(bankCode(i));
        alpha = SourceCode.of(alphaCode(i));
        beta = SourceCode.of(betaCode(i));
        fixture = new StageBFixture(statements, matches, breaks, runs, transactions);
    }

    @Test
    @DisplayName("FR-MAT-6, B1_BATCH_TOTAL: a bank line whose amount is its batch's net sum is matched to the batch, "
            + "recorded in full: one BATCH and one BANK item, the batch's line count, no amount difference")
    void batchTotalMatchesItsBankLine() {
        fixture.pspFile(alpha, List.of(
                new Psp("A-1", "B-1", 10_000, 150, TRY, TUESDAY),
                new Psp("A-2", "B-1", 2_500, 50, TRY, WEDNESDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 12_300, TRY, THURSDAY, "B-1")));

        ReconciliationRun run = run();

        assertThat(matchesOfTheBank()).containsExactly(Match.activeBatch(onlyMatchId(), run.id(), RuleId.B1_BATCH_TOTAL,
                1, Money.zero(CurrencyCode.of(TRY)), FixedRunClock.NOW,
                List.of(item(batch(alpha, "B-1")), item(bank(line.get("S-1")))), 2));
        assertThat(createdEventsOf(onlyMatchId())).containsExactly("CREATED:system");
        assertThat(run.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "bank.TRY.matched.count", 1L, "bank.TRY.matched.sum", 12_300L,
                "batch.TRY.matched.count", 1L, "batch.TRY.matched.sum", 12_300L)));
    }

    @Test
    @DisplayName("B1_BATCH_TOTAL (TDD 8.2): a batch whose refunds exceed its payments has a negative net, and a negative "
            + "bank line matches it")
    void refundBatchMatchesANegativeBankLine() {
        fixture.pspFile(alpha, List.of(
                new Psp("A-1", "B-REFUND", 500, 20, TRY, TUESDAY),
                new Psp("A-2", "B-REFUND", -900, 0, TRY, TUESDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", -420, TRY, WEDNESDAY, "B-REFUND")));

        run();

        assertThat(matchesOfTheBank()).extracting(Match::rule, Match::items, Match::batchLineCount)
                .containsExactly(tuple(RuleId.B1_BATCH_TOTAL,
                        List.of(item(bank(line.get("S-1"))), item(batch(alpha, "B-REFUND"))), OptionalInt.of(2)));
    }

    @Test
    @DisplayName("B1_BATCH_TOTAL (TDD 8.2): a batch is all its source's lines with its id, whatever file they came from")
    void batchWhoseLinesSpanTwoFilesIsSummedWhole() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-SPLIT", 4_000, 0, TRY, MONDAY)));
        fixture.pspFile(alpha, List.of(
                new Psp("A-2", "B-SPLIT", 3_000, 0, TRY, TUESDAY),
                new Psp("A-3", "B-SPLIT", 1_000, 100, TRY, TUESDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 7_900, TRY, WEDNESDAY, "B-SPLIT")));

        run();

        assertThat(matchesOfTheBank()).extracting(Match::items, Match::batchLineCount).containsExactly(
                tuple(List.of(item(bank(line.get("S-1"))), item(batch(alpha, "B-SPLIT"))), OptionalInt.of(3)));
    }

    @Test
    @DisplayName("B1_BATCH_TOTAL (TDD 8.2): a bank line outside the run's range is matched to a batch in it, and a batch "
            + "outside the range to a bank line in it; with neither in the range, nothing is matched")
    void eitherSideInTheRangeIsEnough() {
        fixture.pspFile(alpha, List.of(
                new Psp("A-1", "B-IN", 1_000, 0, TRY, WEDNESDAY),
                new Psp("A-2", "B-BEFORE", 2_000, 0, TRY, FRIDAY_BEFORE),
                new Psp("A-3", "B-AFTER", 3_000, 0, TRY, MONDAY_AFTER)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(
                bankLine("S-AFTER", 1_000, TRY, MONDAY_AFTER, "B-IN"),
                bankLine("S-IN", 2_000, TRY, TUESDAY, "B-BEFORE"),
                bankLine("S-LATER", 3_000, TRY, MONDAY_AFTER.plusDays(1), "B-AFTER")));

        run();

        assertThat(matchesOfTheBank()).extracting(Match::items).containsExactlyInAnyOrder(
                List.of(item(bank(line.get("S-AFTER"))), item(batch(alpha, "B-IN"))),
                List.of(item(bank(line.get("S-IN"))), item(batch(alpha, "B-BEFORE"))));
    }

    @Test
    @DisplayName("TDD 8.2: batch ids compare exactly, case-sensitive")
    void batchIdsCompareExactly() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-case", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, TRY, TUESDAY, "B-CASE")));

        run();

        assertThat(matchesOfTheBank()).isEmpty();
    }

    @Test
    @DisplayName("B1_BATCH_TOTAL keeps a match exact (TDD 8.2): another amount, another currency, or a batch with lines "
            + "in two currencies is not matched, even when the line's currency alone sums to the line's amount")
    void conflictingTotalsAreNotMatched() {
        fixture.pspFile(alpha, List.of(
                new Psp("A-1", "B-AMOUNT", 1_000, 0, TRY, TUESDAY),
                new Psp("A-2", "B-CURRENCY", 2_000, 0, EUR, TUESDAY),
                new Psp("A-3", "B-MIXED", 3_000, 0, TRY, TUESDAY),
                new Psp("A-4", "B-MIXED", 500, 0, EUR, TUESDAY)));
        fixture.bankFile(bankSource, List.of(
                bankLine("S-AMOUNT", 1_001, TRY, TUESDAY, "B-AMOUNT"),
                bankLine("S-CURRENCY", 2_000, TRY, TUESDAY, "B-CURRENCY"),
                bankLine("S-MIXED", 3_000, TRY, TUESDAY, "B-MIXED")));

        run();

        assertThat(matchesOfTheBank()).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-4 (TDD 8.2): a batch id naming batches of two settling PSP sources is matched to neither, "
            + "though one of them has the line's amount")
    void batchIdOfTwoSettlingSourcesIsNotMatched() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-TWICE", 1_000, 0, TRY, TUESDAY)));
        fixture.pspFile(beta, List.of(new Psp("B-1", "B-TWICE", 900, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, TRY, WEDNESDAY, "B-TWICE")));

        run();

        assertThat(matchesOfTheBank()).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-4 (TDD 8.2): two unmatched bank lines naming one batch are neither matched, though one has the "
            + "batch's sum and the other lies outside the range")
    void twoBankLinesNamingOneBatchAreNotMatched() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-CLAIMED", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(
                bankLine("S-1", 1_000, TRY, WEDNESDAY, "B-CLAIMED"),
                bankLine("S-LATER", 900, TRY, MONDAY_AFTER, "B-CLAIMED")));

        run();

        assertThat(matchesOfTheBank()).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-2 (TDD 8.2): a batch already matched is not matched again, and its match is kept as it was")
    void matchedBatchIsNotMatchedAgain() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-PAID", 1_000, 0, TRY, TUESDAY)));
        Map<String, UUID> first = fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, TRY, WEDNESDAY, "B-PAID")));
        ReconciliationRun earlier = run();
        List<Match> before = matchesOfTheBank();
        fixture.bankFile(bankSource, List.of(bankLine("S-2", 1_000, TRY, THURSDAY, "B-PAID")));

        run();

        assertThat(matchesOfTheBank()).isEqualTo(before).extracting(Match::runId, Match::items).containsExactly(
                tuple(earlier.id(), List.of(item(bank(first.get("S-1"))), item(batch(alpha, "B-PAID")))));
    }

    @Test
    @DisplayName("TDD 8.1: a batch of a PSP source settling to another bank is no candidate for this bank's lines")
    void batchOfAPspSourceSettlingElsewhereIsNoCandidate() {
        String batchId = "B-ELSEWHERE-" + bankSource.value();
        fixture.pspFile(ELSEWHERE, List.of(new Psp("E-" + bankSource.value(), batchId, 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, TRY, WEDNESDAY, batchId)));

        run();

        assertThat(matchesOfTheBank()).isEmpty();
    }

    private ReconciliationRun run() {
        return matching.run(bankSource.value(), MONDAY, FRIDAY, "operator-001");
    }

    private static Bank bankLine(String lineId, long amount, String currency, LocalDate valueDate, String batchId) {
        return new Bank(lineId, amount, currency, valueDate, Optional.of(batchId));
    }

    private static MatchItem item(ItemRef ref) {
        return new MatchItem(ref.side(), ref.id());
    }

    /** The matches holding a bank line of this test's bank source, read back through the match store. */
    private List<Match> matchesOfTheBank() {
        return matchIds().stream().map(id -> matches.findById(id).orElseThrow()).toList();
    }

    private UUID onlyMatchId() {
        List<UUID> ids = matchIds();
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private List<UUID> matchIds() {
        return jdbc.sql("""
                        SELECT item.match_id
                          FROM match_items item
                          JOIN bank_lines line ON line.id = item.item_id
                         WHERE item.side = 'BANK' AND line.source_code = :bank
                         ORDER BY line.line_id
                        """)
                .param("bank", bankSource.value()).query(UUID.class).list();
    }

    /** A match's creation events, as event:actor. */
    private List<String> createdEventsOf(UUID matchId) {
        return jdbc.sql("SELECT event_type || ':' || actor FROM match_events WHERE match_id = :matchId ORDER BY id")
                .param("matchId", matchId).query(String.class).list();
    }
}
