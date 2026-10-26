package com.baran.recon.application.run;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.StageBStore;
import com.baran.recon.domain.breaks.Actor;
import com.baran.recon.domain.calendar.BusinessCalendar;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.RuleId;
import com.baran.recon.domain.match.StageBRule;
import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.run.ItemStatistics;
import com.baran.recon.domain.run.ItemStatus;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-PERF-4 (design doc v1.21, 4.6): a bank source's run at NFR-PERF-2's scale, by a JVM limited to
 * 512 MB of heap, measured rather than estimated, in the two shapes the requirement names, one per
 * subclass: a bank file's three-day range, and the scheduled run's 30-day range (TDD 5.3). Runs only
 * under -Pperf, which sets -Xmx512m for the test JVM; the test refuses to measure without it. Each
 * subclass loads the same data into a database of its own, so both shapes start from the same state.
 * The test asserts what the run did, not how long it took: the 180 s bound is asserted once
 * NFR-PERF-4's rule has been applied (Milestone 6 part D3b).
 *
 * <p>The data is synthetic, made by PostgreSQL itself from item numbers, in a fixed order, and
 * written straight into the database as the schema's owner, so the load costs the JVM nothing and is
 * timed apart from the run. Only the 10,000 batches' definitions are made in Java. 1,000,000 PSP
 * lines form batches of 100 lines, alternately of two PSP sources settling to one bank, one day's
 * batches after another over the 180 days ending on the run's last day. Every batch dated before the
 * daily range was paid on its own day and matched by B1 in an earlier run (the history). In the daily
 * range, by batch number modulo 20: 15 in 20 batches are paid exactly (B1), 2 are paid one kuruş more
 * (B2), 1 is paid twice (DUPLICATE_LINE on both lines), and 2 are not paid (MISSING_SETTLEMENT once
 * past their grace, PENDING within it). Each day of the daily range also has a bank fee with no batch
 * id and a line naming a batch no PSP sent (UNEXPECTED_BANK_LINE). One history batch in 50 has its
 * last line dated in the daily range, after its match, so the late-line check opens
 * BATCH_AMOUNT_MISMATCH on it when a run's range covers that line's day. The tables are vacuumed and
 * analysed after the load, as autovacuum would after such a load, so the plans do not depend on when
 * its daemon last woke.
 *
 * <p>Before the timed run, the run's Stage B statements are executed once under EXPLAIN (ANALYZE,
 * BUFFERS), in the run's order, as the application's role at the run's isolation level, in a
 * transaction that is rolled back; the dead rows it leaves are vacuumed away before the timed run.
 * The clock covers the whole timed run, from the call that records it RUNNING to its return,
 * COMPLETED: the stores are timed through proxies, so the result also says where the time went,
 * statement by statement, and how long the commit took. The plan of the statement the timed run
 * spent longest in is written to target/, beside every statement's plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=" + StageBPerformanceTest.ALPHA,
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].settles-to=" + StageBPerformanceTest.BANK,
        "recon.sources[1].code=" + StageBPerformanceTest.BETA,
        "recon.sources[1].type=PSP_SETTLEMENT",
        "recon.sources[1].settles-to=" + StageBPerformanceTest.BANK,
        "recon.sources[2].code=" + StageBPerformanceTest.BANK,
        "recon.sources[2].type=BANK_STATEMENT",
        "recon.sources[2].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[2].grace-days-batch-unpaid=2"})
@ActiveProfiles("test")
@Import({FixedRunClock.class, StageBPerformanceTest.TimeTheStores.class})
@DirtiesContext
@Tag("perf")
abstract class StageBPerformanceTest {

    static final String ALPHA = "PSP_STAGE_B_PERF_ALPHA";
    static final String BETA = "PSP_STAGE_B_PERF_BETA";
    static final String BANK = "BANK_STAGE_B_PERF";

    private static final long MAX_HEAP = 512L * 1024 * 1024;
    private static final LocalDate TODAY = FixedRunClock.TODAY;
    private static final int DAYS = 180;
    private static final LocalDate FIRST_DAY = TODAY.minusDays(DAYS - 1);
    /** The scheduled run's range starts look-back-days, 30 by default, before today (TDD 5.3). */
    private static final LocalDate DAILY_FROM = TODAY.minusDays(30);
    /** The bank source's grace-days-batch-unpaid, as configured above. */
    private static final int BATCH_GRACE_DAYS = 2;

