package com.baran.recon.application.run;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.run.StageBFixture.Bank;
import com.baran.recon.application.run.StageBFixture.Psp;
import com.baran.recon.domain.breaks.BatchFigures;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.item.BatchKey;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.Match;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ItemStatistics;
import com.baran.recon.domain.run.ItemStatus;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage B's invariants over generated batches and bank lines (TDD 8.2, 9, 13): INV-1 completeness,
 * INV-4 conservation and INV-5 determinism, with a fixed seed. A set is a few settlements across two
 * PSP sources settling to one bank: each a batch of one to three lines - payments and refunds, now and
 * then in a second currency, spread over one or two files - and usually a bank line paying it, exactly
 * or with another amount or currency, sometimes claimed twice or claimed again later, sometimes not paid
 * at all. Now and then the other PSP source has a batch of the same id, so the bank line's id is
 * ambiguous; bank fees name no batch, and some lines name a batch that does not exist. Each item
 * arrives in the first or the second of two deliveries, a run after each, so lines arrive after their
 * batch was matched, batches after their bank line, and claimants after a match. Dates reach past both
 * ends of the run's range, and unpaid batches lie past and within their grace period. jqwik checks
 * that every Stage B rule and break type occurs, and every shape of data named here.
 *
 * <p>jqwik runs this class, not JUnit's Spring extension, so the Spring test context is prepared by a
 * {@link TestContextManager}, as in {@link StageAPropertiesTest}. Each try takes a bank source and its
 * two PSP sources of its own, so tries never see each other's items; shrinking is off, because every
 * shrinking step would need sources too, and a failure reports the generated set instead. Stage B
 * reads no ledger entry, so this class stores none and uses no event ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(FixedRunClock.class)
@Label("INV-1, INV-4, INV-5: Stage B over generated batches and bank lines")
class StageBPropertiesTest {

    private static final String SEED = "20261024";
    private static final TestContextManager SPRING = new TestContextManager(StageBPropertiesTest.class);

    /** Source codes no other test class uses: a bank and the two PSP sources settling to it, per group. */
    private static final String PREFIX = "BPROP_";
    private static final int GROUPS = 110;
    private static final AtomicInteger NEXT_GROUP = new AtomicInteger();

