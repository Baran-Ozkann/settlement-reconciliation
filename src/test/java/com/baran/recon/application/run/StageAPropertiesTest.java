package com.baran.recon.application.run;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.statistics.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageAFixture.BreakView;
import com.baran.recon.application.run.StageAFixture.MatchView;
import com.baran.recon.application.run.StageAFixture.Psp;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.run.ItemStatistics;
import com.baran.recon.domain.run.ItemStatus;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage A's invariants over generated item sets (TDD 9, 13): INV-1 completeness, INV-4
 * conservation and INV-5 determinism, with a fixed seed. A set is a few transactions as both sides see them: usually one
 * ledger entry and one PSP line that agree, sometimes a side missing or repeated, the line's
 * reference of any kind (exact, upper case, none, not a UUID, unknown) and its amount or currency
 * changed, on dates inside and outside the run's range. Amounts are few, so lines without a usable
 * reference meet several candidates. jqwik checks that every match rule and break type occurs.
 *
 * <p>jqwik runs this class, not JUnit's Spring extension, so the Spring test context is prepared
 * by a {@link TestContextManager}: the same cached context, properties and injection a
 * {@code @SpringBootTest} class gets. Each try takes sources of its own, so tries never see each
 * other's items; shrinking is off, because every shrinking step would need sources too, and a
 * failure reports the generated set instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(FixedRunClock.class)
@Label("INV-1, INV-4, INV-5: Stage A over generated items")
class StageAPropertiesTest {

    private static final String SEED = "20261003";
    private static final TestContextManager SPRING = new TestContextManager(StageAPropertiesTest.class);

    /** Event ids and source codes no other test class uses; each source has a block of event ids. */
    private static final long FIRST_EVENT_ID = 9_750_000_000L;
    private static final long EVENT_IDS_PER_SOURCE = 1_000L;
    private static final String SOURCE_PREFIX = "PSP_PROPERTY_";
    private static final int SOURCES = 200;
    private static final AtomicInteger NEXT_SOURCE = new AtomicInteger();