    private static final int LINES = 1_000_000;
    private static final int LINES_PER_BATCH = 100;
    private static final int BATCHES = LINES / LINES_PER_BATCH;
    /** One history batch in this many has its last line dated in the daily range. */
    private static final int LATE_EVERY = 50;
    /** What a B2 bank line pays over its batch's sum, a fee's amount and a line naming an unknown batch's, in kuruş. */
    private static final long OVERPAID = 1;
    private static final long FEE = -1250;
    private static final long UNKNOWN_BATCH = 50_000;
    private static final String TRY = "TRY";
    private static final String OPERATOR = "operator-001";
    private static final Path OUTPUT = Path.of("target", "stage-b-perf");

    /**
     * The run's Stage B statements in the order it executes them: each store method, and the constant
     * of JdbcStageBStore holding the statement it runs.
     */
    private static final Map<String, String> STATEMENTS = statements();

    /** Nanoseconds spent in each timed method, and when each was first called and last returned. */
    private static final Map<String, AtomicLong> SPENT = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> FIRST_CALLED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> LAST_RETURNED = new ConcurrentHashMap<>();

    private static ReconPostgres database;

    @Autowired
    private RunMatching matching;

    @Autowired
    private RunStore runs;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BusinessCalendar calendar;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = ReconPostgres.throwaway();
        database.registerIn(registry);
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    /** The two shapes of NFR-PERF-4: a bank file's three days at the end, and the scheduled run's range. */
    enum Shape {
        FILE(TODAY.minusDays(2)),
        DAILY(DAILY_FROM);

        private final LocalDate from;