    /** The run's range is a working week; the items' dates reach past both ends of it. */
    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);
    private static final LocalDate TO = LocalDate.of(2026, 10, 9);
    private static final List<LocalDate> DATES = LocalDate.of(2026, 10, 1).datesUntil(LocalDate.of(2026, 10, 13)).toList();
    private static final List<LocalDate> IN_RANGE = FROM.datesUntil(TO.plusDays(1)).toList();
    /** Gross amounts: payments, and a refund that can outweigh a payment or cancel one less its fee. */
    private static final List<Long> GROSS = List.of(1_000L, 2_500L, 4_000L, -1_500L);
    private static final int SETTLEMENTS = 6;
    private static final int GRACE_BATCH = 2;
    private static final BusinessCalendar CALENDAR =
            new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of());

    /** Every Stage B outcome; each must occur in some tries. */
    private static final List<String> OUTCOMES = List.of("B1_BATCH_TOTAL", "BATCH_AMOUNT_MISMATCH on BANK",
            "BATCH_AMOUNT_MISMATCH on BATCH", "UNEXPECTED_BANK_LINE", "AMBIGUOUS_MATCH",
            "DUPLICATE_LINE of unmatched claimants", "DUPLICATE_LINE naming a matched bank line", "MISSING_SETTLEMENT",
            "MATCHED_LATE");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        int index = 0;
        for (int group = 0; group < GROUPS; group++) {
            Group codes = Group.of(group);
            for (SourceCode psp : List.of(codes.alpha(), codes.beta())) {
                String source = "recon.sources[" + index++ + "].";
                registry.add(source + "code", psp::value);
                registry.add(source + "type", () -> "PSP_SETTLEMENT");
                registry.add(source + "settles-to", () -> codes.bank().value());
            }
            String source = "recon.sources[" + index++ + "].";
            registry.add(source + "code", () -> codes.bank().value());
            registry.add(source + "type", () -> "BANK_STATEMENT");
            registry.add(source + "batch-id-pattern", () -> "BATCH[-_]?([A-Za-z0-9_-]{1,64})");
            registry.add(source + "grace-days-batch-unpaid", () -> Integer.toString(GRACE_BATCH));
        }
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

    @BeforeProperty
    void prepareSpring() throws Exception {
        SPRING.prepareTestInstance(this);
    }

    @Property(tries = 40, seed = SEED, shrinking = ShrinkingMode.OFF)
    @Label("INV-1, INV-4: after each of two bank runs every bank line and batch in scope is exactly one of MATCHED, "
            + "PENDING and BROKEN, no bank line is pending, no batch past its grace is pending, and per side and "
            + "currency the statuses add up to the generated scope in count and in sum")
    void everyBankLineAndBatchEndsInExactlyOneStatus(@ForAll("scenarios") Scenario scenario) {
        Group group = Group.of(NEXT_GROUP.getAndIncrement());
        StageBFixture fixture = fixture();

        for (Phase phase : Phase.values()) {
            insertAsGenerated(fixture, group, scenario, phase);
            ReconciliationRun run = run(group);
            assertConservedAndComplete(group, scenario, phase, run);
        }
        requireEveryOutcome(group, scenario);
        requireEveryShape(scenario);
    }

    @Property(tries = 20, seed = SEED, shrinking = ShrinkingMode.OFF)
    @Label("INV-5: the same batches and bank lines stored in shuffled orders, in other files, give the same matches, "
            + "breaks and statistics after each run, compared by rule, items, type, subject, related items and details")
    void shuffledInsertionGivesTheSameResults(@ForAll("scenarios") Scenario scenario, @ForAll Random random) {
        List<Outcome> first = null;
        for (int order = 0; order < 3; order++) {
            Group group = Group.of(NEXT_GROUP.getAndIncrement());
            StageBFixture fixture = fixture();
            List<Outcome> outcomes = new ArrayList<>();
            List<UUID> runIds = new ArrayList<>();
            for (Phase phase : Phase.values()) {
                if (order == 0) {
                    insertAsGenerated(fixture, group, scenario, phase);
                } else {
                    insertShuffled(fixture, group, scenario, phase, random);
                }
                ReconciliationRun run = run(group);
                runIds.add(run.id());
                outcomes.add(outcome(group, scenario, runIds, run));
            }
            if (first == null) {
                first = outcomes;
            } else {
                assertThat(outcomes).as("order %s against the order generated, after each run", order)
                        .isEqualTo(first);
            }
        }
    }

    /**
     * What the runs decided, by the names the generated set gave its items: a bank line by its line
     * id, a batch by its PSP source's role and batch id, a run by its place in the sequence. A match
     * id in a late-line break's details is named by the match's items. Generated ids appear nowhere.
     */
    private Outcome outcome(Group group, Scenario scenario, List<UUID> runIds, ReconciliationRun run) {
        Map<UUID, String> names = names(group, scenario);
        Function<ItemRef, String> name = ref -> names.getOrDefault(ref.id(), "unknown " + ref);
        Map<UUID, Match> matchesById = new HashMap<>();
        Set<String> matched = new TreeSet<>();
        for (UUID id : matchIds(group)) {
            Match match = matches.findById(id).orElseThrow();
            matchesById.put(id, match);
            matched.add(String.join("|", match.rule().name(), Integer.toString(match.ruleVersion()),
                    match.status().name(), match.cardinality().name(), money(match.amountDifference()),
                    match.batchLineCount().toString(), "run " + runIds.indexOf(match.runId()), itemsOf(match, name)));
        }
        Set<String> broken = new TreeSet<>();
        for (UUID id : breakIds(group, scenario)) {
            Break found = breaks.findById(id).orElseThrow();
            broken.add(String.join("|", found.type().name(), found.status().name(),
                    found.resolutionCode().map(Enum::name).orElse("-"), name.apply(found.item()),
                    found.relatedItems().stream().map(name).sorted().toList().toString(),
                    found.details().map(details -> details(details, matchesById, name)).orElse("-"),
                    "run " + found.openedRunId().map(runIds::indexOf).orElse(-1)));
        }
        return new Outcome(matched, broken, run.stats().orElseThrow());
    }

    private static String itemsOf(Match match, Function<ItemRef, String> name) {
        return match.items().stream().map(item -> name.apply(new ItemRef(item.side(), item.itemId()))).sorted().toList()
                .toString();
    }

    private static String details(BreakDetails details, Map<UUID, Match> matchesById, Function<ItemRef, String> name) {
        return switch (details) {
            case BreakDetails.BatchTotalConflict conflict ->
                    "BATCH_TOTAL_CONFLICT bank " + money(conflict.bank()) + " batch " + figures(conflict.batch());
            case BreakDetails.BatchChangedAfterMatch changed ->
                    "BATCH_CHANGED_AFTER_MATCH match " + Optional.ofNullable(matchesById.get(changed.matchId()))
                            .map(match -> itemsOf(match, name)).orElse("unknown") + " matched "
                            + figures(changed.matched()) + " current " + figures(changed.current());
        };
    }

    private static String figures(BatchFigures figures) {
        return figures.lineCount() + " " + figures.sums().stream().map(StageBPropertiesTest::money).toList();
    }

    private static String money(Money money) {
        return money.minorUnits() + " " + money.currency();
    }

    /** Every bank line of the group's bank, by line id, and every batch the set names, in both PSP sources. */
    private Map<UUID, String> names(Group group, Scenario scenario) {
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, line_id FROM bank_lines WHERE source_code = :bank").param("bank", group.bank().value())
                .query((row, n) -> Map.entry(row.getObject("id", UUID.class), "BANK " + row.getString("line_id")))
                .list().forEach(line -> names.put(line.getKey(), line.getValue()));
        for (String batchId : scenario.batchIds()) {
            for (Role role : Role.values()) {
                names.put(new BatchKey(group.psp(role), batchId).itemId(), "BATCH " + role + "/" + batchId);
            }
        }
        return names;
    }

    private record Outcome(Set<String> matches, Set<String> breaks, SortedMap<String, Long> stats) {
    }

    /** The ids of the matches holding a bank line of the group's bank. */
    private List<UUID> matchIds(Group group) {
        return jdbc.sql("""
                        SELECT item.match_id
                          FROM match_items item
                          JOIN bank_lines line ON line.id = item.item_id
                         WHERE item.side = 'BANK' AND line.source_code = :bank
                        """)
                .param("bank", group.bank().value()).query(UUID.class).list();
    }

    /** The ids of the breaks about a bank line of the group's bank or a batch the set names. */
    private List<UUID> breakIds(Group group, Scenario scenario) {
        Set<UUID> batches = batchItems(group, scenario);
        List<UUID> ids = new ArrayList<>(jdbc.sql("""
                        SELECT found.id
                          FROM breaks found
                          JOIN bank_lines line ON found.item_side = 'BANK' AND line.id = found.item_id
                         WHERE line.source_code = :bank
                        """)
                .param("bank", group.bank().value()).query(UUID.class).list());
        jdbc.sql("SELECT id, item_id FROM breaks WHERE item_side = 'BATCH'")
                .query((row, n) -> Map.entry(row.getObject("id", UUID.class), row.getObject("item_id", UUID.class)))
                .list().stream().filter(found -> batches.contains(found.getValue()))
                .forEach(found -> ids.add(found.getKey()));
        return ids;
    }

    private static Set<UUID> batchItems(Group group, Scenario scenario) {
        Set<UUID> items = new HashSet<>();
        for (String batchId : scenario.batchIds()) {
            for (Role role : Role.values()) {
                items.add(new BatchKey(group.psp(role), batchId).itemId());
            }
        }
        return items;
    }

    /**
     * A set that never produces an outcome would pass without testing it, so jqwik checks across all
     * tries that B1 matched and that every Stage B break type was opened, in each of its forms, and that
     * a later match resolved a break as MATCHED_LATE, in some of them.
     */
    private void requireEveryOutcome(Group group, Scenario scenario) {
        Set<UUID> activeBank = new HashSet<>();
        Set<String> outcomes = new HashSet<>();
        for (UUID id : matchIds(group)) {
            Match match = matches.findById(id).orElseThrow();
            outcomes.add(match.rule().name());
            match.items().stream().filter(item -> item.side() == ItemSide.BANK).forEach(item -> activeBank.add(item.itemId()));
        }
        for (UUID id : breakIds(group, scenario)) {
            Break found = breaks.findById(id).orElseThrow();
            switch (found.type()) {
                case BATCH_AMOUNT_MISMATCH -> outcomes.add("BATCH_AMOUNT_MISMATCH on " + found.item().side());
                case DUPLICATE_LINE -> outcomes.add(found.relatedItems().stream()
                        .anyMatch(related -> activeBank.contains(related.id()))
                        ? "DUPLICATE_LINE naming a matched bank line" : "DUPLICATE_LINE of unmatched claimants");
                default -> outcomes.add(found.type().name());
            }
            found.resolutionCode().ifPresent(code -> outcomes.add(code.name()));
        }
        for (String outcome : OUTCOMES) {
            Statistics.label(outcome).collect(outcomes.contains(outcome));
            Statistics.label(outcome).coverage(checker -> checker.check(true).percentage(share -> share >= 5));
        }
    }

    /**
     * The generated data's own shapes, so a generator that stopped producing one is seen: jqwik checks
     * across all tries that each occurs in some of them.
     */
    private static void requireEveryShape(Scenario scenario) {
        Map<String, List<PspItem>> batches = scenario.pspLines().stream()
                .collect(Collectors.groupingBy(line -> line.role() + "/" + line.batchId()));
        Set<String> paid = scenario.bankLines().stream().flatMap(line -> line.batchId().stream())
                .collect(Collectors.toSet());
        LocalDate graceStart = CALENDAR.firstDayWithinGrace(FixedRunClock.TODAY, GRACE_BATCH);
        Map<String, Boolean> shapes = new LinkedHashMap<>();
        shapes.put("batch of several lines", batches.values().stream().anyMatch(lines -> lines.size() > 1));
        shapes.put("batch over two files", batches.values().stream()
                .anyMatch(lines -> lines.stream().map(line -> line.phase() + "/" + line.file()).distinct().count() > 1));
        shapes.put("refund", scenario.pspLines().stream().anyMatch(line -> line.gross() < 0));
        shapes.put("batch with a negative net", batches.values().stream()
                .anyMatch(lines -> lines.stream().mapToLong(PspItem::net).sum() < 0));
        shapes.put("mixed-currency batch", batches.values().stream()
                .anyMatch(lines -> lines.stream().map(PspItem::currency).distinct().count() > 1));
        shapes.put("PSP line in the second delivery", scenario.pspLines().stream()
                .anyMatch(line -> line.phase() == Phase.SECOND));
        shapes.put("bank line naming no batch", scenario.bankLines().stream().anyMatch(line -> line.batchId().isEmpty()));
        shapes.put("bank line naming an unknown batch", scenario.bankLines().stream()
                .anyMatch(line -> line.batchId().filter(id -> !scenario.batchIds().contains(id)).isPresent()));
        shapes.put("unpaid batch past its grace", unpaidDated(batches, paid, date -> date.isBefore(graceStart)));
        shapes.put("unpaid batch within its grace", unpaidDated(batches, paid, date -> !date.isBefore(graceStart)));
        shapes.forEach((shape, present) -> {
            Statistics.label(shape).collect(present);
            Statistics.label(shape).coverage(checker -> checker.check(true).percentage(share -> share >= 5));
        });
    }

    /** Whether some batch no bank line names is dated, by its latest line, in the range on such a day. */
    private static boolean unpaidDated(Map<String, List<PspItem>> batches, Set<String> paid,
                                       Predicate<LocalDate> day) {
        return batches.values().stream()
                .filter(lines -> !paid.contains(lines.getFirst().batchId()))
                .map(lines -> lines.stream().map(PspItem::valueDate).max(LocalDate::compareTo).orElseThrow())
                .anyMatch(date -> inRange(date) && day.test(date));
    }

    /**
     * INV-4 first: the run's totals per side and currency, over every status, are the generated items
     * in scope. Then INV-1: every in-scope item is classified from the stored rows, independently of
     * the run's own statement, and the run counts each under that status.
     */
    private void assertConservedAndComplete(Group group, Scenario scenario, Phase stored, ReconciliationRun run) {
        SortedMap<String, Long> statusStats = new TreeMap<>(run.stats().orElseThrow());
        statusStats.keySet().removeIf(key -> !key.matches("(bank|batch)\\.[A-Z]{3}\\..*"));
        assertThat(totalsBySideAndCurrency(statusStats))
                .as("INV-4: per side and currency, the statuses add up to the generated bank lines and batches in scope")
                .isEqualTo(expectedScope(scenario, stored));

        Set<String> nonZero = new HashSet<>();
        statusStats.forEach((key, value) -> {
            if (key.endsWith(".count") && value > 0) {
                nonZero.add(key.substring(0, key.lastIndexOf('.')));
            }
        });
        statusStats.keySet().removeIf(key -> !nonZero.contains(key.substring(0, key.lastIndexOf('.'))));
        assertThat(statusStats).as("INV-1: the run counts each in-scope bank line and batch once, under its status")
                .isEqualTo(classified(group, scenario));
    }

    /**
     * Per side, currency and status, the count and sum of the in-scope items as the stored rows give
     * them: an item in an active match is MATCHED; otherwise one that is the subject of an unresolved
     * break about a bank line or a batch, or named among its related items, is BROKEN; otherwise PENDING.
     * Batches are built here from their stored lines, not by the run's statements.
     */
    private SortedMap<String, Long> classified(Group group, Scenario scenario) {
        Set<UUID> batches = batchItems(group, scenario);
        Map<String, Integer> activeMatches = new HashMap<>();
        jdbc.sql("SELECT side, item_id FROM match_items WHERE active AND side IN ('BANK', 'BATCH')")
                .query((row, n) -> row.getString("side") + " " + row.getObject("item_id", UUID.class)).list()
                .forEach(item -> activeMatches.merge(item, 1, Integer::sum));
        Set<String> subjects = new HashSet<>(jdbc.sql("""
                        SELECT item_side || ' ' || item_id FROM breaks
                         WHERE status <> 'RESOLVED' AND item_side IN ('BANK', 'BATCH')
                        """)
                .query(String.class).list());
        Set<String> named = new HashSet<>(jdbc.sql("""
                        SELECT (related ->> 'side') || ' ' || (related ->> 'id')
                          FROM breaks, jsonb_array_elements(related_items) AS related
                         WHERE status <> 'RESOLVED' AND item_side IN ('BANK', 'BATCH')
                        """)
                .query(String.class).list());
        Function<String, ItemStatus> status = key -> {
            int count = activeMatches.getOrDefault(key, 0);
            assertThat(count).as("INV-2: %s is in at most one active match", key).isLessThanOrEqualTo(1);
            return count == 1 ? ItemStatus.MATCHED
                    : subjects.contains(key) || named.contains(key) ? ItemStatus.BROKEN
                    : ItemStatus.PENDING;
        };

        SortedMap<String, Long> classified = new TreeMap<>();
        jdbc.sql("""
                        SELECT id, line_id, currency, amount FROM bank_lines
                         WHERE source_code = :bank AND value_date BETWEEN :from AND :to
                        """)
                .param("bank", group.bank().value()).param("from", FROM).param("to", TO)
                .query((row, n) -> {
                    String key = "BANK " + row.getObject("id", UUID.class);
                    ItemStatus found = status.apply(key);
                    assertThat(found).as("INV-1 (TDD 8.2): bank line %s in scope is not pending: there is no grace "
                            + "for a bank line", row.getString("line_id")).isNotEqualTo(ItemStatus.PENDING);
                    add(classified, ItemSide.BANK, CurrencyCode.of(row.getString("currency")), found, row.getLong("amount"));
                    return key;
                })
                .list();

        LocalDate graceStart = CALENDAR.firstDayWithinGrace(FixedRunClock.TODAY, GRACE_BATCH);
        storedBatches(group).forEach((batch, lines) -> {
            LocalDate batchDate = lines.stream().map(StoredLine::valueDate).max(LocalDate::compareTo).orElseThrow();
            if (batchDate.isBefore(FROM) || batchDate.isAfter(TO)) {
                return;
            }
            UUID item = batch.itemId();
            assertThat(batches).as("a stored batch the set names").contains(item);
            ItemStatus found = status.apply("BATCH " + item);
            if (found == ItemStatus.PENDING) {
                assertThat(batchDate).as("INV-1 (TDD 8.2): batch %s is pending only inside its grace period", batch)
                        .isAfterOrEqualTo(graceStart);
            }
            lines.stream().collect(Collectors.groupingBy(StoredLine::currency, TreeMap::new,
                            Collectors.summingLong(StoredLine::net)))
                    .forEach((currency, sum) -> add(classified, ItemSide.BATCH, CurrencyCode.of(currency), found, sum));
        });
        return classified;
    }

    private static void add(SortedMap<String, Long> classified, ItemSide side, CurrencyCode currency, ItemStatus status,
                            long amount) {
        classified.merge(ItemStatistics.countKey(side, currency, status), 1L, Long::sum);
        classified.merge(ItemStatistics.sumKey(side, currency, status), amount, Long::sum);
    }

    /** The stored lines of the group's two PSP sources, by batch, whatever their dates and files. */
    private Map<BatchKey, List<StoredLine>> storedBatches(Group group) {
        return jdbc.sql("""
                        SELECT source_code, batch_id, currency, net_amount, value_date FROM psp_lines
                         WHERE source_code IN (:alpha, :beta)
                        """)
                .param("alpha", group.alpha().value()).param("beta", group.beta().value())
                .query((row, n) -> Map.entry(new BatchKey(SourceCode.of(row.getString("source_code")),
                                row.getString("batch_id")),
                        new StoredLine(row.getString("currency"), row.getLong("net_amount"),
                                row.getObject("value_date", LocalDate.class))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private record StoredLine(String currency, long net, LocalDate valueDate) {
    }

    /** Per side and currency, {count, sum} over every status. */
    private static Map<String, List<Long>> totalsBySideAndCurrency(SortedMap<String, Long> statusStats) {
        Map<String, long[]> totals = new TreeMap<>();
        statusStats.forEach((key, value) -> {
            String[] parts = key.split("\\.");
            long[] total = totals.computeIfAbsent(parts[0] + "." + parts[1], group -> new long[2]);
            total["count".equals(parts[3]) ? 0 : 1] += value;
        });
        Map<String, List<Long>> result = new TreeMap<>();
        totals.forEach((group, total) -> result.put(group, List.of(total[0], total[1])));
        return result;
    }

    /**
     * Per side and currency, {count, sum} of what the set has delivered up to {@code stored}, computed
     * from the set alone: each bank line dated in the range, its amount; each batch whose latest line
     * is dated in the range, once in each of its currencies, with its net sum in that currency.
     */
    private static Map<String, List<Long>> expectedScope(Scenario scenario, Phase stored) {
        Map<String, long[]> totals = new TreeMap<>();
        for (BankItem line : scenario.bankLines()) {
            if (line.phase().compareTo(stored) <= 0 && inRange(line.valueDate())) {
                add(totals, "bank." + line.currency(), line.amount());
            }
        }
        Map<String, List<PspItem>> batches = new TreeMap<>();
        for (PspItem line : scenario.pspLines()) {
            if (line.phase().compareTo(stored) <= 0) {
                batches.computeIfAbsent(line.role() + "/" + line.batchId(), any -> new ArrayList<>()).add(line);
            }
        }
        batches.values().forEach(lines -> {
            LocalDate batchDate = lines.stream().map(PspItem::valueDate).max(LocalDate::compareTo).orElseThrow();
            if (inRange(batchDate)) {
                lines.stream().collect(Collectors.groupingBy(PspItem::currency, Collectors.summingLong(PspItem::net)))
                        .forEach((currency, sum) -> add(totals, "batch." + currency, sum));
            }
        });
        Map<String, List<Long>> result = new TreeMap<>();
        totals.forEach((group, total) -> result.put(group, List.of(total[0], total[1])));
        return result;
    }

    private static void add(Map<String, long[]> totals, String group, long amount) {
        long[] total = totals.computeIfAbsent(group, any -> new long[2]);
        total[0] += 1;
        total[1] += amount;
    }

    private static boolean inRange(LocalDate date) {
        return !date.isBefore(FROM) && !date.isAfter(TO);
    }

    /**
     * The delivery's items as generated: each PSP source's lines in the files the set gave them, so a
     * batch can span two files, then the bank lines in one file.
     */
    private static void insertAsGenerated(StageBFixture fixture, Group group, Scenario scenario, Phase phase) {
        for (Role role : Role.values()) {
            for (int file = 0; file < 2; file++) {
                int inFile = file;
                List<Psp> lines = scenario.pspLines().stream()
                        .filter(line -> line.phase() == phase && line.role() == role && line.file() == inFile)
                        .map(PspItem::toPsp).toList();
                if (!lines.isEmpty()) {
                    fixture.pspFile(group.psp(role), lines);
                }
            }
        }
        List<Bank> bankLines = scenario.bankLines().stream().filter(line -> line.phase() == phase)
                .map(BankItem::toBank).toList();
        if (!bankLines.isEmpty()) {
            fixture.bankFile(group.bank(), bankLines);
        }
    }

    /**
     * The delivery's items in a shuffled order, PSP lines and bank lines mixed, each run of items of one
     * source cut into files of one to three lines, so the files, their order and the order of lines in a
     * file all differ from the generated delivery.
     */
    private static void insertShuffled(StageBFixture fixture, Group group, Scenario scenario, Phase phase,
                                       Random random) {
        List<Object> items = new ArrayList<>();
        scenario.pspLines().stream().filter(line -> line.phase() == phase).forEach(items::add);
        scenario.bankLines().stream().filter(line -> line.phase() == phase).forEach(items::add);
        Collections.shuffle(items, random);
        List<Object> file = new ArrayList<>();
        int size = 0;
        for (Object item : items) {
            if (!file.isEmpty() && (file.size() == size || !sameSource(file.getFirst(), item))) {
                store(fixture, group, file);
                file = new ArrayList<>();
            }
            if (file.isEmpty()) {
                size = 1 + random.nextInt(3);
            }
            file.add(item);
        }
        if (!file.isEmpty()) {
            store(fixture, group, file);
        }
    }

    private static boolean sameSource(Object first, Object next) {
        if (first instanceof PspItem line && next instanceof PspItem other) {
            return line.role() == other.role();
        }
        return first instanceof BankItem && next instanceof BankItem;
    }

    private static void store(StageBFixture fixture, Group group, List<Object> file) {
        if (file.getFirst() instanceof PspItem line) {
            fixture.pspFile(group.psp(line.role()), file.stream().map(item -> ((PspItem) item).toPsp()).toList());
        } else {
            fixture.bankFile(group.bank(), file.stream().map(item -> ((BankItem) item).toBank()).toList());
        }
    }

    private ReconciliationRun run(Group group) {
        ReconciliationRun run = matching.run(group.bank().value(), FROM, TO, "operator-001");
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        return run;
    }

    private StageBFixture fixture() {
        return new StageBFixture(statements, matches, breaks, runs, transactions);
    }

    /**
     * Settlements as the PSPs and the bank report them, and bank lines that name no batch or one that
     * does not exist. Amounts are few, so sums repeat and cancel; most items arrive in the first
     * delivery.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<LineSpec> line = Combinators.combine(
                        Arbitraries.of(GROSS),
                        Arbitraries.frequency(Tuple.of(3, 0L), Tuple.of(1, 50L)),
                        Arbitraries.frequency(Tuple.of(12, "TRY"), Tuple.of(1, "EUR")),
                        dates(),
                        Arbitraries.integers().between(0, 1),
                        Arbitraries.frequency(Tuple.of(2, Phase.FIRST), Tuple.of(1, Phase.SECOND)))
                .as(LineSpec::new);
        Arbitrary<SettlementSpec> settlement = Combinators.combine(
                        Arbitraries.frequency(Tuple.of(3, Role.ALPHA), Tuple.of(1, Role.BETA)),
                        Arbitraries.frequency(Tuple.of(4, false), Tuple.of(1, true)),
                        line.list().ofMinSize(1).ofMaxSize(3),
                        Arbitraries.frequency(Tuple.of(6, Payout.EXACT), Tuple.of(1, Payout.OTHER_AMOUNT),
                                Tuple.of(1, Payout.OTHER_CURRENCY), Tuple.of(2, Payout.NONE)),
                        Arbitraries.frequency(Tuple.of(1, Basis.EVERY_LINE), Tuple.of(1, Basis.FIRST_DELIVERY)),
                        dates(),
                        Arbitraries.frequency(Tuple.of(4, Phase.FIRST), Tuple.of(1, Phase.SECOND)),
                        Arbitraries.frequency(Tuple.of(4, Claim.ONCE), Tuple.of(1, Claim.TWICE),
                                Tuple.of(2, Claim.AGAIN_LATER)))
                .as(SettlementSpec::new);
        Arbitrary<OtherSpec> other = Combinators.combine(
                        Arbitraries.of(OtherKind.values()),
                        dates(),
                        Arbitraries.frequency(Tuple.of(4, Phase.FIRST), Tuple.of(1, Phase.SECOND)))
                .as(OtherSpec::new);
        return Combinators.combine(settlement.list().ofMinSize(3).ofMaxSize(SETTLEMENTS), other.list().ofMaxSize(2))
                .as(Scenario::of);
    }

    /** Mostly a day of the run's range, so most items are in scope; otherwise any day, before or after it. */
    private static Arbitrary<LocalDate> dates() {
        return Arbitraries.frequencyOf(Tuple.of(3, Arbitraries.of(IN_RANGE)), Tuple.of(1, Arbitraries.of(DATES)));
    }

    /** A try's sources: a bank, and the two PSP sources settling to it. */
    private record Group(SourceCode alpha, SourceCode beta, SourceCode bank) {

        static Group of(int index) {
            String number = String.format("%03d", index);
            return new Group(SourceCode.of("PSP_" + PREFIX + number + "_ALPHA"),
                    SourceCode.of("PSP_" + PREFIX + number + "_BETA"), SourceCode.of("BANK_" + PREFIX + number));
        }

        SourceCode psp(Role role) {
            return role == Role.ALPHA ? alpha : beta;
        }
    }

    /** Which of the two PSP sources settling to the bank. */
    private enum Role {
        ALPHA, BETA
    }

    /** The delivery an item arrives in; a run follows each. */
    private enum Phase {
        FIRST, SECOND
    }

    /** What the bank line paying a batch says, against the batch's lines. */
    private enum Payout {
        EXACT, OTHER_AMOUNT, OTHER_CURRENCY, NONE
    }

    /** Which of the batch's lines the bank line's amount adds up: all, or only those of the first delivery. */
    private enum Basis {
        EVERY_LINE, FIRST_DELIVERY
    }

    /** How many bank lines claim the batch: one, two in the same delivery, or a second in the next. */
    private enum Claim {
        ONCE, TWICE, AGAIN_LATER
    }

    /** A bank line that pays no batch: a fee naming none, or one naming a batch id nobody uses. */
    private enum OtherKind {
        FEE, UNKNOWN_BATCH
    }

    /** A PSP line: gross, fee, currency, value date, which of two files of its delivery, and its delivery. */
    private record LineSpec(long gross, long fee, String currency, LocalDate valueDate, int file, Phase phase) {
    }

    /**
     * A batch of one PSP source and the bank's lines about it: whether the other PSP source has a batch
     * of the same id, the batch's lines, what its bank line says, which lines it adds up, its date and
     * delivery, and how many bank lines claim the batch.
     */
    private record SettlementSpec(Role role, boolean twin, List<LineSpec> lines, Payout payout, Basis basis,
                                  LocalDate payoutDate, Phase payoutPhase, Claim claim) {
    }

    private record OtherSpec(OtherKind kind, LocalDate valueDate, Phase phase) {
    }

    private record PspItem(Role role, String lineId, String batchId, long gross, long fee, String currency,
                           LocalDate valueDate, int file, Phase phase) {

        long net() {
            return gross - fee;
        }

        Psp toPsp() {
            return new Psp(lineId, batchId, gross, fee, currency, valueDate);
        }
    }

    private record BankItem(String lineId, long amount, String currency, LocalDate valueDate,
                            Optional<String> batchId, Phase phase) {

        Bank toBank() {
            return new Bank(lineId, amount, currency, valueDate, batchId);
        }
    }

    /** The set's PSP lines and bank lines, with the ids they are stored and named by. */
    private record Scenario(List<PspItem> pspLines, List<BankItem> bankLines, List<String> batchIds) {

        static Scenario of(List<SettlementSpec> settlements, List<OtherSpec> others) {
            List<PspItem> psp = new ArrayList<>();
            List<BankItem> bank = new ArrayList<>();
            List<String> batchIds = new ArrayList<>();
            for (int i = 0; i < settlements.size(); i++) {
                SettlementSpec spec = settlements.get(i);
                String batchId = "B-" + i;
                batchIds.add(batchId);
                List<PspItem> lines = new ArrayList<>();
                for (int n = 0; n < spec.lines().size(); n++) {
                    LineSpec line = spec.lines().get(n);
                    lines.add(new PspItem(spec.role(), "P-" + i + "-" + n, batchId, line.gross(), line.fee(),
                            line.currency(), line.valueDate(), line.file(), line.phase()));
                }
                psp.addAll(lines);
                if (spec.twin()) {
                    Role other = spec.role() == Role.ALPHA ? Role.BETA : Role.ALPHA;
                    psp.add(new PspItem(other, "T-" + i, batchId, 1_000, 0, "TRY", spec.lines().getFirst().valueDate(),
                            0, Phase.FIRST));
                }
                if (spec.payout() != Payout.NONE) {
                    addPayouts(bank, spec, i, batchId, lines);
                }
            }
            for (int i = 0; i < others.size(); i++) {
                OtherSpec other = others.get(i);
                bank.add(other.kind() == OtherKind.FEE
                        ? new BankItem("N-" + i, -15, "TRY", other.valueDate(), Optional.empty(), other.phase())
                        : new BankItem("N-" + i, 700, "TRY", other.valueDate(), Optional.of("NO-SUCH-" + i), other.phase()));
            }
            return new Scenario(psp, bank, batchIds);
        }

        /**
         * The bank lines claiming the batch, in the currency of its first line: its sum over the lines
         * the basis names, in that currency, or that sum changed. A bank amount is never zero, so a sum
         * of zero is paid as one minor unit, which conflicts.
         */
        private static void addPayouts(List<BankItem> bank, SettlementSpec spec, int i, String batchId,
                                       List<PspItem> lines) {
            String currency = lines.getFirst().currency();
            List<PspItem> basis = spec.basis() == Basis.FIRST_DELIVERY
                    && lines.stream().anyMatch(line -> line.phase() == Phase.FIRST)
                    ? lines.stream().filter(line -> line.phase() == Phase.FIRST).toList() : lines;
            long sum = basis.stream().filter(line -> line.currency().equals(currency)).mapToLong(PspItem::net).sum();
            long amount = spec.payout() == Payout.OTHER_AMOUNT ? sum + 100 : sum;
            String paidIn = spec.payout() != Payout.OTHER_CURRENCY ? currency : "TRY".equals(currency) ? "EUR" : "TRY";
            long paid = amount == 0 ? 1 : amount;
            bank.add(new BankItem("S-" + i, paid, paidIn, spec.payoutDate(), Optional.of(batchId), spec.payoutPhase()));
            if (spec.claim() != Claim.ONCE) {
                Phase phase = spec.claim() == Claim.TWICE ? spec.payoutPhase() : Phase.SECOND;
                bank.add(new BankItem("S-" + i + "-AGAIN", paid, paidIn, spec.payoutDate(), Optional.of(batchId), phase));
            }
        }
    }
}
