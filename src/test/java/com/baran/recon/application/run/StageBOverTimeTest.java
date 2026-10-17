package com.baran.recon.application.run;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.breaks.BatchFigures;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.breaks.BreakStatus;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.breaks.ResolutionCode;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.run.StageBFixture.bank;
import static com.baran.recon.application.run.StageBFixture.batch;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to Stage B over time (TDD 8.2): the breaks a later match answers, a batch that changes
 * after it was matched, and a batch left unpaid past its grace period. Through the run use case,
 * against the application's own database as recon_app. Every test takes a bank source of its own,
 * with two PSP sources settling to it, so tests never see each other's items in the shared database.
 * Runs are at {@link FixedRunClock#NOW}, Monday 2026-10-12, over the working week before it.
 *
 * <p>Where no rule can reach a state before Milestone 7's reversal or a change of a PSP source's
 * settles-to, the test builds it through the application's own stores, as the rules would write it,
 * and says so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(FixedRunClock.class)
@DisplayName("TDD 8.2: Stage B over time - late matches, late lines and unpaid batches")
class StageBOverTimeTest {

    /** Source codes no other test class uses; the shared database outlives each class. */
    private static final String PREFIX = "OVER_TIME_";
    private static final int BANKS = 30;
    private static final AtomicInteger NEXT_BANK = new AtomicInteger();

    // The working week before the run: 2026-10-05 is a Monday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate MONDAY_AFTER = LocalDate.of(2026, 10, 12);

    private static final String TRY = "TRY";

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
    @DisplayName("FR-BRK-5 (TDD 8.2): a B1 match resolves the UNEXPECTED_BANK_LINE its bank line got before its batch "
            + "arrived, as MATCHED_LATE by the system, with its event; a re-run writes nothing more")
    void matchResolvesAnUnexpectedBankLine() {
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-1")));
        run();
        Break unexpected = onlyBreakAbout(bank(line.get("S-1")));
        assertThat(unexpected.type()).isEqualTo(BreakType.UNEXPECTED_BANK_LINE);

        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-1", 1_000, 0, TRY, THURSDAY)));
        ReconciliationRun matched = run();

        assertThat(matchedPairs()).containsExactly("S-1");
        assertResolvedMatchedLate(unexpected.id(), matched, "OPEN");
        assertThat(matched.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "bank.TRY.matched.count", 1L, "bank.TRY.broken.count", 0L, "batch.TRY.matched.count", 1L)));

        List<String> eventsBefore = breakEventsOfTheBank();
        run();
        assertThat(breakEventsOfTheBank()).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("FR-BRK-5 (TDD 8.2): a B1 match resolves the BATCH_AMOUNT_MISMATCH B2 opened on its bank line while its "
            + "batch was incomplete, as MATCHED_LATE")
    void matchResolvesABatchConflictOnItsBankLine() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-2", 600, 0, TRY, TUESDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-2")));
        run();
        Break conflict = onlyBreakAbout(bank(line.get("S-1")));
        assertThat(conflict.type()).isEqualTo(BreakType.BATCH_AMOUNT_MISMATCH);

        fixture.pspFile(alpha, List.of(new Psp("A-2", "B-2", 400, 0, TRY, THURSDAY)));
        ReconciliationRun matched = run();

        assertThat(matchedPairs()).containsExactly("S-1");
        assertResolvedMatchedLate(conflict.id(), matched, "OPEN");
    }

    @Test
    @DisplayName("FR-BRK-5 (TDD 8.2): a B1 match resolves an AMBIGUOUS_MATCH on its bank line, as MATCHED_LATE (built "
            + "through the stores: only a change of settles-to can leave one batch where there were two)")
    void matchResolvesAnAmbiguousMatchOnItsBankLine() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-3", 700, 0, TRY, THURSDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 700, WEDNESDAY, "B-3")));
        UUID ambiguous = openBreak(BreakType.AMBIGUOUS_MATCH, bank(line.get("S-1")),
                List.of(batch(alpha, "B-3"), batch(beta, "B-3")), Optional.empty());

        ReconciliationRun matched = run();

        assertThat(matchedPairs()).containsExactly("S-1");
        assertResolvedMatchedLate(ambiguous, matched, "OPEN");
    }

    @Test
    @DisplayName("FR-BRK-5 (TDD 8.2): a B1 match resolves the MISSING_SETTLEMENT on its batch, as MATCHED_LATE")
    void matchResolvesMissingSettlementOnItsBatch() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-4", 900, 0, TRY, TUESDAY)));
        UUID missing = openBreak(BreakType.MISSING_SETTLEMENT, batch(alpha, "B-4"), List.of(), Optional.empty());
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 900, THURSDAY, "B-4")));

        ReconciliationRun matched = run();

        assertThat(matchedPairs()).containsExactly("S-1");
        assertResolvedMatchedLate(missing, matched, "OPEN");
        assertThat(matched.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "batch.TRY.matched.count", 1L, "batch.TRY.broken.count", 0L)));
    }

    @Test
    @DisplayName("FR-BRK-5 (TDD 8.2): a break under investigation is resolved MATCHED_LATE too, and its event leaves "
            + "INVESTIGATING")
    void investigatedBreakIsResolvedWhenItsBankLineMatches() {
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-5")));
        run();
        Break unexpected = onlyBreakAbout(bank(line.get("S-1")));
        breaks.apply(unexpected.startInvestigation(Actor.operator("operator-001"), Optional.empty(), FixedRunClock.NOW));

        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-5", 1_000, 0, TRY, THURSDAY)));
        ReconciliationRun matched = run();

        assertResolvedMatchedLate(unexpected.id(), matched, "INVESTIGATING");
        assertThat(eventsOf(unexpected.id())).containsExactly(
                "->OPEN:-:system", "OPEN>INVESTIGATING:-:operator-001", "INVESTIGATING>RESOLVED:MATCHED_LATE:system");
    }

    @Test
    @DisplayName("TDD 8.2: a B1 match leaves its bank line's DUPLICATE_LINE open, and a break that only names a matched "
            + "item is left alone (built through the stores: no rule matches a line while another claims its batch)")
    void duplicateLineAndNamingBreaksStayOpen() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-6", 800, 0, TRY, THURSDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(
                bankLine("S-1", 800, WEDNESDAY, "B-6"),
                new Bank("S-OTHER", 800, TRY, MONDAY_AFTER, Optional.empty())));
        ItemRef matchedLine = bank(line.get("S-1"));
        ItemRef otherLine = bank(line.get("S-OTHER"));
        UUID duplicate = openBreak(BreakType.DUPLICATE_LINE, matchedLine, List.of(otherLine, batch(alpha, "B-6")),
                Optional.empty());
        UUID naming = openBreak(BreakType.DUPLICATE_LINE, otherLine, List.of(matchedLine, batch(alpha, "B-6")),
                Optional.empty());

        ReconciliationRun matched = run();

        assertThat(matchedPairs()).containsExactly("S-1");
        assertThat(List.of(duplicate, naming)).allSatisfy(id -> {
            assertThat(breaks.findById(id).orElseThrow().status()).isEqualTo(BreakStatus.OPEN);
            assertThat(eventsOf(id)).containsExactly("->OPEN:-:system");
        });
        assertThat(matched.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "bank.TRY.matched.count", 1L, "batch.TRY.matched.count", 1L)));
    }

    @Test
    @DisplayName("TDD 8.2 (v1.18): MATCHED_LATE is per side: a batch's late-line BATCH_AMOUNT_MISMATCH "
            + "(BATCH_CHANGED_AFTER_MATCH) stays open when its batch is matched again (built through the stores, as "
            + "after Milestone 7's reversal)")
    void batchChangedAfterMatchStaysOpenWhenTheBatchIsMatchedAgain() {
        fixture.pspFile(alpha, List.of(
                new Psp("A-1", "B-7", 500, 0, TRY, WEDNESDAY),
                new Psp("A-2", "B-7", 300, 0, TRY, THURSDAY)));
        UUID changed = openBreak(BreakType.BATCH_AMOUNT_MISMATCH, batch(alpha, "B-7"), List.of(),
                Optional.of(new BreakDetails.BatchChangedAfterMatch(UUID.randomUUID(),
                        new BatchFigures(1, List.of(money(500))), new BatchFigures(2, List.of(money(800))))));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 800, THURSDAY, "B-7")));

        run();

        assertThat(matchedPairs()).containsExactly("S-1");
        assertThat(breaks.findById(changed).orElseThrow().status()).isEqualTo(BreakStatus.OPEN);
        assertThat(eventsOf(changed)).containsExactly("->OPEN:-:system");
    }

    @Test
    @DisplayName("TDD 8.2: a line arriving after its batch was matched, changing the batch's sum, opens "
            + "BATCH_AMOUNT_MISMATCH on the BATCH item with BATCH_CHANGED_AFTER_MATCH details - the match's figures and "
            + "the batch's now - naming no other item; the match stays active (FR-MAT-2)")
    void lateLineChangingTheSumOpensABatchMismatch() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-11", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-11")));
        run();
        UUID matchId = onlyActiveMatchId();
        fixture.pspFile(alpha, List.of(new Psp("A-2", "B-11", 250, 0, TRY, THURSDAY)));

        ReconciliationRun changed = run();

        assertChangedAfterMatch(batch(alpha, "B-11"), changed, matchId,
                new BatchFigures(1, List.of(money(1_000))), new BatchFigures(2, List.of(money(1_250))));
        assertThat(onlyActiveMatchId()).isEqualTo(matchId);
        assertThat(changed.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "bank.TRY.matched.count", 1L, "batch.TRY.matched.count", 1L, "batch.TRY.matched.sum", 1_250L,
                "batch.TRY.broken.count", 0L)));
    }

    @Test
    @DisplayName("TDD 8.2: a late line of net 0 changes the batch's line count but not its sum, and opens the same "
            + "BATCH_AMOUNT_MISMATCH: the count is compared on its own")
    void lateLineChangingOnlyTheCountOpensABatchMismatch() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-12", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-12")));
        run();
        UUID matchId = onlyActiveMatchId();
        fixture.pspFile(alpha, List.of(new Psp("A-2", "B-12", 100, 100, TRY, THURSDAY)));

        ReconciliationRun changed = run();

        assertChangedAfterMatch(batch(alpha, "B-12"), changed, matchId,
                new BatchFigures(1, List.of(money(1_000))), new BatchFigures(2, List.of(money(1_000))));
    }

    @Test
    @DisplayName("TDD 8.2: a late line in another currency makes the matched batch mixed-currency and opens "
            + "BATCH_AMOUNT_MISMATCH, its current sums one per currency in currency-code order (INV-5)")
    void lateLineMakingTheBatchMixedCurrencyOpensABatchMismatch() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-13", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-13")));
        run();
        UUID matchId = onlyActiveMatchId();
        fixture.pspFile(alpha, List.of(new Psp("A-2", "B-13", 50, 0, "EUR", THURSDAY)));

        ReconciliationRun changed = run();

        assertChangedAfterMatch(batch(alpha, "B-13"), changed, matchId, new BatchFigures(1, List.of(money(1_000))),
                new BatchFigures(2, List.of(Money.of(50, CurrencyCode.of("EUR")), money(1_000))));
        assertThat(storedCurrentSumsOf(batch(alpha, "B-13")))
                .isEqualTo("[{\"amount\": 50, \"currency\": \"EUR\"}, {\"amount\": 1000, \"currency\": \"TRY\"}]");
    }

    @Test
    @DisplayName("TDD 8.2: a matched batch whose sum differs from its match's with the same line count opens "
            + "BATCH_AMOUNT_MISMATCH: the sums are compared on their own (built through the stores: lines are never "
            + "removed or changed, so no ingestion reaches it)")
    void sumDifferingWithTheSameCountOpensABatchMismatch() {
        fixture.pspFile(alpha, List.of(
                new Psp("A-1", "B-14", 600, 0, TRY, TUESDAY),
                new Psp("A-2", "B-14", 400, 0, TRY, TUESDAY)));
        Map<String, UUID> line = fixture.bankFile(bankSource, List.of(bankLine("S-1", 900, WEDNESDAY, "B-14")));
        fixture.matchBatch(fixture.completedRun(bankSource), batch(alpha, "B-14"), bank(line.get("S-1")), TRY, 2);

        ReconciliationRun changed = run();

        assertChangedAfterMatch(batch(alpha, "B-14"), changed, onlyActiveMatchId(),
                new BatchFigures(2, List.of(money(900))), new BatchFigures(2, List.of(money(1_000))));
    }

    @Test
    @DisplayName("TDD 8.2: the late-line break stays open for an operator: a later run, even after another late line, "
            + "opens no second break, leaves its details as they were written and writes no event (INV-7)")
    void lateLineBreakStaysOpenOnLaterRuns() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-15", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-15")));
        run();
        UUID matchId = onlyActiveMatchId();
        fixture.pspFile(alpha, List.of(new Psp("A-2", "B-15", 250, 0, TRY, THURSDAY)));
        run();
        Break opened = onlyBreakAbout(batch(alpha, "B-15"));
        List<String> eventsBefore = breakEventsOfTheBank();

        fixture.pspFile(alpha, List.of(new Psp("A-3", "B-15", 300, 0, TRY, FRIDAY)));
        run();
        run();

        assertThat(breaksAbout(batch(alpha, "B-15"))).containsExactly(opened);
        assertThat(breakEventsOfTheBank()).isEqualTo(eventsBefore);
        assertThat(onlyActiveMatchId()).isEqualTo(matchId);
    }

    @Test
    @DisplayName("TDD 8.2: a matched batch is compared when it is in the run's scope: a late line dated after the range "
            + "moves the batch out of it, and the run covering that date opens the break")
    void matchedBatchIsComparedWhenInScope() {
        fixture.pspFile(alpha, List.of(new Psp("A-1", "B-16", 1_000, 0, TRY, TUESDAY)));
        fixture.bankFile(bankSource, List.of(bankLine("S-1", 1_000, WEDNESDAY, "B-16")));
        run();
        UUID matchId = onlyActiveMatchId();
        fixture.pspFile(alpha, List.of(new Psp("A-2", "B-16", 250, 0, TRY, MONDAY_AFTER)));

        run();
        assertThat(breaksAbout(batch(alpha, "B-16"))).isEmpty();
        ReconciliationRun covering = matching.run(bankSource.value(), MONDAY_AFTER, MONDAY_AFTER, "operator-001");

        assertChangedAfterMatch(batch(alpha, "B-16"), covering, matchId,
                new BatchFigures(1, List.of(money(1_000))), new BatchFigures(2, List.of(money(1_250))));
    }

    private ReconciliationRun run() {
        return matching.run(bankSource.value(), MONDAY, FRIDAY, "operator-001");
    }

    /**
     * The batch's one break is an OPEN BATCH_AMOUNT_MISMATCH opened by the run, naming nothing, with
     * BATCH_CHANGED_AFTER_MATCH details read back through the strict reader, and its opening event.
     */
    private void assertChangedAfterMatch(ItemRef batch, ReconciliationRun run, UUID matchId, BatchFigures matched,
                                         BatchFigures current) {
        Break changed = onlyBreakAbout(batch);
        assertThat(changed).extracting(Break::type, Break::relatedItems, Break::status, Break::openedRunId,
                        Break::details)
                .containsExactly(BreakType.BATCH_AMOUNT_MISMATCH, List.of(), BreakStatus.OPEN, Optional.of(run.id()),
                        Optional.of(new BreakDetails.BatchChangedAfterMatch(matchId, matched, current)));
        assertThat(eventsOf(changed.id())).containsExactly("->OPEN:-:system");
    }

    /** The one active match holding a bank line of the bank source. */
    private UUID onlyActiveMatchId() {
        List<UUID> ids = jdbc.sql("""
                        SELECT item.match_id
                          FROM match_items item
                          JOIN bank_lines line ON item.side = 'BANK' AND line.id = item.item_id
                         WHERE line.source_code = :bank AND item.active
                        """)
                .param("bank", bankSource.value()).query(UUID.class).list();
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    /** The {@code current.sums} list of the batch's break details, as PostgreSQL stored it. */
    private String storedCurrentSumsOf(ItemRef batch) {
        return jdbc.sql("SELECT details -> 'current' -> 'sums' FROM breaks WHERE item_side = 'BATCH' AND item_id = :id")
                .param("id", batch.id()).query(String.class).single();
    }

    /** The break is RESOLVED as MATCHED_LATE by the run, with one event from {@code from}, by the system. */
    private void assertResolvedMatchedLate(UUID breakId, ReconciliationRun run, String from) {
        Break resolved = breaks.findById(breakId).orElseThrow();
        assertThat(resolved).extracting(Break::status, Break::resolutionCode, Break::resolvedAt)
                .containsExactly(BreakStatus.RESOLVED, Optional.of(ResolutionCode.MATCHED_LATE),
                        Optional.of(FixedRunClock.NOW));
        assertThat(eventsOf(breakId)).last().isEqualTo(from + ">RESOLVED:MATCHED_LATE:system");
        assertThat(reasonsOf(breakId)).last().isEqualTo("Matched by run " + run.id());
    }

    /** An OPEN break opened by the system, as a run would have opened it; returns its id. */
    private UUID openBreak(BreakType type, ItemRef subject, List<ItemRef> related, Optional<BreakDetails> details) {
        fixture.openBreak(type, subject, related, details);
        return onlyBreakAbout(subject).id();
    }

    /** The one break ever opened about the item, read back through the break store. */
    private Break onlyBreakAbout(ItemRef item) {
        List<Break> found = breaksAbout(item);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    /** The breaks ever opened about the item, oldest first, read back through the break store. */
    private List<Break> breaksAbout(ItemRef item) {
        return jdbc.sql("SELECT id FROM breaks WHERE item_side = :side AND item_id = :id ORDER BY opened_at, id")
                .param("side", item.side().name()).param("id", item.id())
                .query(UUID.class).list().stream().map(id -> breaks.findById(id).orElseThrow()).toList();
    }

    /** The line ids of the bank source's lines in an active match, in line-id order. */
    private List<String> matchedPairs() {
        return jdbc.sql("""
                        SELECT line.line_id
                          FROM match_items item
                          JOIN bank_lines line ON item.side = 'BANK' AND line.id = item.item_id
                         WHERE line.source_code = :bank AND item.active
                         ORDER BY line.line_id COLLATE "C"
                        """)
                .param("bank", bankSource.value()).query(String.class).list();
    }

    /** A break's events, as from>to:code:actor. */
    private List<String> eventsOf(UUID breakId) {
        return jdbc.sql("""
                        SELECT coalesce(from_status, '-') || '>' || to_status || ':' || coalesce(resolution_code, '-')
                               || ':' || actor
                          FROM break_events WHERE break_id = :breakId ORDER BY id
                        """)
                .param("breakId", breakId).query(String.class).list();
    }

    private List<String> reasonsOf(UUID breakId) {
        return jdbc.sql("SELECT coalesce(reason, '-') FROM break_events WHERE break_id = :breakId ORDER BY id")
                .param("breakId", breakId).query(String.class).list();
    }

    /**
     * Every event of every break about a bank line of the bank source or a batch of its PSP sources,
     * in event order: a re-run that writes nothing leaves it unchanged.
     */
    private List<String> breakEventsOfTheBank() {
        return jdbc.sql("""
                        SELECT event.break_id || '|' || coalesce(event.from_status, '-') || '>' || event.to_status
                          FROM break_events event
                          JOIN breaks found ON found.id = event.break_id
                         WHERE (found.item_side = 'BANK'
                                AND found.item_id IN (SELECT id FROM bank_lines WHERE source_code = :bank))
                            OR (found.item_side = 'BATCH' AND found.item_id IN (:batches))
                         ORDER BY event.id
                        """)
                .param("bank", bankSource.value())
                .param("batches", batchItemsOfTheBank())
                .query(String.class).list();
    }

    /** The item ids of every batch the bank's PSP sources have lines of, and one that matches nothing. */
    private List<UUID> batchItemsOfTheBank() {
        List<UUID> ids = new ArrayList<>(List.of(UUID.randomUUID()));
        for (SourceCode psp : List.of(alpha, beta)) {
            jdbc.sql("SELECT DISTINCT batch_id FROM psp_lines WHERE source_code = :psp")
                    .param("psp", psp.value()).query(String.class).list()
                    .forEach(batchId -> ids.add(batch(psp, batchId).id()));
        }
        return ids;
    }

    private static Bank bankLine(String lineId, long amount, LocalDate valueDate, String batchId) {
        return new Bank(lineId, amount, TRY, valueDate, Optional.of(batchId));
    }

    private static Money money(long minorUnits) {
        return Money.of(minorUnits, CurrencyCode.of(TRY));
    }
}
