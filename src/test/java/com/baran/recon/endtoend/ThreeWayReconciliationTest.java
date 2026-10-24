package com.baran.recon.endtoend;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.adapters.in.kafka.LedgerTopics;
import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.MatchStore;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.run.AutomaticRunTrigger;
import com.baran.recon.application.run.ScheduledRuns;
import com.baran.recon.domain.breaks.BatchFigures;
import com.baran.recon.domain.breaks.Break;
import com.baran.recon.domain.breaks.BreakDetails;
import com.baran.recon.domain.breaks.BreakStatus;
import com.baran.recon.domain.item.BatchKey;
import com.baran.recon.domain.item.ItemRef;
import com.baran.recon.domain.item.ItemSide;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.match.Match;
import com.baran.recon.domain.match.MatchStatus;
import com.baran.recon.domain.money.Money;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.source.ConfiguredSources;
import com.baran.recon.support.Multipart;
import com.baran.recon.support.ReconKafka;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

/**
 * TDD 13, 14 (Milestone 6's exit criterion): the full three-way flow on a small hand-written dataset,
 * every item's outcome asserted one by one. The ledger's events arrive through Kafka, as the ledger's
 * relay publishes them; the PSP file and the bank file are uploaded over HTTP as an operator; the
 * automatic trigger, on in this context as in the application, runs the sources after each file; and a
 * week later the daily scheduled run, over its look-back, opens the grace breaks no file-triggered run
 * reached. The context's clock is the test's, which it moves from day to day.
 *
 * <p>The money, followed from ledger to bank (amounts in TRY; the ledger and the PSP's gross agree, the
 * PSP's fee comes off its gross, and the bank pays each batch's net):
 * <pre>
 *   ledger entry          PSP line                       batch    bank line
 *   L1 1250.00 (Tue 6th)  P1 gross 1250.00 fee 25.00     B1       S1 2009.00 = 1225.00 + 784.00   B1 match
 *   L2  800.00 (Tue 6th)  P2 gross  800.00 fee 16.00     B1
 *   L3  500.00 (Tue 6th)  (none)                                                                   MISSING_IN_PSP
 *   L4 2100.00 (Wed 7th)  P4 gross 2100.00 fee 42.00     B2       S2 2350.00, the batch nets 2352.00  B2 conflict
 *   (none)                P6 gross  300.00 fee  6.00     B2                                        MISSING_IN_LEDGER
 *   L5  600.00 (Wed 7th)  P5 gross  600.00 fee 12.00     B3       (none: never paid)               MISSING_SETTLEMENT
 *                                                                 S3 -12.50, an account fee        UNEXPECTED_BANK_LINE
 * </pre>
 * Each PSP line refers to its ledger entry by the entry's transaction id, so L1..L5 meet P1..P5 by A1;
 * P6's reference is a transaction the ledger never wrote, and no unmatched entry has its amount for A3.
 * The PSP lines are dated the day after their entries; the bank lines are dated Friday the 9th.
 *
 * <p>The days: on Friday the 9th the PSP file arrives, and its runs cover its dates, the 7th and 8th;
 * on Monday the 12th the bank file arrives, and the bank's run covers the 9th. Neither file's runs
 * see L3 (dated the 6th) or find P6, B2 or B3 past their grace. On Monday the 19th, with no file since,
 * the scheduled run of each source covers the 30 days back to 19 September.
 *
 * <p>The context listens to the ledger's topic, so it is closed when the class ends (as every
 * listening context is, {@link ReconKafka#registerListening}). Its sources, the clearing account and
 * the event ids are this class's alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.matching.automatic-trigger.busy-retry-interval=50ms",
        "recon.sources[0].code=PSP_THREE_WAY",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[0].ledger-accounts[0]=" + ThreeWayReconciliationTest.CLEARING_ACCOUNT,
        "recon.sources[0].value-date-window-days=2",
        "recon.sources[0].grace-days-ledger-unmatched=3",
        "recon.sources[0].grace-days-psp-unmatched=1",
        "recon.sources[0].settles-to=BANK_THREE_WAY",
        "recon.sources[1].code=BANK_THREE_WAY",
        "recon.sources[1].type=BANK_STATEMENT",
        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})",
        "recon.sources[1].grace-days-batch-unpaid=2"})
@ActiveProfiles("test")
@DirtiesContext
@Import(ThreeWayReconciliationTest.MovableClockInjection.class)
@DisplayName("TDD 13, 14 (Milestone 6): the three-way flow from ledger events, a PSP file and a bank file, item by item")
class ThreeWayReconciliationTest {

    static final String CLEARING_ACCOUNT = "00000000-0000-4000-8000-0000000e2e00";

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final SourceCode PSP = SourceCode.of("PSP_THREE_WAY");
    private static final SourceCode BANK = SourceCode.of("BANK_THREE_WAY");

    /** Event ids, and the ledger's entry ids with them, no other test class uses. */
    private static final long FIRST_EVENT_ID = 9_950_000_000L;

    /** Noon in Istanbul: the PSP file arrives on Friday the 9th, the bank file on Monday the 12th. */
    private static final Instant PSP_FILE_ARRIVES = Instant.parse("2026-10-09T09:00:00Z");
    private static final Instant BANK_FILE_ARRIVES = Instant.parse("2026-10-12T09:00:00Z");
    /** Noon in Istanbul on Monday the 19th, a week after the last file, when the daily run is queued. */
    private static final Instant SCHEDULED_RUN = Instant.parse("2026-10-19T09:00:00Z");

    // Each ledger entry's transaction id; a PSP line carries it as its reference. T6 is never written.
    private static final String T1 = "00000000-0000-4000-8000-0000000e2e01";
    private static final String T2 = "00000000-0000-4000-8000-0000000e2e02";
    private static final String T3 = "00000000-0000-4000-8000-0000000e2e03";
    private static final String T4 = "00000000-0000-4000-8000-0000000e2e04";
    private static final String T5 = "00000000-0000-4000-8000-0000000e2e05";
    private static final String T6 = "00000000-0000-4000-8000-0000000e2e06";

    /** The ledger's events, by name: transaction, amount in kuruş, created_at (10:00 in Istanbul). */
    private static final List<LedgerEvent> LEDGER = List.of(
            new LedgerEvent("L1", T1, 125_000, "2026-10-06T07:00:00.000000Z"),
            new LedgerEvent("L2", T2, 80_000, "2026-10-06T07:05:00.000000Z"),
            new LedgerEvent("L3", T3, 50_000, "2026-10-06T07:10:00.000000Z"),
            new LedgerEvent("L4", T4, 210_000, "2026-10-07T07:00:00.000000Z"),
            new LedgerEvent("L5", T5, 60_000, "2026-10-07T07:05:00.000000Z"));

    /** The PSP's settlement report: B1 nets 2009.00, B2 2352.00, B3 588.00. */
    private static final String PSP_FILE = """
            line_id,transaction_reference,batch_id,transaction_date,value_date,type,gross_amount,fee_amount,net_amount,currency
            P1,%s,E2E-B1,2026-10-06,2026-10-07,PAYMENT,1250.00,25.00,1225.00,TRY
            P2,%s,E2E-B1,2026-10-06,2026-10-07,PAYMENT,800.00,16.00,784.00,TRY
            P4,%s,E2E-B2,2026-10-07,2026-10-08,PAYMENT,2100.00,42.00,2058.00,TRY
            P6,%s,E2E-B2,2026-10-07,2026-10-08,PAYMENT,300.00,6.00,294.00,TRY
            P5,%s,E2E-B3,2026-10-07,2026-10-08,PAYMENT,600.00,12.00,588.00,TRY
            """.formatted(T1, T2, T4, T6, T5);

    /** The bank's statement: B1 paid exactly, B2 paid 2.00 short, an account fee, and B3 not at all. */
    private static final String BANK_FILE = """
            line_id,booking_date,value_date,amount,currency,reference,description
            S1,2026-10-09,2026-10-09,2009.00,TRY,PSP THREE WAY BATCH-E2E-B1 payout,Settlement Test Merchant 001
            S2,2026-10-09,2026-10-09,2350.00,TRY,PSP THREE WAY BATCH-E2E-B2 payout,Settlement Test Merchant 001
            S3,2026-10-09,2026-10-09,-12.50,TRY,Monthly account fee,Account fee
            """;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        ReconKafka.registerListening(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MovableClock clock;

    @Autowired
    private RunTrigger runTrigger;

    @Autowired
    private ConfiguredSources sources;

    @Autowired
    private RunStore runs;

    @Autowired
    private MatchStore matches;

    @Autowired
    private BreakStore breaks;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("A1_EXACT_REFERENCE, MISSING_IN_PSP, MISSING_IN_LEDGER, B1_BATCH_TOTAL, B2_BATCH_CONFLICT, "
            + "UNEXPECTED_BANK_LINE and MISSING_SETTLEMENT (TDD 8.2) from events, files and the scheduled run (TDD 5.3): "
            + "each item's status, match and break, and each run's statistics (INV-1, INV-4)")
    void reconcilesTheDatasetFromLedgerToBank() throws Exception {
        publishLedgerEvents();

        clock.set(PSP_FILE_ARRIVES);
        assertThat(upload(PSP, "STMT-E2E-PSP-001", PSP_FILE)).as("the PSP file is ingested").isEqualTo(201);
        await().atMost(WAIT).untilAsserted(() -> assertThat(completedRuns()).containsExactly(
                "PSP_THREE_WAY 2026-10-07 2026-10-08 system",
                "BANK_THREE_WAY 2026-10-07 2026-10-08 system"));
        assertThat(openedBreaks()).as("the PSP file's runs find nothing past its grace").isEmpty();

        clock.set(BANK_FILE_ARRIVES);
        assertThat(upload(BANK, "STMT-E2E-BANK-001", BANK_FILE)).as("the bank file is ingested").isEqualTo(201);
        await().atMost(WAIT).untilAsserted(() -> assertThat(completedRuns()).hasSize(3)
                .endsWith("BANK_THREE_WAY 2026-10-09 2026-10-09 system"));
        assertThat(openedBreaks()).as("the bank file's run judges its own lines, and no grace break")
                .containsExactlyInAnyOrder("BATCH_AMOUNT_MISMATCH on BANK S2", "UNEXPECTED_BANK_LINE on BANK S3");

        clock.set(SCHEDULED_RUN);
        new ScheduledRuns(sources, (AutomaticRunTrigger) runTrigger, clock, ISTANBUL, 30).queueDailyRuns();
        await().atMost(WAIT).untilAsserted(() -> assertThat(completedRuns()).containsExactly(
                "PSP_THREE_WAY 2026-10-07 2026-10-08 system",
                "BANK_THREE_WAY 2026-10-07 2026-10-08 system",
                "BANK_THREE_WAY 2026-10-09 2026-10-09 system",
                "PSP_THREE_WAY 2026-09-19 2026-10-19 system",
                "BANK_THREE_WAY 2026-09-19 2026-10-19 system"));

        Items items = items();
        // Stage A: four exact references; L3 never reached the PSP; P6 is a payment the ledger never saw.
        items.assertItem("LEDGER L1", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [PSP P1], difference 0 TRY", "none");
        items.assertItem("LEDGER L2", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [PSP P2], difference 0 TRY", "none");
        items.assertItem("LEDGER L3", "BROKEN", "none", "MISSING_IN_PSP OPEN by run 4, related [], no details");
        items.assertItem("LEDGER L4", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [PSP P4], difference 0 TRY", "none");
        items.assertItem("LEDGER L5", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [PSP P5], difference 0 TRY", "none");
        items.assertItem("PSP P1", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [LEDGER L1], difference 0 TRY", "none");
        items.assertItem("PSP P2", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [LEDGER L2], difference 0 TRY", "none");
        items.assertItem("PSP P4", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [LEDGER L4], difference 0 TRY", "none");
        items.assertItem("PSP P5", "MATCHED", "A1_EXACT_REFERENCE v1 by run 1 with [LEDGER L5], difference 0 TRY", "none");
        items.assertItem("PSP P6", "BROKEN", "none", "MISSING_IN_LEDGER OPEN by run 4, related [], no details");
        // Stage B: B1 paid exactly by S1; S2 pays B2 2.00 less than its net, which names B2 and so keeps
        // it from a grace break; S3 is a fee naming no batch; B3 was never paid.
        items.assertItem("BATCH B1", "MATCHED",
                "B1_BATCH_TOTAL v1 by run 3 with [BANK S1], difference 0 TRY, 2 lines", "none");
        items.assertItem("BATCH B2", "BROKEN", "none", "none");
        items.assertNamedBy("BATCH B2", "BATCH_AMOUNT_MISMATCH on BANK S2");
        items.assertItem("BATCH B3", "BROKEN", "none", "MISSING_SETTLEMENT OPEN by run 5, related [], no details");
        items.assertItem("BANK S1", "MATCHED",
                "B1_BATCH_TOTAL v1 by run 3 with [BATCH B1], difference 0 TRY, 2 lines", "none");
        items.assertItem("BANK S2", "BROKEN", "none", "BATCH_AMOUNT_MISMATCH OPEN by run 3, related [BATCH B2], "
                + "BATCH_TOTAL_CONFLICT bank 235000 TRY, batch 2 lines [235200 TRY]");
        items.assertItem("BANK S3", "BROKEN", "none", "UNEXPECTED_BANK_LINE OPEN by run 3, related [], no details");

        List<SortedMap<String, Long>> stats = runStatistics();
        assertThat(stats.get(0)).as("run 1, the PSP file's Stage A over the 7th and 8th: L4 and L5 in scope, "
                + "P6 within its grace").containsOnly(
                entry("ledger.TRY.matched.count", 2L), entry("ledger.TRY.matched.sum", 270_000L),
                entry("ledger.TRY.broken.count", 0L), entry("ledger.TRY.broken.sum", 0L),
                entry("ledger.TRY.pending.count", 0L), entry("ledger.TRY.pending.sum", 0L),
                entry("psp.TRY.matched.count", 4L), entry("psp.TRY.matched.sum", 475_000L),
                entry("psp.TRY.broken.count", 0L), entry("psp.TRY.broken.sum", 0L),
                entry("psp.TRY.pending.count", 1L), entry("psp.TRY.pending.sum", 30_000L),
                entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 2L),
                entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L));
        assertThat(stats.get(1)).as("run 2, the PSP file's bank run over the 7th and 8th: three batches waiting, "
                + "no bank line yet").containsOnly(
                entry("batch.TRY.matched.count", 0L), entry("batch.TRY.matched.sum", 0L),
                entry("batch.TRY.broken.count", 0L), entry("batch.TRY.broken.sum", 0L),
                entry("batch.TRY.pending.count", 3L), entry("batch.TRY.pending.sum", 494_900L),
                entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 0L),
                entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L));
        assertThat(stats.get(2)).as("run 3, the bank file's run over the 9th: its three lines, no batch dated then")
                .containsOnly(
                        entry("bank.TRY.matched.count", 1L), entry("bank.TRY.matched.sum", 200_900L),
                        entry("bank.TRY.broken.count", 2L), entry("bank.TRY.broken.sum", 233_750L),
                        entry("bank.TRY.pending.count", 0L), entry("bank.TRY.pending.sum", 0L),
                        entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 0L),
                        entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L));
        assertThat(stats.get(3)).as("run 4, the scheduled Stage A over 30 days: every entry and line").containsOnly(
                entry("ledger.TRY.matched.count", 4L), entry("ledger.TRY.matched.sum", 475_000L),
                entry("ledger.TRY.broken.count", 1L), entry("ledger.TRY.broken.sum", 50_000L),
                entry("ledger.TRY.pending.count", 0L), entry("ledger.TRY.pending.sum", 0L),
                entry("psp.TRY.matched.count", 4L), entry("psp.TRY.matched.sum", 475_000L),
                entry("psp.TRY.broken.count", 1L), entry("psp.TRY.broken.sum", 30_000L),
                entry("psp.TRY.pending.count", 0L), entry("psp.TRY.pending.sum", 0L),
                entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 5L),
                entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L));
        assertThat(stats.get(4)).as("run 5, the scheduled bank run over 30 days: every bank line and batch")
                .containsOnly(
                        entry("bank.TRY.matched.count", 1L), entry("bank.TRY.matched.sum", 200_900L),
                        entry("bank.TRY.broken.count", 2L), entry("bank.TRY.broken.sum", 233_750L),
                        entry("bank.TRY.pending.count", 0L), entry("bank.TRY.pending.sum", 0L),
                        entry("batch.TRY.matched.count", 1L), entry("batch.TRY.matched.sum", 200_900L),
                        entry("batch.TRY.broken.count", 2L), entry("batch.TRY.broken.sum", 294_000L),
                        entry("batch.TRY.pending.count", 0L), entry("batch.TRY.pending.sum", 0L),
                        entry(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE, 0L),
                        entry(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 0L));
    }

    /** The ledger's five events on the clearing account, each with its event-id header, then waits for each row. */
    private void publishLedgerEvents() throws Exception {
        try (KafkaProducer<String, byte[]> ledger = ReconKafka.ledgerProducer()) {
            for (int i = 0; i < LEDGER.size(); i++) {
                LedgerEvent event = LEDGER.get(i);
                long id = FIRST_EVENT_ID + i + 1;
                byte[] value = """
                        {"transaction_id":"%s","account_id":"%s","amount":%d,"currency":"TRY","tx_type":"TRANSFER",
                         "entry_id":%d,"created_at":"%s"}"""
                        .formatted(event.transactionId(), CLEARING_ACCOUNT, event.amount(), id, event.createdAt())
                        .getBytes(StandardCharsets.UTF_8);
                ledger.send(new ProducerRecord<>(LedgerTopics.ACCOUNT_ACTIVITY, null, CLEARING_ACCOUNT, value,
                        List.of(new RecordHeader("event-id", Long.toString(id).getBytes(StandardCharsets.UTF_8)))))
                        .get(30, TimeUnit.SECONDS);
            }
        }
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.sql("""
                        SELECT count(*) FROM ledger_entries
                         WHERE source_code = :source AND event_id BETWEEN :first AND :last
                        """)
                .param("source", PSP.value()).param("first", FIRST_EVENT_ID + 1)
                .param("last", FIRST_EVENT_ID + LEDGER.size()).query(Long.class).single())
                .as("each event stored once, for the PSP source").isEqualTo(LEDGER.size()));
    }

    /** Uploads a statement file over HTTP as the operator; returns the response's status. */
    private int upload(SourceCode source, String reference, String content) throws IOException, InterruptedException {
        Multipart body = new Multipart().field("source", source.value()).field("statementReference", reference)
                .file("file", "statement.csv", content);
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/statements"))
                    .header("Content-Type", body.contentType())
                    .header("Authorization", ReconUsers.operatorAuthorization())
                    .POST(body.publisher()).build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /** The two sources' completed runs in the order they started, as {@code source from to triggered_by}. */
    private List<String> completedRuns() {
        return jdbc.sql("""
                        SELECT source_code || ' ' || value_date_from || ' ' || value_date_to || ' ' || triggered_by
                          FROM reconciliation_runs
                         WHERE source_code IN (:psp, :bank) AND status = 'COMPLETED'
                         ORDER BY started_at, source_code DESC
                        """)
                .param("psp", PSP.value()).param("bank", BANK.value()).query(String.class).list();
    }

    /** Each run's statistics, in the order the runs started. */
    private List<SortedMap<String, Long>> runStatistics() {
        return runIds().stream().map(id -> runs.findById(id).orElseThrow().stats().orElseThrow()).toList();
    }

    private List<UUID> runIds() {
        return jdbc.sql("""
                        SELECT id FROM reconciliation_runs
                         WHERE source_code IN (:psp, :bank) AND status = 'COMPLETED'
                         ORDER BY started_at, source_code DESC
                        """)
                .param("psp", PSP.value()).param("bank", BANK.value()).query(UUID.class).list();
    }

    /** The breaks on the dataset's items so far, as {@code type on subject}. */
    private List<String> openedBreaks() {
        Items items = items();
        return items.breaks().stream().map(found -> found.type() + " on " + items.name(found.item())).toList();
    }

    /** Every item of the dataset by its name, with the matches and breaks that touch it. */
    private Items items() {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, ItemSide> sides = new HashMap<>();
        jdbc.sql("SELECT id, event_id FROM ledger_entries WHERE source_code = :source").param("source", PSP.value())
                .query((row, n) -> Map.entry(row.getObject("id", UUID.class), row.getLong("event_id")))
                .list().forEach(entry -> {
                    names.put(entry.getKey(), "LEDGER L" + (entry.getValue() - FIRST_EVENT_ID));
                    sides.put(entry.getKey(), ItemSide.LEDGER);
                });
        jdbc.sql("SELECT id, line_id FROM psp_lines WHERE source_code = :source").param("source", PSP.value())
                .query((row, n) -> Map.entry(row.getObject("id", UUID.class), row.getString("line_id")))
                .list().forEach(line -> {
                    names.put(line.getKey(), "PSP " + line.getValue());
                    sides.put(line.getKey(), ItemSide.PSP);
                });
        jdbc.sql("SELECT id, line_id FROM bank_lines WHERE source_code = :source").param("source", BANK.value())
                .query((row, n) -> Map.entry(row.getObject("id", UUID.class), row.getString("line_id")))
                .list().forEach(line -> {
                    names.put(line.getKey(), "BANK " + line.getValue());
                    sides.put(line.getKey(), ItemSide.BANK);
                });
        for (String batch : List.of("B1", "B2", "B3")) {
            UUID id = new BatchKey(PSP, "E2E-" + batch).itemId();
            names.put(id, "BATCH " + batch);
            sides.put(id, ItemSide.BATCH);
        }
        List<Match> found = jdbc.sql("SELECT DISTINCT match_id FROM match_items WHERE item_id IN (:items)")
                .param("items", List.copyOf(names.keySet())).query(UUID.class).list().stream()
                .map(id -> matches.findById(id).orElseThrow()).toList();
        List<Break> opened = jdbc.sql("SELECT id FROM breaks WHERE item_id IN (:items)")
                .param("items", List.copyOf(names.keySet())).query(UUID.class).list().stream()
                .map(id -> breaks.findById(id).orElseThrow()).toList();
        return new Items(names, sides, found, opened, runIds());
    }

    private record LedgerEvent(String name, String transactionId, long amount, String createdAt) {
    }

    /**
     * The dataset's items by name, and what touches them. An item's status follows TDD 8.2: MATCHED in
     * an active match; otherwise BROKEN as the subject of an unresolved break, or named among the
     * related items of one about an item of its own stage; otherwise PENDING.
     */
    private record Items(Map<UUID, String> names, Map<UUID, ItemSide> sides, List<Match> matches, List<Break> breaks,
                         List<UUID> runIds) {

        String name(ItemRef ref) {
            return names.getOrDefault(ref.id(), "unknown " + ref);
        }

        void assertItem(String item, String status, String match, String opened) {
            UUID id = idOf(item);
            assertThat(List.of(status(id), describeMatches(id), describeBreaks(id))).as(item)
                    .containsExactly(status, match, opened);
        }

        void assertNamedBy(String item, String... namers) {
            UUID id = idOf(item);
            assertThat(breaks.stream()
                    .filter(found -> found.relatedItems().stream().anyMatch(related -> related.id().equals(id)))
                    .map(found -> found.type() + " on " + name(found.item())).toList())
                    .as("the breaks naming %s", item).containsExactly(namers);
        }

        private UUID idOf(String item) {
            return names.entrySet().stream().filter(name -> name.getValue().equals(item)).map(Map.Entry::getKey)
                    .findFirst().orElseThrow(() -> new AssertionError("no item " + item));
        }

        private String status(UUID id) {
            if (matches.stream().anyMatch(match -> match.status() == MatchStatus.ACTIVE
                    && match.items().stream().anyMatch(item -> item.itemId().equals(id)))) {
                return "MATCHED";
            }
            boolean stageB = sides.get(id) == ItemSide.BANK || sides.get(id) == ItemSide.BATCH;
            boolean broken = breaks.stream().filter(found -> found.status() != BreakStatus.RESOLVED)
                    .filter(found -> (found.item().side() == ItemSide.BANK || found.item().side() == ItemSide.BATCH)
                            == stageB)
                    .anyMatch(found -> found.item().id().equals(id)
                            || found.relatedItems().stream().anyMatch(related -> related.id().equals(id)));
            return broken ? "BROKEN" : "PENDING";
        }

        private String describeMatches(UUID id) {
            List<String> described = new ArrayList<>();
            for (Match match : matches) {
                if (match.items().stream().noneMatch(item -> item.itemId().equals(id))) {
                    continue;
                }
                described.add(match.rule() + " v" + match.ruleVersion() + " by run " + (runIds.indexOf(match.runId()) + 1)
                        + " with " + match.items().stream().filter(item -> !item.itemId().equals(id))
                        .map(item -> name(new ItemRef(item.side(), item.itemId()))).sorted().toList()
                        + ", difference " + money(match.amountDifference())
                        + match.batchLineCount().stream().mapToObj(lines -> ", " + lines + " lines").findFirst().orElse("")
                        + (match.status() == MatchStatus.ACTIVE ? "" : ", " + match.status()));
            }
            return described.isEmpty() ? "none" : String.join("; ", described);
        }

        private String describeBreaks(UUID id) {
            List<String> described = new ArrayList<>();
            for (Break found : breaks) {
                if (!found.item().id().equals(id)) {
                    continue;
                }
                described.add(found.type() + " " + found.status()
                        + found.resolutionCode().map(code -> " " + code).orElse("")
                        + " by run " + found.openedRunId().map(run -> runIds.indexOf(run) + 1).orElse(0)
                        + ", related " + found.relatedItems().stream().map(this::name).sorted().toList()
                        + ", " + found.details().map(Items::details).orElse("no details"));
            }
            return described.isEmpty() ? "none" : String.join("; ", described);
        }

        private static String details(BreakDetails details) {
            return switch (details) {
                case BreakDetails.BatchTotalConflict conflict ->
                        "BATCH_TOTAL_CONFLICT bank " + money(conflict.bank()) + ", batch " + figures(conflict.batch());
                case BreakDetails.BatchChangedAfterMatch changed ->
                        "BATCH_CHANGED_AFTER_MATCH matched " + figures(changed.matched()) + ", current "
                                + figures(changed.current());
            };
        }

        private static String figures(BatchFigures figures) {
            return figures.lineCount() + " lines " + figures.sums().stream().map(Items::money).toList();
        }

        private static String money(Money money) {
            return money.minorUnits() + " " + money.currency();
        }
    }

    /** The context's clock, which the test sets to the day each file arrives and then a week later. */
    static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(PSP_FILE_ARRIVES);

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the application reads instants only");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockInjection {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