    /** The run's range is a working week; the items' dates reach past both ends of it. */
    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);
    private static final LocalDate TO = LocalDate.of(2026, 10, 9);
    private static final List<LocalDate> DATES = LocalDate.of(2026, 10, 2).datesUntil(LocalDate.of(2026, 10, 13)).toList();
    private static final List<Long> AMOUNTS = List.of(1_000L, 2_000L, 3_000L, -500L);
    private static final int TRANSACTIONS = 10;
    private static final int GRACE_LEDGER = 3;
    private static final int GRACE_PSP = 1;
    private static final BusinessCalendar CALENDAR =
            new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of());

    private static final Pattern LEDGER_KEY = Pattern.compile("LEDGER (\\d+)");

    /** Every match rule and break type Stage A produces; each must occur in some tries. */
    private static final List<String> OUTCOMES = List.of("A1_EXACT_REFERENCE", "A3_FALLBACK_UNIQUE", "AMOUNT_MISMATCH",
            "CURRENCY_MISMATCH", "DUPLICATE_LINE", "AMBIGUOUS_MATCH", "MISSING_IN_PSP", "MISSING_IN_LEDGER");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        for (int i = 0; i < SOURCES; i++) {
            String source = "recon.sources[" + i + "].";
            String code = sourceCode(i);
            registry.add(source + "code", () -> code);
            registry.add(source + "type", () -> "PSP_SETTLEMENT");
            registry.add(source + "value-date-window-days", () -> "2");
            registry.add(source + "grace-days-ledger-unmatched", () -> Integer.toString(GRACE_LEDGER));
            registry.add(source + "grace-days-psp-unmatched", () -> Integer.toString(GRACE_PSP));
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
    private JdbcClient jdbc;

    @BeforeProperty
    void prepareSpring() throws Exception {
        SPRING.prepareTestInstance(this);
    }

    @Property(tries = 40, seed = SEED, shrinking = ShrinkingMode.OFF)
    @Label("INV-1, INV-4: after each of two runs every in-scope item is exactly one of MATCHED, PENDING and BROKEN, "
            + "nothing past its grace is pending, and the statuses conserve the scope per side and currency")
    void everyInScopeItemEndsInExactlyOneStatus(@ForAll("scenarios") Scenario scenario) {
        int index = NEXT_SOURCE.getAndIncrement();
        SourceCode source = SourceCode.of(sourceCode(index));
        StageAFixture fixture = fixture(index);
        int ledgerHalf = scenario.ledger().size() / 2;
        int pspHalf = scenario.psp().size() / 2;

        insert(fixture, source, index, scenario, 0, ledgerHalf, 0, pspHalf);
        assertCompleteAndConserved(source, scenario, ledgerHalf, pspHalf, run(source));

        insert(fixture, source, index, scenario, ledgerHalf, scenario.ledger().size(), pspHalf, scenario.psp().size());
        assertCompleteAndConserved(source, scenario, scenario.ledger().size(), scenario.psp().size(), run(source));
        requireEveryOutcome(fixture, source);
    }

    @Property(tries = 20, seed = SEED, shrinking = ShrinkingMode.OFF)
    @Label("INV-5: the same items stored in shuffled orders give the same matches, breaks and statistics, "
            + "compared by line id and event id")
    void shuffledInsertionGivesTheSameResults(@ForAll("scenarios") Scenario scenario, @ForAll Random random) {
        Outcome first = null;
        for (int order = 0; order < 3; order++) {
            int index = NEXT_SOURCE.getAndIncrement();
            SourceCode source = SourceCode.of(sourceCode(index));
            StageAFixture fixture = fixture(index);
            List<Runnable> inserts = new ArrayList<>();
            for (int i = 0; i < scenario.ledger().size(); i++) {
                LedgerSpec entry = scenario.ledger().get(i);
                long eventId = eventBase(index) + i;
                inserts.add(() -> fixture.ledger(source, eventId, scenario.transactionId(entry.transaction()),
                        entry.amount(), entry.currency(), entry.valueDate()));
            }
            for (int i = 0; i < scenario.psp().size(); i++) {
                Psp line = psp(scenario, i);
                inserts.add(() -> fixture.pspLines(source, List.of(line)));
            }
            if (order > 0) {
                Collections.shuffle(inserts, random);
            }
            inserts.forEach(Runnable::run);

            Outcome outcome = outcome(fixture, source, eventBase(index), run(source));
            if (first == null) {
                first = outcome;
            } else {
                assertThat(outcome).as("order %s against the order generated", order).isEqualTo(first);
            }
        }
    }

    /**
     * What a run decided, by the keys the generated set gave its items: a PSP line by its line id, a
     * ledger entry by its event id less its source's block. Generated ids appear nowhere.
     */
    private static Outcome outcome(StageAFixture fixture, SourceCode source, long eventBase, ReconciliationRun run) {
        Set<String> matches = new TreeSet<>();
        for (MatchView match : fixture.matches(source)) {
            matches.add(String.join("|", match.rule(), Boolean.toString(match.lowConfidence()), match.status(),
                    Long.toString(match.amountDifference()), match.currency(), logical(match.ledger(), eventBase),
                    match.psp()));
        }
        Set<String> breaks = new TreeSet<>();
        for (BreakView found : fixture.breaks(source)) {
            breaks.add(String.join("|", found.type(), found.status(), logical(found.subject(), eventBase),
                    found.related().stream().map(item -> logical(item, eventBase)).sorted().toList().toString()));
        }
        return new Outcome(matches, breaks, run.stats().orElseThrow());
    }

    private static String logical(String key, long eventBase) {
        Matcher ledger = LEDGER_KEY.matcher(key);
        return ledger.matches() ? "LEDGER #" + (Long.parseLong(ledger.group(1)) - eventBase) : key;
    }

    private record Outcome(Set<String> matches, Set<String> breaks, SortedMap<String, Long> stats) {
    }

    /**
     * A property over sets that never produce an outcome would pass without testing it, so jqwik
     * checks across all tries that every rule matched and every Stage A break type was opened in
     * some of them.
     */
    private static void requireEveryOutcome(StageAFixture fixture, SourceCode source) {
        Set<String> outcomes = new HashSet<>();
        fixture.matches(source).forEach(match -> outcomes.add(match.rule()));
        fixture.breaks(source).forEach(found -> outcomes.add(found.type()));
        for (String outcome : OUTCOMES) {
            Statistics.label(outcome).collect(outcomes.contains(outcome));
            Statistics.label(outcome).coverage(checker -> checker.check(true).percentage(share -> share >= 5));
        }
    }

    private void insert(StageAFixture fixture, SourceCode source, int index, Scenario scenario, int ledgerFrom,
                        int ledgerTo, int pspFrom, int pspTo) {
        for (int i = ledgerFrom; i < ledgerTo; i++) {
            LedgerSpec entry = scenario.ledger().get(i);
            fixture.ledger(source, eventBase(index) + i, scenario.transactionId(entry.transaction()), entry.amount(),
                    entry.currency(), entry.valueDate());
        }
        List<Psp> lines = new ArrayList<>();
        for (int i = pspFrom; i < pspTo; i++) {
            lines.add(psp(scenario, i));
        }
        if (!lines.isEmpty()) {
            fixture.pspLines(source, lines);
        }
    }

    /**
     * Classifies every in-scope item from the stored rows, independently of the run's own
     * statement, and holds the run's statistics against that and against the generated items.
     */
    private void assertCompleteAndConserved(SourceCode source, Scenario scenario, int ledgerStored, int pspStored,
                                            ReconciliationRun run) {
        Map<String, Integer> activeMatches = new HashMap<>();
        jdbc.sql("""
                        SELECT item.side || ' ' || item.item_id FROM match_items item
                         WHERE item.active
                           AND (item.item_id IN (SELECT id FROM ledger_entries WHERE source_code = :source)
                                OR item.item_id IN (SELECT id FROM psp_lines WHERE source_code = :source))
                        """)
                .param("source", source.value()).query(String.class).list()
                .forEach(item -> activeMatches.merge(item, 1, Integer::sum));
        Set<String> subjects = new HashSet<>(jdbc.sql(
                        "SELECT item_side || ' ' || item_id FROM breaks WHERE status <> 'RESOLVED'")
                .query(String.class).list());
        Set<String> named = new HashSet<>(jdbc.sql("""
                        SELECT (related ->> 'side') || ' ' || (related ->> 'id')
                          FROM breaks, jsonb_array_elements(related_items) AS related
                         WHERE status <> 'RESOLVED'
                        """)
                .query(String.class).list());
        LocalDate ledgerGraceStart = CALENDAR.firstDayWithinGrace(FixedRunClock.TODAY, GRACE_LEDGER);
        LocalDate pspGraceStart = CALENDAR.firstDayWithinGrace(FixedRunClock.TODAY, GRACE_PSP);

        SortedMap<String, Long> classified = new TreeMap<>();
        jdbc.sql("""
                        SELECT 'LEDGER' AS side, id, currency, amount, value_date FROM ledger_entries
                         WHERE source_code = :source AND value_date BETWEEN :from AND :to
                        UNION ALL
                        SELECT 'PSP', id, currency, gross_amount, value_date FROM psp_lines
                         WHERE source_code = :source AND value_date BETWEEN :from AND :to
                        """)
                .param("source", source.value()).param("from", FROM).param("to", TO)
                .query((row, n) -> {
                    ItemSide side = ItemSide.valueOf(row.getString("side"));
                    String key = side + " " + row.getObject("id", UUID.class);
                    int matches = activeMatches.getOrDefault(key, 0);
                    assertThat(matches).as("INV-2: %s is in at most one active match", key).isLessThanOrEqualTo(1);
                    ItemStatus status = matches == 1 ? ItemStatus.MATCHED
                            : subjects.contains(key) || named.contains(key) ? ItemStatus.BROKEN
                            : ItemStatus.PENDING;
                    if (status == ItemStatus.PENDING) {
                        LocalDate graceStart = side == ItemSide.LEDGER ? ledgerGraceStart : pspGraceStart;
                        assertThat(row.getObject("value_date", LocalDate.class))
                                .as("INV-1: %s is pending only inside its grace period", key)
                                .isAfterOrEqualTo(graceStart);
                    }
                    CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
                    classified.merge(ItemStatistics.countKey(side, currency, status), 1L, Long::sum);
                    classified.merge(ItemStatistics.sumKey(side, currency, status), row.getLong("amount"), Long::sum);
                    return key;
                })
                .list();

        SortedMap<String, Long> statusStats = new TreeMap<>(run.stats().orElseThrow());
        statusStats.keySet().removeIf(key -> !key.matches("(ledger|psp)\\.[A-Z]{3}\\..*"));
        Set<String> noItems = new HashSet<>();
        statusStats.forEach((key, value) -> {
            if (key.endsWith(".count") && value == 0) {
                noItems.add(key.substring(0, key.lastIndexOf('.')));
            }
        });
        statusStats.keySet().removeIf(key -> noItems.contains(key.substring(0, key.lastIndexOf('.'))));
        assertThat(statusStats).as("INV-1: the run counts each in-scope item once, under its status")
                .isEqualTo(classified);
        assertThat(totalsBySideAndCurrency(statusStats))
                .as("INV-4: per side and currency, the statuses add up to the generated items in scope")
                .isEqualTo(expectedScope(scenario, ledgerStored, pspStored));
    }

    /** Per side and currency, {count, sum} over every status. */
    private static Map<String, List<Long>> totalsBySideAndCurrency(SortedMap<String, Long> statusStats) {
        Map<String, Long[]> totals = new TreeMap<>();
        statusStats.forEach((key, value) -> {
            String[] parts = key.split("\\.");
            Long[] total = totals.computeIfAbsent(parts[0] + "." + parts[1], group -> new Long[] {0L, 0L});
            total["count".equals(parts[3]) ? 0 : 1] += value;
        });
        Map<String, List<Long>> result = new TreeMap<>();
        totals.forEach((group, total) -> result.put(group, List.of(total)));
        return result;
    }

    /** Per side and currency, {count, sum} of the generated items stored so far whose date is in the range. */
    private static Map<String, List<Long>> expectedScope(Scenario scenario, int ledgerStored, int pspStored) {
        Map<String, Long[]> totals = new TreeMap<>();
        scenario.ledger().subList(0, ledgerStored).stream().filter(entry -> inRange(entry.valueDate()))
                .forEach(entry -> add(totals, "ledger." + entry.currency(), entry.amount()));
        scenario.psp().subList(0, pspStored).stream().filter(line -> inRange(line.valueDate()))
                .forEach(line -> add(totals, "psp." + line.currency(), line.amount()));
        Map<String, List<Long>> result = new TreeMap<>();
        totals.forEach((group, total) -> result.put(group, List.of(total)));
        return result;
    }

    private static void add(Map<String, Long[]> totals, String group, long amount) {
        Long[] total = totals.computeIfAbsent(group, any -> new Long[] {0L, 0L});
        total[0] += 1;
        total[1] += amount;
    }

    private static boolean inRange(LocalDate date) {
        return !date.isBefore(FROM) && !date.isAfter(TO);
    }

    private ReconciliationRun run(SourceCode source) {
        ReconciliationRun run = matching.run(source.value(), FROM, TO, "operator-001");
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        return run;
    }

    private StageAFixture fixture(int index) {
        return new StageAFixture(ledgerEntries, statements, transactions, jdbc, eventBase(index));
    }

    private static long eventBase(int index) {
        return FIRST_EVENT_ID + EVENT_IDS_PER_SOURCE * index;
    }

    private static Psp psp(Scenario scenario, int index) {
        PspSpec line = scenario.psp().get(index);
        return new Psp(scenario.lineId(index), scenario.reference(line), line.amount(), line.currency(),
                line.valueDate());
    }

    /**
     * Transactions as the two sides see them: the ledger's entries and the PSP's lines of each,
     * usually one of each and usually agreeing, sometimes missing, repeated or changed. Amounts are
     * few, so lines without a usable reference meet several candidates.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<TransactionSpec> transaction = Combinators.combine(
                        Arbitraries.of(AMOUNTS),
                        Arbitraries.frequency(Tuple.of(9, "TRY"), Tuple.of(1, "EUR")),
                        Arbitraries.of(DATES),
                        Arbitraries.frequency(Tuple.of(8, 1), Tuple.of(2, 0), Tuple.of(2, 2)),
                        Arbitraries.frequency(Tuple.of(8, 1), Tuple.of(2, 0), Tuple.of(2, 2)),
                        Arbitraries.frequency(Tuple.of(7, Reference.EXACT), Tuple.of(1, Reference.UPPER_CASE),
                                Tuple.of(2, Reference.NONE), Tuple.of(1, Reference.NOT_A_UUID),
                                Tuple.of(1, Reference.UNKNOWN)),
                        Arbitraries.frequency(Tuple.of(7, Change.NONE), Tuple.of(2, Change.AMOUNT),
                                Tuple.of(2, Change.CURRENCY)),
                        Arbitraries.integers().between(0, 3))
                .as(TransactionSpec::new);
        return Combinators.combine(Arbitraries.longs(), transaction.list().ofMinSize(4).ofMaxSize(TRANSACTIONS))
                .as(Scenario::of);
    }

    private static String sourceCode(int index) {
        return String.format("%s%03d", SOURCE_PREFIX, index);
    }

    /** How a PSP line refers to its transaction: as the ledger wrote it, in other case, or not usably. */
    private enum Reference {
        EXACT, UPPER_CASE, NONE, NOT_A_UUID, UNKNOWN
    }

    /** What the PSP's lines of a transaction say differently from the ledger. */
    private enum Change {
        NONE, AMOUNT, CURRENCY
    }

    /**
     * One transaction: its amount, currency and ledger value date, how many entries the ledger and
     * lines the PSP hold of it, how the lines refer to it, what they change, and how many calendar
     * days after the ledger's value date they are dated.
     */
    private record TransactionSpec(long amount, String currency, LocalDate ledgerDate, int ledgerCopies,
                                   int pspCopies, Reference reference, Change change, int pspDelay) {
    }

    private record LedgerSpec(int transaction, long amount, String currency, LocalDate valueDate) {
    }

    private record PspSpec(Reference reference, int transaction, long amount, String currency, LocalDate valueDate) {
    }

    /** Transaction ids are derived from the generated seed, so the same scenario has the same ids. */
    private record Scenario(long seed, List<LedgerSpec> ledger, List<PspSpec> psp) {

        static Scenario of(long seed, List<TransactionSpec> transactions) {
            List<LedgerSpec> ledger = new ArrayList<>();
            List<PspSpec> psp = new ArrayList<>();
            for (int i = 0; i < transactions.size(); i++) {
                TransactionSpec spec = transactions.get(i);
                for (int copy = 0; copy < spec.ledgerCopies(); copy++) {
                    ledger.add(new LedgerSpec(i, spec.amount(), spec.currency(), spec.ledgerDate()));
                }
                long amount = spec.change() == Change.AMOUNT ? spec.amount() + 100 : spec.amount();
                String currency = spec.change() != Change.CURRENCY ? spec.currency()
                        : "TRY".equals(spec.currency()) ? "EUR" : "TRY";
                for (int copy = 0; copy < spec.pspCopies(); copy++) {
                    psp.add(new PspSpec(spec.reference(), i, amount, currency, spec.ledgerDate().plusDays(spec.pspDelay())));
                }
            }
            return new Scenario(seed, ledger, psp);
        }

        UUID transactionId(int transaction) {
            return new UUID(seed, transaction);
        }

        String reference(PspSpec line) {
            return switch (line.reference()) {
                case EXACT -> transactionId(line.transaction()).toString();
                case UPPER_CASE -> transactionId(line.transaction()).toString().toUpperCase();
                case NONE -> null;
                case NOT_A_UUID -> "ORDER-" + line.transaction();
                case UNKNOWN -> new UUID(~seed, line.transaction()).toString();
            };
        }

        String lineId(int index) {
            return "L-" + index;
        }
    }
}