        Shape(LocalDate from) {
            this.from = from;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a batch of the generated data is, by its number and day. */
    enum Kind {
        /** Dated before the daily range: paid on its day and matched by B1 in an earlier run. */
        HISTORY,
        /** Paid on its day by one bank line of its exact sum. */
        EXACT,
        /** Paid on its day by one bank line of its sum and one kuruş. */
        CONFLICT,
        /** Paid on its day by two bank lines of its exact sum. */
        DUPLICATE,
        /** Not paid. */
        UNPAID;

        static Kind of(int number, LocalDate date) {
            if (date.isBefore(DAILY_FROM)) {
                return HISTORY;
            }
            return switch (number % 20) {
                case 14, 15 -> CONFLICT;
                case 16 -> DUPLICATE;
                case 17, 18 -> UNPAID;
                default -> EXACT;
            };
        }

        int bankLines() {
            return switch (this) {
                case UNPAID -> 0;
                case DUPLICATE -> 2;
                default -> 1;
            };
        }
    }

    /**
     * One batch: its number, PSP source, batch id and day, its kind, and for one history batch in
     * {@link #LATE_EVERY} the day its last line is dated, inside the daily range.
     */
    record Batch(int number, String source, String batchId, LocalDate date, Kind kind, Optional<LocalDate> lateLineDate) {

        static Batch numbered(int number) {
            LocalDate date = FIRST_DAY.plusDays((long) (number - 1) * DAYS / BATCHES);
            Kind kind = Kind.of(number, date);
            Optional<LocalDate> late = kind == Kind.HISTORY && number % LATE_EVERY == 0
                    ? Optional.of(TODAY.minusDays((number / LATE_EVERY) % 31))
                    : Optional.empty();
            return new Batch(number, number % 2 == 1 ? ALPHA : BETA, String.format(Locale.ROOT, "PB%05d", number), date,
                    kind, late);
        }

        /** The batch's date now: the latest value date of its lines (TDD 8.2). */
        LocalDate currentDate() {
            return lateLineDate.orElse(date);
        }
    }

    /**
     * Loads the data, rehearses the run's statements under EXPLAIN, then times one run of the shape
     * and checks what it did against what the data was built for.
     */
    protected void measure(Shape shape) throws Exception {
        assertThat(Runtime.getRuntime().maxMemory()).as("run under -Pperf, which sets -Xmx512m").isLessThanOrEqualTo(MAX_HEAP);
        List<Batch> batches = new ArrayList<>(BATCHES);
        for (int number = 1; number <= BATCHES; number++) {
            batches.add(Batch.numbered(number));
        }
        Sums sums = Sums.of(batches);
        LocalDate graceStart = calendar.firstDayWithinGrace(TODAY, BATCH_GRACE_DAYS);
        String tag = "[" + shape.label() + " " + shape.from + ".." + TODAY + "]";

        long loadStart = System.nanoTime();
        load(batches);
        double loadSeconds = seconds(System.nanoTime() - loadStart);
        System.out.println("NFR-PERF-4 DATA " + tag + ": " + describe(batches));

        long rehearsalStart = System.nanoTime();
        Map<String, List<String>> plans = explainEachStatement(shape, graceStart);
        vacuumRunTables();
        double rehearsalSeconds = seconds(System.nanoTime() - rehearsalStart);
        resetPeaks();
        SPENT.clear();
        FIRST_CALLED.clear();
        LAST_RETURNED.clear();

        long start = System.nanoTime();
        ReconciliationRun run = matching.run(BANK, shape.from, TODAY, OPERATOR);
        long end = System.nanoTime();
        Duration elapsed = Duration.ofNanos(end - start);

        long peakHeap = peakHeapUsed();
        System.out.printf(Locale.ROOT, "NFR-PERF-4 RESULT %s: %d PSP lines in %d batches, run %s in %.3f s (diagnostic: no "
                        + "bound asserted); max heap %d MB, peak heap used %d MB; %d processors, Java %s; load and vacuum "
                        + "%.1f s, EXPLAIN rehearsal and vacuum %.1f s%n",
                tag, LINES, BATCHES, run.status(), elapsed.toNanos() / 1e9,
                Runtime.getRuntime().maxMemory() / (1024 * 1024), peakHeap / (1024 * 1024),
                Runtime.getRuntime().availableProcessors(), Runtime.version(), loadSeconds, rehearsalSeconds);
        System.out.printf(Locale.ROOT, "NFR-PERF-4 BREAKDOWN %s: RUNNING row %.3f s, B1 %.3f s, bank line breaks %.3f s, "
                        + "changed batches %.3f s, MATCHED_LATE %.3f s, grace breaks %.3f s, statistics %.3f s, commit %.3f s%n",
                tag, seconds(FIRST_CALLED.get("matchBatchTotals").get() - start), spent("matchBatchTotals"),
                spent("openBankLineBreaks"), spent("openChangedBatchBreaks"), spent("resolveMatchedLate"),
                spent("openGraceBreaks"), spent("itemTotals") + spent("countInScope") + spent("countWithoutValueDate"),
                seconds(end - LAST_RETURNED.get("recordOutcome").get()));
        String slowest = STATEMENTS.keySet().stream()
                .max(Comparator.comparingDouble(StageBPerformanceTest::spent))
                .orElseThrow();
        Path slowestPlan = writePlans(shape, plans, slowest);
        System.out.printf(Locale.ROOT, "NFR-PERF-4 EXPLAIN %s: slowest statement %s (%.3f s in the timed run), its plan in %s; "
                        + "under EXPLAIN %s%n",
                tag, slowest, spent(slowest), slowestPlan, executionTime(plans.get(slowest)));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(outcomes(run.id())).as("the run did the work the data was built for")
                .containsExactlyInAnyOrderEntriesOf(expectedOutcomes(batches, shape, graceStart));
        assertThat(run.stats()).as("the run's statistics, which conserve the generated scope per status (INV-1, INV-4)")
                .hasValue(new TreeMap<>(expectedStatistics(batches, sums, shape, graceStart)));
    }

    /**
     * The net sums of each batch's lines, all of them and those of the batch's own day, which its
     * history match recorded; computed here from the formula the load uses, a line at a time.
     */
    record Sums(long[] all, long[] onItsDay, int[] linesOnItsDay) {

        static Sums of(List<Batch> batches) {
            long[] all = new long[BATCHES + 1];
            long[] onItsDay = new long[BATCHES + 1];
            int[] linesOnItsDay = new int[BATCHES + 1];
            for (int line = 1; line <= LINES; line++) {
                int number = (line - 1) / LINES_PER_BATCH + 1;
                long gross = 10_000 + (37L * line) % 90_000;
                long net = gross - gross / 40;
                all[number] += net;
                boolean late = batches.get(number - 1).lateLineDate().isPresent() && line % LINES_PER_BATCH == 0;
                if (!late) {
                    onItsDay[number] += net;
                    linesOnItsDay[number]++;
                }
            }
            return new Sums(all, onItsDay, linesOnItsDay);
        }
    }

    /** The matches this run made and the breaks it opened, by rule and by side and type, and every resolved break. */
    private static Map<String, Long> outcomes(UUID runId) throws SQLException {
        Map<String, Long> outcomes = new LinkedHashMap<>();
        try (Connection connection = database.connectAsMigrator()) {
            JdbcClient owner = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            owner.sql("SET search_path TO " + ReconPostgres.SCHEMA).update();
            owner.sql("""
                            SELECT 'match ' || rule_id AS outcome, count(*) AS items FROM matches WHERE run_id = :runId
                             GROUP BY rule_id
                            UNION ALL
                            SELECT 'break ' || item_side || ' ' || break_type, count(*) FROM breaks WHERE opened_run_id = :runId
                             GROUP BY item_side, break_type
                            UNION ALL
                            SELECT 'resolved ' || resolution_code, count(*) FROM breaks WHERE status = 'RESOLVED'
                             GROUP BY resolution_code
                            """)
                    .param("runId", runId)
                    .query((row, n) -> Map.entry(row.getString("outcome"), row.getLong("items")))
                    .list()
                    .forEach(outcome -> outcomes.put(outcome.getKey(), outcome.getValue()));
        }
        return outcomes;
    }

    /** What the data was built for, in a fresh database: see the class comment. */
    private static Map<String, Long> expectedOutcomes(List<Batch> batches, Shape shape, LocalDate graceStart) {
        Map<String, Long> expected = new TreeMap<>();
        for (Batch batch : batches) {
            if (batch.currentDate().isBefore(shape.from)) {
                continue;
            }
            switch (batch.kind()) {
                case HISTORY -> expected.merge("break BATCH BATCH_AMOUNT_MISMATCH", 1L, Long::sum);
                case EXACT -> expected.merge("match B1_BATCH_TOTAL", 1L, Long::sum);
                case CONFLICT -> expected.merge("break BANK BATCH_AMOUNT_MISMATCH", 1L, Long::sum);
                case DUPLICATE -> expected.merge("break BANK DUPLICATE_LINE", 2L, Long::sum);
                case UNPAID -> {
                    if (batch.date().isBefore(graceStart)) {
                        expected.merge("break BATCH MISSING_SETTLEMENT", 1L, Long::sum);
                    }
                }
            }
        }
        expected.put("break BANK UNEXPECTED_BANK_LINE", 2 * days(shape.from, TODAY));
        return expected;
    }

    /** The run's statistics on the data: bank lines and batches dated in the range, per status. */
    private static Map<String, Long> expectedStatistics(List<Batch> batches, Sums sums, Shape shape, LocalDate graceStart) {
        Map<String, Long> expected = new TreeMap<>();
        CurrencyCode lira = CurrencyCode.of(TRY);
        for (ItemSide side : List.of(ItemSide.BANK, ItemSide.BATCH)) {
            for (ItemStatus status : ItemStatus.values()) {
                expected.put(ItemStatistics.countKey(side, lira, status), 0L);
                expected.put(ItemStatistics.sumKey(side, lira, status), 0L);
            }
        }
        for (Batch batch : batches) {
            if (batch.currentDate().isBefore(shape.from)) {
                continue;
            }
            long sum = sums.all()[batch.number()];
            switch (batch.kind()) {
                case HISTORY -> add(expected, ItemSide.BATCH, ItemStatus.MATCHED, 1, sum);
                case EXACT -> {
                    add(expected, ItemSide.BATCH, ItemStatus.MATCHED, 1, sum);
                    add(expected, ItemSide.BANK, ItemStatus.MATCHED, 1, sum);
                }
                case CONFLICT -> {
                    add(expected, ItemSide.BATCH, ItemStatus.BROKEN, 1, sum);
                    add(expected, ItemSide.BANK, ItemStatus.BROKEN, 1, sum + OVERPAID);
                }
                case DUPLICATE -> {
                    add(expected, ItemSide.BATCH, ItemStatus.BROKEN, 1, sum);
                    add(expected, ItemSide.BANK, ItemStatus.BROKEN, 2, 2 * sum);
                }
                case UNPAID -> add(expected, ItemSide.BATCH,
                        batch.date().isBefore(graceStart) ? ItemStatus.BROKEN : ItemStatus.PENDING, 1, sum);
            }
        }
        long days = days(shape.from, TODAY);
        add(expected, ItemSide.BANK, ItemStatus.BROKEN, 2 * days, days * (FEE + UNKNOWN_BATCH));
        expected.put(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 0L);
        expected.put(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L);
        return expected;
    }

    private static void add(Map<String, Long> statistics, ItemSide side, ItemStatus status, long items, long amount) {
        CurrencyCode lira = CurrencyCode.of(TRY);
        statistics.merge(ItemStatistics.countKey(side, lira, status), items, Long::sum);
        statistics.merge(ItemStatistics.sumKey(side, lira, status), amount, Long::sum);
    }

    private static long days(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).count();
    }

