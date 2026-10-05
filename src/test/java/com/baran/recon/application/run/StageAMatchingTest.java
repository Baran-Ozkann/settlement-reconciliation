package com.baran.recon.application.run;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageAFixture.BreakView;
import com.baran.recon.application.run.StageAFixture.MatchView;
import com.baran.recon.application.statement.IngestStatement;
import com.baran.recon.application.statement.UploadedStatement;
import com.baran.recon.domain.breaks.BreakType;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.domain.statement.StatementFile;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

/**
 * Stage A's rules and details (TDD 8.2) through the run use case, against the application's own
 * database as recon_app. Every test takes a source of its own, so tests never see each other's items
 * in the shared database. Runs are at {@link FixedRunClock#NOW}, Monday 2026-10-12; each source has a
 * window of 2 business days and grace periods of 3 (ledger) and 1 (PSP), and Thursday 2026-10-29 is
 * a holiday.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(FixedRunClock.class)
@DisplayName("TDD 8.2: Stage A matches ledger entries and PSP lines by its rules, in their order")
class StageAMatchingTest {

    /** Event ids and source codes no other test class uses; the shared database outlives each class. */
    private static final long FIRST_EVENT_ID = 9_700_000_000L;
    private static final String SOURCE_PREFIX = "PSP_STAGE_A_";
    private static final int SOURCES = 40;
    private static final AtomicInteger NEXT_SOURCE = new AtomicInteger();

    // A week with a weekend after it: 2026-10-05 is a Monday.
    private static final LocalDate FRIDAY_BEFORE = LocalDate.of(2026, 10, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    /** Monday 2026-10-12, the day every run of this class is made on. */
    private static final LocalDate TODAY = FixedRunClock.TODAY;

    private static final String TRY = "TRY";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        registry.add("recon.business-calendar.holidays[0]", () -> "2026-10-29");
        for (int i = 0; i < SOURCES; i++) {
            String source = "recon.sources[" + i + "].";
            String code = String.format("%s%03d", SOURCE_PREFIX, i);
            registry.add(source + "code", () -> code);
            registry.add(source + "type", () -> "PSP_SETTLEMENT");
            registry.add(source + "value-date-window-days", () -> "2");
            registry.add(source + "grace-days-ledger-unmatched", () -> "3");
            registry.add(source + "grace-days-psp-unmatched", () -> "1");
        }
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private StatementStore statements;

    @Autowired
    private Transactions transactions;

    @Autowired
    private BreakStore breaks;

    @Autowired
    private IngestStatement ingest;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    private StageAFixture fixture;
    private SourceCode source;

    @BeforeEach
    void takeASourceOfItsOwn() {
        fixture = new StageAFixture(ledgerEntries, statements, transactions, jdbc,
                FIRST_EVENT_ID + 1_000L * NEXT_SOURCE.get());
        source = SourceCode.of(String.format("%s%03d", SOURCE_PREFIX, NEXT_SOURCE.getAndIncrement()));
    }

    @Test
    @DisplayName("FR-MAT-6, A1_EXACT_REFERENCE: same reference, currency and amount is a match, recorded in full")
    void exactReferenceMatches() {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 12_500, TRY, TUESDAY);
        String line = fixture.psp(source, "L-001", transaction.toString(), 12_500, TRY, WEDNESDAY);

        ReconciliationRun run = run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).containsExactly(
                new MatchView("A1_EXACT_REFERENCE", 1, "ONE_TO_ONE", "ACTIVE", 0, TRY, false, run.id(), entry, line, 1));
    }

    @Test
    @DisplayName("TDD 8.2: references are compared as UUIDs, so case and formatting do not matter")
    void referencesAreComparedAsUuids() {
        UUID upper = UUID.randomUUID();
        UUID bare = UUID.randomUUID();
        UUID braced = UUID.randomUUID();
        String upperEntry = fixture.ledger(source, upper, 1_000, TRY, TUESDAY);
        String bareEntry = fixture.ledger(source, bare, 2_000, TRY, TUESDAY);
        String bracedEntry = fixture.ledger(source, braced, 3_000, TRY, TUESDAY);
        fixture.psp(source, "L-UPPER", upper.toString().toUpperCase(), 1_000, TRY, TUESDAY);
        fixture.psp(source, "L-BARE", bare.toString().replace("-", ""), 2_000, TRY, TUESDAY);
        fixture.psp(source, "L-BRACED", "{" + braced + "}", 3_000, TRY, TUESDAY);

        run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactlyInAnyOrder(
                        tuple("A1_EXACT_REFERENCE", upperEntry, "PSP L-UPPER"),
                        tuple("A1_EXACT_REFERENCE", bareEntry, "PSP L-BARE"),
                        tuple("A1_EXACT_REFERENCE", bracedEntry, "PSP L-BRACED"));
    }

    @Test
    @DisplayName("TDD 8.2: A1_EXACT_REFERENCE matches a candidate outside the run's range, however far apart the value dates")
    void exactReferenceMatchesOutsideTheRangeWhateverTheDates() {
        UUID twoDaysBack = UUID.randomUUID();
        UUID threeDaysBack = UUID.randomUUID();
        UUID twoMonthsBack = UUID.randomUUID();
        UUID lineAfterRange = UUID.randomUUID();
        String twoBackEntry = fixture.ledger(source, twoDaysBack, 1_000, TRY, MONDAY);
        fixture.psp(source, "L-TWO-BACK", twoDaysBack.toString(), 1_000, TRY, WEDNESDAY);
        String threeBackEntry = fixture.ledger(source, threeDaysBack, 2_000, TRY, FRIDAY_BEFORE);
        fixture.psp(source, "L-THREE-BACK", threeDaysBack.toString(), 2_000, TRY, WEDNESDAY);
        String farEntry = fixture.ledger(source, twoMonthsBack, 4_000, TRY, LocalDate.of(2026, 8, 5));
        fixture.psp(source, "L-FAR", twoMonthsBack.toString(), 4_000, TRY, WEDNESDAY);
        String entryInRange = fixture.ledger(source, lineAfterRange, 3_000, TRY, WEDNESDAY);
        fixture.psp(source, "L-AFTER", lineAfterRange.toString(), 3_000, TRY, FRIDAY);

        run(WEDNESDAY, WEDNESDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactlyInAnyOrder(
                        tuple("A1_EXACT_REFERENCE", twoBackEntry, "PSP L-TWO-BACK"),
                        tuple("A1_EXACT_REFERENCE", threeBackEntry, "PSP L-THREE-BACK"),
                        tuple("A1_EXACT_REFERENCE", farEntry, "PSP L-FAR"),
                        tuple("A1_EXACT_REFERENCE", entryInRange, "PSP L-AFTER"));
    }

    @Test
    @DisplayName("TDD 8.2: A3_FALLBACK_UNIQUE keeps the window: a candidate outside the run's range is matched within it, "
            + "in business days, and not beyond it")
    void fallbackCandidatesOutsideTheRangeWithinTheWindow() {
        String earlierEntry = fixture.ledger(source, UUID.randomUUID(), 1_000, TRY, MONDAY);
        fixture.psp(source, "L-TWO-BACK", null, 1_000, TRY, WEDNESDAY);
        fixture.ledger(source, UUID.randomUUID(), 2_000, TRY, FRIDAY_BEFORE);
        fixture.psp(source, "L-THREE-BACK", null, 2_000, TRY, WEDNESDAY);

        run(WEDNESDAY, WEDNESDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactly(tuple("A3_FALLBACK_UNIQUE", earlierEntry, "PSP L-TWO-BACK"));
    }

    @Test
    @DisplayName("TDD 8.1: a configured holiday is not counted in A3's window")
    void holidayIsNotCountedInTheWindow() {
        String entry = fixture.ledger(source, UUID.randomUUID(), 1_000, TRY, LocalDate.of(2026, 10, 28));
        fixture.psp(source, "L-HOLIDAY", null, 1_000, TRY, LocalDate.of(2026, 11, 2));

        run(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2));

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactly(tuple("A3_FALLBACK_UNIQUE", entry, "PSP L-HOLIDAY"));
    }

    @Test
    @DisplayName("FR-MAT-9: an entry without a value date is never a candidate")
    void entryWithoutValueDateIsNeverACandidate() {
        UUID transaction = UUID.randomUUID();
        fixture.undatedLedger(source, transaction, 1_000, TRY);
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, TUESDAY);

        run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
    }

    @Test
    @DisplayName("A2_REFERENCE_CONFLICT: same reference, other amount: AMOUNT_MISMATCH on the PSP line, the entry related")
    void referenceWithAnotherAmountIsAnAmountMismatch() {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 12_500, TRY, TUESDAY);
        fixture.psp(source, "L-001", transaction.toString(), 12_000, TRY, TUESDAY);

        ReconciliationRun run = run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        BreakView opened = onlyBreak();
        assertThat(opened).isEqualTo(new BreakView(opened.id(), "AMOUNT_MISMATCH", "OPEN", Optional.empty(), run.id(),
                "PSP L-001", Set.of(entry)));
        assertThat(fixture.eventsOf(opened.id())).containsExactly("->OPEN:-:system");
    }

    @Test
    @DisplayName("A2_REFERENCE_CONFLICT: same reference, other currency: CURRENCY_MISMATCH on the PSP line, the entry related")
    void referenceWithAnotherCurrencyIsACurrencyMismatch() {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 12_500, TRY, TUESDAY);
        fixture.psp(source, "L-001", transaction.toString(), 12_500, "EUR", TUESDAY);

        run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(onlyBreak()).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .containsExactly("CURRENCY_MISMATCH", "PSP L-001", Set.of(entry));
    }

    @Test
    @DisplayName("A2_REFERENCE_CONFLICT ignores the window: another amount or currency beyond it opens AMOUNT_MISMATCH "
            + "or CURRENCY_MISMATCH, and neither item gets a grace break")
    void referenceConflictOutsideTheWindowIsStillAConflict() {
        UUID amountConflict = UUID.randomUUID();
        UUID currencyConflict = UUID.randomUUID();
        UUID farConflict = UUID.randomUUID();
        String amountEntry = fixture.ledger(source, amountConflict, 2_000, TRY, MONDAY);
        fixture.psp(source, "L-AMOUNT", amountConflict.toString(), 2_500, TRY, THURSDAY);
        String currencyEntry = fixture.ledger(source, currencyConflict, 3_000, TRY, MONDAY);
        fixture.psp(source, "L-CURRENCY", currencyConflict.toString(), 3_000, "EUR", THURSDAY);
        String farEntry = fixture.ledger(source, farConflict, 4_100, TRY, LocalDate.of(2026, 8, 3));
        fixture.psp(source, "L-FAR", farConflict.toString(), 4_000, TRY, MONDAY);

        ReconciliationRun run = run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(fixture.breaks(source)).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .as("three business days apart, and an entry two months before its line")
                .containsExactlyInAnyOrder(
                        tuple("AMOUNT_MISMATCH", "PSP L-AMOUNT", Set.of(amountEntry)),
                        tuple("CURRENCY_MISMATCH", "PSP L-CURRENCY", Set.of(currencyEntry)),
                        tuple("AMOUNT_MISMATCH", "PSP L-FAR", Set.of(farEntry)));
        assertThat(run.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "ledger.TRY.broken.count", 2L, "ledger.TRY.pending.count", 0L,
                "psp.TRY.broken.count", 2L, "psp.EUR.broken.count", 1L, "psp.TRY.pending.count", 0L)));
    }

    @Test
    @DisplayName("A1_EXACT_REFERENCE ignores the window: the line's reference, currency and amount beyond it is a match, "
            + "and neither item gets a grace break")
    void exactReferenceOutsideTheWindowIsAMatch() {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 1_000, TRY, MONDAY);
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, THURSDAY);

        ReconciliationRun run = run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactly(tuple("A1_EXACT_REFERENCE", entry, "PSP L-001"));
        assertThat(fixture.breaks(source)).as("three business days apart, both past their grace periods").isEmpty();
        assertThat(run.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "ledger.TRY.matched.count", 1L, "ledger.TRY.pending.count", 0L, "ledger.TRY.broken.count", 0L,
                "psp.TRY.matched.count", 1L, "psp.TRY.pending.count", 0L, "psp.TRY.broken.count", 0L)));
    }

    @Test
    @DisplayName("FR-MAT-4: two entries with the line's reference, currency and amount, one beyond the window, is "
            + "AMBIGUOUS_MATCH naming both")
    void exactEntriesAreAmbiguousWhateverTheirDates() {
        UUID transaction = UUID.randomUUID();
        String near = fixture.ledger(source, transaction, 1_000, TRY, TUESDAY);
        String far = fixture.ledger(source, transaction, 1_000, TRY, LocalDate.of(2026, 8, 4));
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, TUESDAY);

        run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(onlyBreak()).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .containsExactly("AMBIGUOUS_MATCH", "PSP L-001", Set.of(near, far));
    }

    @Test
    @DisplayName("FR-MAT-4: two entries with the reference that both conflict, one beyond the window, is AMBIGUOUS_MATCH "
            + "naming both")
    void conflictingEntriesAreAmbiguousWhateverTheirDates() {
        UUID transaction = UUID.randomUUID();
        String near = fixture.ledger(source, transaction, 4_000, TRY, THURSDAY);
        String far = fixture.ledger(source, transaction, 6_000, TRY, MONDAY);
        fixture.psp(source, "L-001", transaction.toString(), 5_000, TRY, THURSDAY);

        run(MONDAY, FRIDAY);

        assertThat(onlyBreak()).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .containsExactly("AMBIGUOUS_MATCH", "PSP L-001", Set.of(near, far));
    }

    @Test
    @DisplayName("FR-MAT-4: A1 finding two entries makes no match and opens AMBIGUOUS_MATCH naming both")
    void twoExactEntriesAreAmbiguous() {
        UUID transaction = UUID.randomUUID();
        String first = fixture.ledger(source, transaction, 1_000, TRY, MONDAY);
        String second = fixture.ledger(source, transaction, 1_000, TRY, TUESDAY);
        fixture.ledger(source, transaction, 9_000, TRY, TUESDAY);
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, TUESDAY);

        run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(breakOn("PSP L-001")).extracting(BreakView::type, BreakView::related)
                .containsExactly("AMBIGUOUS_MATCH", Set.of(first, second));
    }

    @Test
    @DisplayName("FR-MAT-4: two entries with the reference, both conflicting, is AMBIGUOUS_MATCH naming both")
    void twoConflictingEntriesAreAmbiguous() {
        UUID transaction = UUID.randomUUID();
        String first = fixture.ledger(source, transaction, 1_000, TRY, TUESDAY);
        String second = fixture.ledger(source, transaction, 2_000, TRY, TUESDAY);
        fixture.psp(source, "L-001", transaction.toString(), 3_000, TRY, TUESDAY);

        run(MONDAY, FRIDAY);

        assertThat(onlyBreak()).extracting(BreakView::type, BreakView::related)
                .containsExactly("AMBIGUOUS_MATCH", Set.of(first, second));
    }

    @Test
    @DisplayName("TDD 8.2: two PSP lines with one reference are neither matched; each gets DUPLICATE_LINE naming the others")
    void duplicateReferencesAreNeverMatched() {
        UUID transaction = UUID.randomUUID();
        fixture.ledger(source, transaction, 1_000, TRY, TUESDAY);
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, TUESDAY);
        fixture.psp(source, "L-002", transaction.toString().toUpperCase(), 1_000, TRY, WEDNESDAY);
        fixture.psp(source, "L-LATER", transaction.toString(), 1_000, TRY, FRIDAY.plusDays(7));

        run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(fixture.breaks(source)).filteredOn(found -> found.subject().startsWith("PSP"))
                .extracting(BreakView::type, BreakView::subject, BreakView::related)
                .as("a line outside the range is named, and gets its own break when a run covers it")
                .containsExactlyInAnyOrder(
                        tuple("DUPLICATE_LINE", "PSP L-001", Set.of("PSP L-002", "PSP L-LATER")),
                        tuple("DUPLICATE_LINE", "PSP L-002", Set.of("PSP L-001", "PSP L-LATER")));
    }

    @Test
    @DisplayName("INV-7: a line that already has an unresolved break gets no second one, and no event")
    void anUnresolvedBreakIsNeverOpenedTwice() {
        UUID transaction = UUID.randomUUID();
        fixture.ledger(source, transaction, 12_500, TRY, TUESDAY);
        fixture.psp(source, "L-001", transaction.toString(), 12_000, TRY, TUESDAY);
        UUID earlier = fixture.openBreak(breaks, source, "L-001", BreakType.DUPLICATE_LINE);

        run(MONDAY, FRIDAY);

        assertThat(breakOn("PSP L-001")).extracting(BreakView::id, BreakView::type, BreakView::status)
                .containsExactly(earlier, "DUPLICATE_LINE", "OPEN");
        assertThat(fixture.eventsOf(earlier)).hasSize(1);
    }

    @Test
    @DisplayName("A3_FALLBACK_UNIQUE: a line without a reference and the one entry of its currency and amount match, low confidence")
    void lineWithoutReferenceMatchesItsOnlyCandidate() {
        String entry = fixture.ledger(source, UUID.randomUUID(), 4_000, TRY, FRIDAY);
        fixture.ledger(source, UUID.randomUUID(), 4_000, "EUR", FRIDAY);
        fixture.ledger(source, UUID.randomUUID(), 4_100, TRY, FRIDAY);
        fixture.psp(source, "L-001", null, 4_000, TRY, FRIDAY);

        ReconciliationRun run = run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).containsExactly(
                new MatchView("A3_FALLBACK_UNIQUE", 1, "ONE_TO_ONE", "ACTIVE", 0, TRY, true, run.id(), entry, "PSP L-001", 1));
    }

    @Test
    @DisplayName("TDD 8.2: A3 takes a reference that is not a UUID, or a UUID no entry of the source carries")
    void unusableReferencesFallBackToA3() {
        String forText = fixture.ledger(source, UUID.randomUUID(), 1_000, TRY, FRIDAY);
        String forUnknown = fixture.ledger(source, UUID.randomUUID(), 2_000, TRY, FRIDAY);
        fixture.psp(source, "L-TEXT", "ORDER-77", 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-UNKNOWN", UUID.randomUUID().toString(), 2_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactlyInAnyOrder(
                        tuple("A3_FALLBACK_UNIQUE", forText, "PSP L-TEXT"),
                        tuple("A3_FALLBACK_UNIQUE", forUnknown, "PSP L-UNKNOWN"));
    }

    @Test
    @DisplayName("TDD 8.2: a known reference is never offered to A3, whether its entry is matched or has no value date")
    void knownReferencesAreNotOfferedToA3() {
        UUID matched = UUID.randomUUID();
        UUID undated = UUID.randomUUID();
        String matchedEntry = fixture.ledger(source, matched, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-FIRST", matched.toString(), 1_000, TRY, FRIDAY);
        run(FRIDAY, FRIDAY);
        fixture.undatedLedger(source, undated, 3_000, TRY);
        fixture.ledger(source, UUID.randomUUID(), 2_000, TRY, FRIDAY);
        fixture.ledger(source, UUID.randomUUID(), 3_000, TRY, FRIDAY);
        fixture.psp(source, "L-AGAIN", matched.toString(), 2_000, TRY, FRIDAY);
        fixture.psp(source, "L-UNDATED", undated.toString(), 3_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::ledger, MatchView::psp)
                .containsExactly(tuple(matchedEntry, "PSP L-FIRST"));
        assertThat(fixture.breaks(source)).as("both lines are inside their grace period").isEmpty();
    }

    @Test
    @DisplayName("TDD 8.2: lines repeating an unknown reference are DUPLICATE_LINE and are not offered to A3")
    void duplicatedUnknownReferenceIsNotOfferedToA3() {
        String unknown = UUID.randomUUID().toString();
        fixture.ledger(source, UUID.randomUUID(), 7_000, TRY, FRIDAY);
        fixture.psp(source, "L-001", unknown, 7_000, TRY, FRIDAY);
        fixture.psp(source, "L-002", unknown, 7_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(fixture.breaks(source)).extracting(BreakView::type, BreakView::subject)
                .containsExactlyInAnyOrder(tuple("DUPLICATE_LINE", "PSP L-001"), tuple("DUPLICATE_LINE", "PSP L-002"));
    }

    @Test
    @DisplayName("FR-MAT-3: A1 comes first, so an entry it matches is no longer a candidate for A3")
    void exactReferenceWinsOverTheFallback() {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-REFERENCED", transaction.toString(), 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-UNREFERENCED", null, 1_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp)
                .containsExactly(tuple("A1_EXACT_REFERENCE", entry, "PSP L-REFERENCED"));
        assertThat(fixture.breaks(source)).isEmpty();
    }

    @Test
    @DisplayName("FR-MAT-4: A3 finding two entries makes no match and opens AMBIGUOUS_MATCH naming both")
    void twoFallbackCandidatesAreAmbiguous() {
        String first = fixture.ledger(source, UUID.randomUUID(), 5_000, TRY, FRIDAY);
        String second = fixture.ledger(source, UUID.randomUUID(), 5_000, TRY, TODAY);
        fixture.psp(source, "L-001", null, 5_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(breakOn("PSP L-001")).extracting(BreakView::type, BreakView::related)
                .containsExactly("AMBIGUOUS_MATCH", Set.of(first, second));
    }

    @Test
    @DisplayName("FR-MAT-4: two lines whose only candidate is one entry are both AMBIGUOUS_MATCH, and neither matches")
    void twoLinesClaimingOneEntryAreAmbiguous() {
        String entry = fixture.ledger(source, UUID.randomUUID(), 6_000, TRY, FRIDAY);
        fixture.psp(source, "L-001", null, 6_000, TRY, FRIDAY);
        fixture.psp(source, "L-002", "not-a-uuid", 6_000, TRY, TODAY);

        run(FRIDAY, TODAY);

        assertThat(fixture.matches(source)).isEmpty();
        assertThat(fixture.breaks(source)).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .containsExactlyInAnyOrder(
                        tuple("AMBIGUOUS_MATCH", "PSP L-001", Set.of(entry)),
                        tuple("AMBIGUOUS_MATCH", "PSP L-002", Set.of(entry)));
    }

    @Test
    @DisplayName("FR-BRK-5: a later run that matches an item resolves its open break as MATCHED_LATE, by the system, with its event")
    void laterMatchResolvesTheOpenBreak() {
        UUID referenced = UUID.randomUUID();
        String first = fixture.ledger(source, referenced, 5_000, TRY, FRIDAY);
        String second = fixture.ledger(source, UUID.randomUUID(), 5_000, TRY, FRIDAY);
        fixture.psp(source, "L-001", null, 5_000, TRY, FRIDAY);
        ReconciliationRun firstRun = run(FRIDAY, FRIDAY);
        BreakView ambiguous = breakOn("PSP L-001");
        assertThat(ambiguous.related()).containsExactlyInAnyOrder(first, second);

        fixture.psp(source, "L-002", referenced.toString(), 5_000, TRY, FRIDAY);
        ReconciliationRun secondRun = run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::rule, MatchView::ledger, MatchView::psp, MatchView::runId)
                .containsExactlyInAnyOrder(
                        tuple("A1_EXACT_REFERENCE", first, "PSP L-002", secondRun.id()),
                        tuple("A3_FALLBACK_UNIQUE", second, "PSP L-001", secondRun.id()));
        assertThat(breakOn("PSP L-001")).isEqualTo(new BreakView(ambiguous.id(), "AMBIGUOUS_MATCH", "RESOLVED",
                Optional.of("MATCHED_LATE"), firstRun.id(), "PSP L-001", Set.of(first, second)));
        assertThat(fixture.eventsOf(ambiguous.id()))
                .containsExactly("->OPEN:-:system", "OPEN>RESOLVED:MATCHED_LATE:system");
        assertThat(fixture.reasonsOf(ambiguous.id())).containsExactly("-", "Matched by run " + secondRun.id());
    }

    @Test
    @DisplayName("FR-BRK-5: a break under investigation is resolved MATCHED_LATE too, and its event leaves INVESTIGATING")
    void investigatedBreakIsResolvedWhenItsItemMatches() {
        UUID transaction = UUID.randomUUID();
        fixture.psp(source, "L-001", transaction.toString(), 8_000, TRY, FRIDAY);
        UUID investigated = fixture.openBreak(breaks, source, "L-001", BreakType.MISSING_IN_LEDGER);
        fixture.investigate(breaks, investigated);
        fixture.ledger(source, transaction, 8_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).hasSize(1);
        assertThat(breakOn("PSP L-001")).extracting(BreakView::status, BreakView::resolutionCode)
                .containsExactly("RESOLVED", Optional.of("MATCHED_LATE"));
        assertThat(fixture.eventsOf(investigated)).containsExactly(
                "->OPEN:-:operator-001", "OPEN>INVESTIGATING:-:operator-001", "INVESTIGATING>RESOLVED:MATCHED_LATE:system");
    }

    @Test
    @DisplayName("FR-BRK-5: MISSING_IN_PSP and MISSING_IN_LEDGER opened by a run are resolved MATCHED_LATE when "
            + "their items match, each with its event")
    void missingItemBreaksAreResolvedWhenTheirItemsMatch() {
        UUID entryFirst = UUID.randomUUID();
        UUID lineFirst = UUID.randomUUID();
        String lonelyEntry = fixture.ledger(source, entryFirst, 1_000, TRY, MONDAY);
        fixture.psp(source, "L-LONELY", lineFirst.toString(), 2_000, TRY, MONDAY);
        ReconciliationRun firstRun = run(MONDAY, FRIDAY);
        BreakView missingInPsp = breakOn(lonelyEntry);
        BreakView missingInLedger = breakOn("PSP L-LONELY");
        assertThat(List.of(missingInPsp.type(), missingInLedger.type()))
                .containsExactly("MISSING_IN_PSP", "MISSING_IN_LEDGER");

        fixture.psp(source, "L-LATE", entryFirst.toString(), 1_000, TRY, MONDAY);
        String lateEntry = fixture.ledger(source, lineFirst, 2_000, TRY, MONDAY);
        ReconciliationRun secondRun = run(MONDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::ledger, MatchView::psp, MatchView::runId)
                .containsExactlyInAnyOrder(
                        tuple(lonelyEntry, "PSP L-LATE", secondRun.id()),
                        tuple(lateEntry, "PSP L-LONELY", secondRun.id()));
        assertThat(fixture.breaks(source)).extracting(BreakView::id, BreakView::status, BreakView::resolutionCode,
                        BreakView::openedRunId)
                .containsExactlyInAnyOrder(
                        tuple(missingInPsp.id(), "RESOLVED", Optional.of("MATCHED_LATE"), firstRun.id()),
                        tuple(missingInLedger.id(), "RESOLVED", Optional.of("MATCHED_LATE"), firstRun.id()));
        for (BreakView resolved : List.of(missingInPsp, missingInLedger)) {
            assertThat(fixture.eventsOf(resolved.id()))
                    .containsExactly("->OPEN:-:system", "OPEN>RESOLVED:MATCHED_LATE:system");
        }
    }

    @Test
    @DisplayName("FR-BRK-5, TDD 8.2: a match does not answer DUPLICATE_LINE, AMOUNT_MISMATCH or CURRENCY_MISMATCH; "
            + "the break stays open and its item is MATCHED")
    void duplicateAndConflictBreaksStayOpenWhenTheirItemMatches() throws IOException {
        UUID duplicated = UUID.randomUUID();
        UUID amountConflict = UUID.randomUUID();
        UUID currencyConflict = UUID.randomUUID();
        String duplicatedEntry = fixture.ledger(source, duplicated, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-DUPLICATED", duplicated.toString(), 1_000, TRY, FRIDAY);
        StatementFile repeating = ingest.ingest(new UploadedStatement(source.value(), "STMT-" + UUID.randomUUID(),
                "repeat.csv", csv("L-DUPLICATED," + duplicated + ",B-002,2026-10-09,2026-10-09,PAYMENT,10.00,0.00,10.00,TRY"),
                "operator-001"));
        assertThat(repeating.lines().duplicateLineCount()).as("ingestion opened DUPLICATE_LINE on the stored line")
                .isEqualTo(1);
        String amountEntry = fixture.ledger(source, amountConflict, 2_000, TRY, FRIDAY);
        fixture.psp(source, "L-AMOUNT", amountConflict.toString(), 2_500, TRY, FRIDAY);
        String currencyEntry = fixture.ledger(source, currencyConflict, 3_000, TRY, FRIDAY);
        fixture.psp(source, "L-CURRENCY", currencyConflict.toString(), 3_000, "EUR", FRIDAY);
        ReconciliationRun firstRun = run(FRIDAY, FRIDAY);
        List<BreakView> opened = fixture.breaks(source);

        String exactAmount = fixture.ledger(source, amountConflict, 2_500, TRY, FRIDAY);
        String exactCurrency = fixture.ledger(source, currencyConflict, 3_000, "EUR", FRIDAY);
        ReconciliationRun secondRun = run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::ledger, MatchView::psp, MatchView::runId)
                .containsExactlyInAnyOrder(
                        tuple(duplicatedEntry, "PSP L-DUPLICATED", firstRun.id()),
                        tuple(exactAmount, "PSP L-AMOUNT", secondRun.id()),
                        tuple(exactCurrency, "PSP L-CURRENCY", secondRun.id()));
        assertThat(fixture.breaks(source)).as("as they were opened").isEqualTo(opened)
                .extracting(BreakView::type, BreakView::status, BreakView::subject, BreakView::related)
                .containsExactlyInAnyOrder(
                        tuple("DUPLICATE_LINE", "OPEN", "PSP L-DUPLICATED", Set.of()),
                        tuple("AMOUNT_MISMATCH", "OPEN", "PSP L-AMOUNT", Set.of(amountEntry)),
                        tuple("CURRENCY_MISMATCH", "OPEN", "PSP L-CURRENCY", Set.of(currencyEntry)));
        for (BreakView kept : opened) {
            assertThat(fixture.eventsOf(kept.id())).as("%s has only its opening event", kept.type()).hasSize(1);
        }
        assertThat(secondRun.stats()).hasValueSatisfying(stats -> assertThat(stats).containsAllEntriesOf(Map.of(
                "psp.TRY.matched.count", 2L, "psp.EUR.matched.count", 1L,
                "psp.TRY.broken.count", 0L, "psp.EUR.broken.count", 0L,
                "ledger.TRY.matched.count", 2L, "ledger.EUR.matched.count", 1L,
                "ledger.TRY.broken.count", 2L)));
    }

    @Test
    @DisplayName("FR-BRK-5: a break that only names a matched item among its related items stays open")
    void breakNamingAMatchedItemStaysOpen() {
        UUID referenced = UUID.randomUUID();
        String first = fixture.ledger(source, referenced, 5_000, TRY, FRIDAY);
        String second = fixture.ledger(source, UUID.randomUUID(), 5_000, TRY, FRIDAY);
        fixture.psp(source, "L-001", null, 5_000, TRY, FRIDAY);
        fixture.psp(source, "L-002", null, 5_000, TRY, FRIDAY);
        run(FRIDAY, FRIDAY);
        fixture.psp(source, "L-003", referenced.toString(), 5_000, TRY, FRIDAY);

        run(FRIDAY, FRIDAY);

        assertThat(fixture.matches(source)).extracting(MatchView::ledger, MatchView::psp)
                .containsExactly(tuple(first, "PSP L-003"));
        assertThat(fixture.breaks(source)).extracting(BreakView::subject, BreakView::status, BreakView::related)
                .as("each line now has one candidate, which the other line also has")
                .containsExactlyInAnyOrder(
                        tuple("PSP L-001", "OPEN", Set.of(first, second)),
                        tuple("PSP L-002", "OPEN", Set.of(first, second)));
    }

    @Test
    @DisplayName("TDD 8.2: a ledger entry unmatched past 3 business days is MISSING_IN_PSP; inside them it is pending")
    void ledgerEntryPastItsGraceIsMissingInPsp() {
        String late = fixture.ledger(source, UUID.randomUUID(), 1_000, TRY, TUESDAY);
        fixture.ledger(source, UUID.randomUUID(), 2_000, TRY, WEDNESDAY);

        ReconciliationRun run = run(MONDAY, FRIDAY);

        BreakView missing = onlyBreak();
        assertThat(missing).isEqualTo(new BreakView(missing.id(), "MISSING_IN_PSP", "OPEN", Optional.empty(), run.id(),
                late, Set.of()));
        assertThat(fixture.eventsOf(missing.id())).containsExactly("->OPEN:-:system");
    }

    @Test
    @DisplayName("TDD 8.2: a PSP line unmatched past 1 business day is MISSING_IN_LEDGER; inside it it is pending")
    void pspLinePastItsGraceIsMissingInLedger() {
        fixture.psp(source, "L-THURSDAY", null, 1_000, TRY, FRIDAY.minusDays(1));
        fixture.psp(source, "L-FRIDAY", null, 2_000, TRY, FRIDAY);

        run(MONDAY, FRIDAY);

        assertThat(onlyBreak()).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .containsExactly("MISSING_IN_LEDGER", "PSP L-THURSDAY", Set.of());
    }

    @Test
    @DisplayName("TDD 8.2: an item named by a break, an item with a break, and an item outside the range get no grace break")
    void graceBreaksOnlyForPendingItemsInRange() {
        UUID transaction = UUID.randomUUID();
        String named = fixture.ledger(source, transaction, 1_000, TRY, MONDAY);
        fixture.psp(source, "L-CONFLICT", transaction.toString(), 1_500, TRY, MONDAY);
        fixture.psp(source, "L-FLAGGED", null, 3_000, TRY, MONDAY);
        UUID flagged = fixture.openBreak(breaks, source, "L-FLAGGED", BreakType.DUPLICATE_LINE);
        fixture.ledger(source, UUID.randomUUID(), 4_000, TRY, FRIDAY_BEFORE);

        run(MONDAY, FRIDAY);

        assertThat(fixture.breaks(source)).extracting(BreakView::type, BreakView::subject, BreakView::related)
                .containsExactlyInAnyOrder(
                        tuple("AMOUNT_MISMATCH", "PSP L-CONFLICT", Set.of(named)),
                        tuple("DUPLICATE_LINE", "PSP L-FLAGGED", Set.of()));
        assertThat(fixture.eventsOf(flagged)).hasSize(1);
    }

    @Test
    @DisplayName("FR-API-4, INV-4: the run's stats count and sum its matched, pending and broken items per side and currency")
    void statsCountAndSumEachStatusPerSideAndCurrency() {
        UUID matched = UUID.randomUUID();
        UUID conflicting = UUID.randomUUID();
        fixture.ledger(source, matched, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-MATCHED", matched.toString(), 1_000, TRY, FRIDAY);
        fixture.ledger(source, conflicting, 2_500, TRY, FRIDAY);
        fixture.psp(source, "L-CONFLICT", conflicting.toString(), 2_000, TRY, FRIDAY);
        fixture.ledger(source, UUID.randomUUID(), 300, TRY, FRIDAY);
        fixture.psp(source, "L-PENDING", null, 444, "EUR", FRIDAY);
        fixture.ledger(source, UUID.randomUUID(), -700, TRY, MONDAY);
        fixture.ledger(source, UUID.randomUUID(), 9_999, TRY, FRIDAY_BEFORE);

        ReconciliationRun run = run(MONDAY, FRIDAY);

        assertThat(run.stats()).hasValueSatisfying(stats -> assertThat(stats).containsExactlyInAnyOrderEntriesOf(
                Map.ofEntries(
                        Map.entry("ledger.TRY.matched.count", 1L), Map.entry("ledger.TRY.matched.sum", 1_000L),
                        Map.entry("ledger.TRY.pending.count", 1L), Map.entry("ledger.TRY.pending.sum", 300L),
                        Map.entry("ledger.TRY.broken.count", 2L), Map.entry("ledger.TRY.broken.sum", 1_800L),
                        Map.entry("psp.TRY.matched.count", 1L), Map.entry("psp.TRY.matched.sum", 1_000L),
                        Map.entry("psp.TRY.pending.count", 0L), Map.entry("psp.TRY.pending.sum", 0L),
                        Map.entry("psp.TRY.broken.count", 1L), Map.entry("psp.TRY.broken.sum", 2_000L),
                        Map.entry("psp.EUR.matched.count", 0L), Map.entry("psp.EUR.matched.sum", 0L),
                        Map.entry("psp.EUR.pending.count", 1L), Map.entry("psp.EUR.pending.sum", 444L),
                        Map.entry("psp.EUR.broken.count", 0L), Map.entry("psp.EUR.broken.sum", 0L),
                        Map.entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 4L),
                        Map.entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L))));
    }

    @Test
    @DisplayName("FR-MAT-2: an active match is never altered by a later run, even when new items would contest it")
    void laterRunNeverAltersAnActiveMatch() {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, FRIDAY);
        ReconciliationRun first = run(FRIDAY, FRIDAY);
        List<String> matchBefore = fixture.matchRows(source);

        String sameTransaction = fixture.ledger(source, transaction, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-002", transaction.toString(), 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-003", null, 1_000, TRY, FRIDAY);
        ReconciliationRun second = run(FRIDAY, FRIDAY);

        assertThat(fixture.matchRows(source)).as("the first match's row, items and event are as they were")
                .containsAll(matchBefore);
        assertThat(fixture.matches(source)).extracting(MatchView::ledger, MatchView::psp, MatchView::runId,
                        MatchView::status, MatchView::createdEvents)
                .containsExactlyInAnyOrder(
                        tuple(entry, "PSP L-001", first.id(), "ACTIVE", 1L),
                        tuple(sameTransaction, "PSP L-002", second.id(), "ACTIVE", 1L));
        assertThat(fixture.breaks(source))
                .as("offered again, L-001 would have made L-002 a duplicate reference and L-003 ambiguous")
                .isEmpty();
    }

    @Test
    @DisplayName("TDD 8.2: re-running the same range with no new data writes its run row and nothing else")
    void reRunWithNoNewDataWritesOnlyItsRunRow() {
        UUID exact = UUID.randomUUID();
        UUID conflicting = UUID.randomUUID();
        UUID duplicated = UUID.randomUUID();
        fixture.ledger(source, exact, 1_000, TRY, TUESDAY);
        fixture.psp(source, "L-EXACT", exact.toString(), 1_000, TRY, TUESDAY);
        fixture.ledger(source, conflicting, 2_000, TRY, TUESDAY);
        fixture.psp(source, "L-CONFLICT", conflicting.toString(), 2_100, TRY, TUESDAY);
        fixture.psp(source, "L-DUP-1", duplicated.toString(), 3_000, TRY, TUESDAY);
        fixture.psp(source, "L-DUP-2", duplicated.toString(), 3_000, TRY, TUESDAY);
        fixture.ledger(source, UUID.randomUUID(), 4_000, TRY, TUESDAY);
        fixture.ledger(source, UUID.randomUUID(), 4_000, TRY, TUESDAY);
        fixture.psp(source, "L-AMBIGUOUS", null, 4_000, TRY, TUESDAY);
        fixture.ledger(source, UUID.randomUUID(), 5_000, TRY, TUESDAY);
        fixture.psp(source, "L-FALLBACK", "ORDER-5", 5_000, TRY, TUESDAY);
        fixture.ledger(source, UUID.randomUUID(), 6_000, TRY, MONDAY);
        fixture.psp(source, "L-MISSING", null, 7_000, TRY, MONDAY);
        ReconciliationRun first = run(MONDAY, FRIDAY);
        List<String> matchesBefore = fixture.matchRows(source);
        List<BreakView> breaksBefore = fixture.breaks(source);
        List<String> eventsBefore = breaksBefore.stream().flatMap(found -> fixture.eventsOf(found.id()).stream()).toList();
        assertThat(matchesBefore).as("the first run matched by A1 and A3").hasSize(2);
        assertThat(breaksBefore).extracting(BreakView::type).as("and opened a break of every Stage A kind")
                .contains("AMOUNT_MISMATCH", "DUPLICATE_LINE", "AMBIGUOUS_MATCH", "MISSING_IN_PSP", "MISSING_IN_LEDGER");

        ReconciliationRun second = run(MONDAY, FRIDAY);

        assertThat(fixture.matchRows(source)).isEqualTo(matchesBefore);
        assertThat(fixture.breaks(source)).isEqualTo(breaksBefore);
        assertThat(fixture.breaks(source).stream().flatMap(found -> fixture.eventsOf(found.id()).stream()).toList())
                .isEqualTo(eventsBefore);
        assertThat(second.stats()).isEqualTo(first.stats());
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_runs WHERE source_code = :source")
                .param("source", source.value()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("INV-2: a match committed while a run decides, unseen by the run's exclusion, makes the run fail on "
            + "the active-item index instead of matching the item twice")
    void indexStopsAMatchTheExclusionCannotSee() throws Exception {
        UUID transaction = UUID.randomUUID();
        String entry = fixture.ledger(source, transaction, 1_000, TRY, FRIDAY);
        fixture.psp(source, "L-001", transaction.toString(), 1_000, TRY, FRIDAY);
        ReconciliationRun earlier = run(MONDAY, MONDAY);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            matchElsewhere(other, earlier.id(), fixture.ledgerId(entry), fixture.pspId(source, "L-001"));

            Future<ReconciliationRun> deciding = thread.submit(() -> matching.run(source.value(), FRIDAY, FRIDAY,
                    "operator-001"));
            await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                            "SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype = 'transactionid'")
                    .query(Long.class).single() > 0);
            other.commit();

            assertThatThrownBy(() -> deciding.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(RunFailedException.class)
                    .cause().isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("match_items_active_item_unique");
        } finally {
            thread.shutdownNow();
        }

        assertThat(fixture.matches(source)).extracting(MatchView::runId).as("only the match made elsewhere")
                .containsExactly(earlier.id());
        assertThat(jdbc.sql("SELECT status FROM reconciliation_runs WHERE source_code = :source AND id <> :earlier")
                .param("source", source.value()).param("earlier", earlier.id()).query(String.class).single())
                .isEqualTo("FAILED");
    }

    /** An A1 match of the two items, written on another connection and not yet committed. */
    private static void matchElsewhere(Connection other, UUID runId, UUID ledgerId, UUID pspId) throws SQLException {
        UUID matchId = UUID.randomUUID();
        try (PreparedStatement match = other.prepareStatement("""
                INSERT INTO recon.matches (id, run_id, rule_id, rule_version, cardinality, status, amount_difference,
                                           currency, low_confidence, created_at)
                VALUES (?, ?, 'A1_EXACT_REFERENCE', 1, 'ONE_TO_ONE', 'ACTIVE', 0, 'TRY', FALSE, now())
                """);
             PreparedStatement items = other.prepareStatement(
                     "INSERT INTO recon.match_items (match_id, side, item_id, active) VALUES (?, ?, ?, TRUE)")) {
            match.setObject(1, matchId);
            match.setObject(2, runId);
            assertThat(match.executeUpdate()).isEqualTo(1);
            for (Object[] item : new Object[][] {{"LEDGER", ledgerId}, {"PSP", pspId}}) {
                items.setObject(1, matchId);
                items.setString(2, (String) item[0]);
                items.setObject(3, item[1]);
                assertThat(items.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private BreakView breakOn(String subject) {
        List<BreakView> found = fixture.breaks(source).stream().filter(view -> view.subject().equals(subject)).toList();
        assertThat(found).as("breaks on %s", subject).hasSize(1);
        return found.getFirst();
    }

    private BreakView onlyBreak() {
        List<BreakView> found = fixture.breaks(source);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    /** A PSP settlement report holding the given data lines, as an upload's content (TDD 7.1). */
    private static UploadedStatement.Content csv(String... lines) {
        byte[] bytes = (String.join("\n", "line_id,transaction_reference,batch_id,transaction_date,value_date,type,"
                + "gross_amount,fee_amount,net_amount,currency", String.join("\n", lines)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }

    private ReconciliationRun run(LocalDate from, LocalDate to) {
        ReconciliationRun run = matching.run(source.value(), from, to, "operator-001");
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        return run;
    }
}