    /** The data's shape, for the record: batches by kind, the late lines, the bank lines and the history. */
    private static String describe(List<Batch> batches) {
        Map<Kind, Long> byKind = new TreeMap<>();
        long late = 0;
        long bankLines = 2 * days(DAILY_FROM, TODAY);
        for (Batch batch : batches) {
            byKind.merge(batch.kind(), 1L, Long::sum);
            late += batch.lateLineDate().isPresent() ? 1 : 0;
            bankLines += batch.kind().bankLines();
        }
        return String.format(Locale.ROOT, "batches by kind %s (history from %s, daily range from %s), %d history batches "
                        + "with a late line, %d bank lines (%d fees and %d naming an unknown batch), %d history matches",
                byKind, FIRST_DAY, DAILY_FROM, late, bankLines, days(DAILY_FROM, TODAY), days(DAILY_FROM, TODAY),
                byKind.get(Kind.HISTORY));
    }

    /**
     * Writes the files, the batches' lines, the bank lines, and the history's runs and B1 matches,
     * then vacuums and analyses the tables, as the owner of the schema. Every value is fake; a line's
     * number decides its batch and amount, a batch's number its source, day and kind. Each statement
     * is asserted to have written the rows the batches call for, so a mistake fails here.
     */
    private static void load(List<Batch> batches) throws SQLException {
        long historyBatches = batches.stream().filter(batch -> batch.kind() == Kind.HISTORY).count();
        long bankLines = batches.stream().mapToLong(batch -> batch.kind().bankLines()).sum() + 2 * days(DAILY_FROM, TODAY);
        try (Connection connection = database.connectAsMigrator()) {
            connection.setAutoCommit(false);
            JdbcClient owner = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            owner.sql("SET search_path TO " + ReconPostgres.SCHEMA).update();
            owner.sql("""
                    CREATE TEMPORARY TABLE perf_batch (
                        number INTEGER PRIMARY KEY, source_code TEXT NOT NULL, batch_id TEXT NOT NULL,
                        batch_date DATE NOT NULL, kind TEXT NOT NULL, late_date DATE NULL
                    ) ON COMMIT DROP
                    """).update();
            assertThat(owner.sql("""
                            INSERT INTO perf_batch (number, source_code, batch_id, batch_date, kind, late_date)
                            SELECT * FROM unnest(CAST(:numbers AS INTEGER[]), CAST(:sources AS TEXT[]),
                                                 CAST(:batchIds AS TEXT[]), CAST(:dates AS DATE[]),
                                                 CAST(:kinds AS TEXT[]), CAST(:lateDates AS DATE[]))
                            """)
                    .param("numbers", new SqlArrayValue("integer", batches.stream().map(Batch::number).toArray()))
                    .param("sources", new SqlArrayValue("text", batches.stream().map(Batch::source).toArray()))
                    .param("batchIds", new SqlArrayValue("text", batches.stream().map(Batch::batchId).toArray()))
                    .param("dates", new SqlArrayValue("text", batches.stream().map(batch -> batch.date().toString()).toArray()))
                    .param("kinds", new SqlArrayValue("text", batches.stream().map(batch -> batch.kind().name()).toArray()))
                    .param("lateDates", new SqlArrayValue("text", batches.stream()
                            .map(batch -> batch.lateLineDate().map(LocalDate::toString).orElse(null)).toArray()))
                    .update()).isEqualTo(BATCHES);

            Map<String, UUID> files = new LinkedHashMap<>();
            for (String source : List.of(ALPHA, BETA, BANK)) {
                UUID fileId = UUID.randomUUID();
                files.put(source, fileId);
                assertThat(owner.sql("""
                                INSERT INTO statement_files (id, source_code, statement_reference, sha256, sanitized_filename,
                                                             size_bytes, line_count, status, error_summary, uploaded_by,
                                                             received_at)
                                VALUES (:id, :source, 'PERF-STAGE-B', repeat(:digit, 64), 'perf.csv', 0, 0, 'INGESTED', NULL,
                                        :operator, now())
                                """)
                        .param("id", fileId)
                        .param("source", source)
                        .param("digit", Integer.toString(files.size()))
                        .param("operator", OPERATOR)
                        .update()).isOne();
            }

            assertThat(owner.sql("""
                            INSERT INTO psp_lines (id, file_id, source_code, line_id, reference, batch_id, type,
                                                   transaction_date, value_date, gross_amount, fee_amount, net_amount, currency)
                            SELECT gen_random_uuid(), CASE WHEN batch.source_code = :alpha THEN :alphaFile ELSE :betaFile END,
                                   batch.source_code, 'L' || lpad(CAST(line.n AS TEXT), 7, '0'), NULL, batch.batch_id,
                                   'PAYMENT', dated.value_date, dated.value_date, line.gross, line.gross / 40,
                                   line.gross - line.gross / 40, :currency
                              FROM (SELECT n, (n - 1) / :perBatch + 1 AS number, CAST(10000 + (37 * n) % 90000 AS BIGINT) AS gross
                                      FROM generate_series(1, :lines) AS n) line
                              JOIN perf_batch batch ON batch.number = line.number
                             CROSS JOIN LATERAL (SELECT CASE WHEN batch.late_date IS NOT NULL AND line.n % :perBatch = 0
                                                             THEN batch.late_date ELSE batch.batch_date END AS value_date) dated
                            """)
                    .param("alpha", ALPHA)
                    .param("alphaFile", files.get(ALPHA))
                    .param("betaFile", files.get(BETA))
                    .param("currency", TRY)
                    .param("perBatch", LINES_PER_BATCH)
                    .param("lines", LINES)
                    .update()).isEqualTo(LINES);

            owner.sql("""
                    CREATE TEMPORARY TABLE perf_batch_sum (
                        number INTEGER PRIMARY KEY, amount BIGINT NOT NULL, on_its_day BIGINT NOT NULL,
                        lines_on_its_day INTEGER NOT NULL
                    ) ON COMMIT DROP
                    """).update();
            assertThat(owner.sql("""
                    INSERT INTO perf_batch_sum (number, amount, on_its_day, lines_on_its_day)
                    SELECT batch.number, sum(line.net_amount),
                           sum(line.net_amount) FILTER (WHERE line.value_date = batch.batch_date),
                           count(*) FILTER (WHERE line.value_date = batch.batch_date)
                      FROM psp_lines line
                      JOIN perf_batch batch ON batch.source_code = line.source_code AND batch.batch_id = line.batch_id
                     GROUP BY batch.number
                    """).update()).isEqualTo(BATCHES);

            assertThat(owner.sql("""
                            INSERT INTO bank_lines (id, file_id, source_code, line_id, booking_date, value_date, amount,
                                                    currency, reference, extracted_batch_id, description)
                            SELECT gen_random_uuid(), :bankFile, :bank, 'S-' || batch.batch_id || '-' || claim, batch.batch_date,
                                   batch.batch_date,
                                   CASE batch.kind WHEN 'HISTORY' THEN total.on_its_day
                                                   WHEN 'CONFLICT' THEN total.amount + :overpaid
                                                   ELSE total.amount END,
                                   :currency, 'BATCH-' || batch.batch_id, batch.batch_id, NULL
                              FROM perf_batch batch
                              JOIN perf_batch_sum total ON total.number = batch.number
                             CROSS JOIN LATERAL generate_series(1, CASE batch.kind WHEN 'UNPAID' THEN 0
                                                                                   WHEN 'DUPLICATE' THEN 2
                                                                                   ELSE 1 END) AS claim
                            UNION ALL
                            SELECT gen_random_uuid(), :bankFile, :bank, kind.prefix || to_char(day.d, 'YYYYMMDD'), day.d, day.d,
                                   kind.amount, :currency, kind.reference_prefix || to_char(day.d, 'YYYYMMDD'),
                                   kind.batch_prefix || to_char(day.d, 'YYYYMMDD'), kind.description
                              FROM (SELECT CAST(d AS DATE) AS d
                                      FROM generate_series(CAST(:dailyFrom AS TIMESTAMP), CAST(:today AS TIMESTAMP), INTERVAL '1 day') AS d) day
                             CROSS JOIN (VALUES ('FEE-', CAST(:fee AS BIGINT), CAST(NULL AS TEXT), CAST(NULL AS TEXT), 'Account fee'),
                                                ('UNKNOWN-', CAST(:unknownBatch AS BIGINT), 'BATCH-PX', 'PX', NULL))
                                   AS kind(prefix, amount, reference_prefix, batch_prefix, description)
                            """)
                    .param("bankFile", files.get(BANK))
                    .param("bank", BANK)
                    .param("overpaid", OVERPAID)
                    .param("currency", TRY)
                    .param("dailyFrom", DAILY_FROM)
                    .param("today", TODAY)
                    .param("fee", FEE)
                    .param("unknownBatch", UNKNOWN_BATCH)
                    .update()).isEqualTo(bankLines);

            // One completed run per day of the history, each having matched that day's batches.
            assertThat(owner.sql("""
                            INSERT INTO reconciliation_runs (id, source_code, value_date_from, value_date_to, status,
                                                             config_snapshot, stats, started_at, finished_at, triggered_by)
                            SELECT gen_random_uuid(), :bank, CAST(d AS DATE), CAST(d AS DATE), 'COMPLETED', CAST('{}' AS JSONB),
                                   CAST('{}' AS JSONB), d AT TIME ZONE 'Europe/Istanbul' + INTERVAL '18 hours',
                                   d AT TIME ZONE 'Europe/Istanbul' + INTERVAL '18 hours', :actor
                              FROM generate_series(CAST(:firstDay AS TIMESTAMP), CAST(:lastHistoryDay AS TIMESTAMP), INTERVAL '1 day') AS d
                            """)
                    .param("bank", BANK)
                    .param("actor", Actor.SYSTEM.name())
                    .param("firstDay", FIRST_DAY)
                    .param("lastHistoryDay", DAILY_FROM.minusDays(1))
                    .update()).isEqualTo(days(FIRST_DAY, DAILY_FROM.minusDays(1)));

            RuleId b1 = StageBRule.B1_BATCH_TOTAL.matchRule().orElseThrow();
            assertThat(owner.sql("WITH pair AS MATERIALIZED (SELECT gen_random_uuid() AS match_id, run.id AS run_id, "
                            + "paid.id AS bank_id, " + batchItemId("batch.source_code", "batch.batch_id") + " AS batch_item, "
                            + """
                            total.lines_on_its_day AS lines, run.finished_at AS at
                              FROM perf_batch batch
                              JOIN perf_batch_sum total ON total.number = batch.number
                              JOIN bank_lines paid ON paid.source_code = :bank AND paid.extracted_batch_id = batch.batch_id
                              JOIN reconciliation_runs run ON run.source_code = :bank AND run.value_date_from = batch.batch_date
                             WHERE batch.kind = 'HISTORY'
                            ),
                            new_match AS (
                                INSERT INTO matches (id, run_id, rule_id, rule_version, cardinality, status, amount_difference,
                                                     currency, low_confidence, created_at, batch_line_count)
                                SELECT match_id, run_id, :ruleId, :ruleVersion, :cardinality, 'ACTIVE', 0, :currency, FALSE,
                                       at, lines
                                  FROM pair
                            ),
                            new_item AS (
                                INSERT INTO match_items (match_id, side, item_id, active)
                                SELECT match_id, 'BATCH', batch_item, TRUE FROM pair
                                UNION ALL
                                SELECT match_id, 'BANK', bank_id, TRUE FROM pair
                            ),
                            new_event AS (
                                INSERT INTO match_events (match_id, event_type, actor, reason, occurred_at)
                                SELECT match_id, 'CREATED', :actor, NULL, at FROM pair
                                RETURNING match_id
                            )
                            SELECT count(*) FROM new_event
                            """)
                    .param("bank", BANK)
                    .param("ruleId", b1.name())
                    .param("ruleVersion", StageBRule.B1_BATCH_TOTAL.version())
                    .param("cardinality", b1.cardinality().name())
                    .param("currency", TRY)
                    .param("actor", Actor.SYSTEM.name())
                    .query(Long.class).single()).isEqualTo(historyBatches);
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM (ANALYZE) statement_files, psp_lines, bank_lines, reconciliation_runs, matches, "
                        + "match_items, match_events, breaks, break_events");
            }
        }
    }

    /**
     * A diagnostic beside the measurement: each Stage B statement of the run under EXPLAIN (ANALYZE,
     * BUFFERS), in the run's order, as the application's role and at the run's isolation level, for a
     * run row of its own, in one transaction that is rolled back. Each statement sees what the ones
     * before it wrote, as in the run. The statements are read from the store, so what is explained is
     * what the run executes.
     */
    private Map<String, List<String>> explainEachStatement(Shape shape, LocalDate graceStart) {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return snapshot.execute(status -> {
            status.setRollbackOnly();
            UUID runId = UUID.randomUUID();
            runs.insert(ReconciliationRun.start(runId, SourceCode.of(BANK), shape.from, TODAY, Map.of(), FixedRunClock.NOW,
                    OPERATOR));
            Map<String, List<String>> plans = new LinkedHashMap<>();
            STATEMENTS.forEach((method, constant) -> plans.put(method,
                    jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + statement(constant))
                            .params(parameters(runId, shape, graceStart))
                            .query(String.class).list()));
            return plans;
        });
    }

    /**
     * Every parameter any Stage B statement takes, with the values the run binds; a statement uses
     * the ones it names. A new map each time, as the store binds a new array for each statement.
     */
    private static Map<String, Object> parameters(UUID runId, Shape shape, LocalDate graceStart) {
        RuleId b1 = StageBRule.B1_BATCH_TOTAL.matchRule().orElseThrow();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("runId", runId);
        parameters.put("bank", BANK);
        parameters.put("pspSources", new SqlArrayValue("text", ALPHA, BETA));
        parameters.put("from", shape.from);
        parameters.put("to", TODAY);
        parameters.put("actor", Actor.SYSTEM.name());
        parameters.put("at", FixedRunClock.NOW.atOffset(ZoneOffset.UTC));
        parameters.put("ruleId", b1.name());
        parameters.put("ruleVersion", StageBRule.B1_BATCH_TOTAL.version());
        parameters.put("cardinality", b1.cardinality().name());
        parameters.put("lowConfidence", b1.lowConfidence());
        parameters.put("reason", "Matched by run " + runId);
        parameters.put("batchGraceStart", graceStart);
        return parameters;
    }

    /** Removes the rows the rolled-back rehearsal left dead, so the timed run reads the tables as loaded. */
    private static void vacuumRunTables() throws SQLException {
        try (Connection connection = database.connectAsMigrator();
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + ReconPostgres.SCHEMA);
            statement.execute("VACUUM (ANALYZE) reconciliation_runs, matches, match_items, match_events, breaks, break_events");
        }
    }

    /** Writes every statement's plan, and the slowest one's apart, under target/; returns the latter's path. */
    private static Path writePlans(Shape shape, Map<String, List<String>> plans, String slowest) throws IOException {
        Files.createDirectories(OUTPUT);
        StringBuilder all = new StringBuilder();
        plans.forEach((method, plan) -> all.append("== ").append(method).append(" (").append(STATEMENTS.get(method))
                .append("), timed run ").append(String.format(Locale.ROOT, "%.3f s", spent(method))).append('\n')
                .append(String.join("\n", plan)).append("\n\n"));
        Files.writeString(OUTPUT.resolve(shape.label() + "-explain-all.txt"), all, StandardCharsets.UTF_8);
        Path slowestPlan = OUTPUT.resolve(shape.label() + "-explain-slowest.txt");
        Files.writeString(slowestPlan, "== " + slowest + " (" + STATEMENTS.get(slowest) + ")\n"
                + String.join("\n", plans.get(slowest)) + "\n", StandardCharsets.UTF_8);
        return slowestPlan;
    }

    private static String executionTime(List<String> plan) {
        return plan.stream().filter(line -> line.startsWith("Execution Time")).findFirst().orElse("no execution time");
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        statements.put("matchBatchTotals", "MATCH_BY_BATCH_TOTAL");
        statements.put("openBankLineBreaks", "OPEN_BANK_LINE_BREAKS");
        statements.put("openChangedBatchBreaks", "OPEN_CHANGED_BATCH_BREAKS");
        statements.put("resolveMatchedLate", "RESOLVE_MATCHED_LATE");
        statements.put("openGraceBreaks", "OPEN_GRACE_BREAKS");
        statements.put("itemTotals", "ITEM_TOTALS");
        return statements;
    }

    /** A statement JdbcStageBStore runs, which is the adapter's own constant and not public. */
    private static String statement(String constant) {
        try {
            Field statement = Class.forName("com.baran.recon.adapters.out.persistence.JdbcStageBStore")
                    .getDeclaredField(constant);
            statement.setAccessible(true);
            return (String) statement.get(null);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException(constant + " is no longer where the diagnostic reads it", missing);
        }
    }

    /** The batch item id in SQL, from the adapter's one form of it (BatchItemIdSql), which is not public. */
    private static String batchItemId(String sourceCode, String batchId) {
        try {
            Method of = Class.forName("com.baran.recon.adapters.out.persistence.BatchItemIdSql")
                    .getDeclaredMethod("of", String.class, String.class);
            of.setAccessible(true);
            return (String) of.invoke(null, sourceCode, batchId);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("BatchItemIdSql.of is no longer where the load reads it", missing);
        }
    }

    private static double spent(String method) {
        AtomicLong nanos = SPENT.get(method);
        return nanos == null ? 0 : seconds(nanos.get());
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static void resetPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    /** The sum of each heap pool's peak, an upper bound on the heap used at any one moment. */
    private static long peakHeapUsed() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeTheStores {

        @Bean
        static BeanPostProcessor timeStageBAndRunStores() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    Class<?> port = bean instanceof StageBStore ? StageBStore.class
                            : bean instanceof RunStore ? RunStore.class
                            : bean instanceof LedgerEntryStore ? LedgerEntryStore.class
                            : null;
                    if (port == null) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> {
                        long called = System.nanoTime();
                        FIRST_CALLED.computeIfAbsent(method.getName(), name -> new AtomicLong(called));
                        try {
                            return method.invoke(bean, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        } finally {
                            long returned = System.nanoTime();
                            SPENT.computeIfAbsent(method.getName(), name -> new AtomicLong()).addAndGet(returned - called);
                            LAST_RETURNED.computeIfAbsent(method.getName(), name -> new AtomicLong()).set(returned);
                        }
                    });
                }
            };
        }
    }
}
