package com.baran.recon.adapters.in.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.adapters.in.web.StatementApi.Response;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.port.RunTrigger;
import com.baran.recon.application.run.AutomaticRunTrigger;
import com.baran.recon.application.run.HeldLedgerEntryStore;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.bank;
import static com.baran.recon.application.statement.StatementFiles.bankLine;
import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * FR-MAT-1 over real HTTP, with the automatic trigger switched on: an upload's run, started on the
 * background thread after the ingestion commits. A run can be held inside its work transaction, after
 * its RUNNING row committed, so a test can see what the trigger does while its source is busy and
 * what the upload's response waits for. The queue holds one file, so a test can fill it. Each test
 * takes a source of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.matching.automatic-trigger.queue-capacity=1",
        "recon.matching.automatic-trigger.busy-retry-interval=50ms",
        "recon.sources[0].code=PSP_AFTER_UPLOAD_DONE",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=PSP_AFTER_UPLOAD_REJECTED",
        "recon.sources[1].type=PSP_SETTLEMENT",
        "recon.sources[2].code=PSP_AFTER_UPLOAD_BUSY",
        "recon.sources[2].type=PSP_SETTLEMENT",
        "recon.sources[3].code=PSP_AFTER_UPLOAD_FULL",
        "recon.sources[3].type=PSP_SETTLEMENT",
        "recon.sources[4].code=PSP_AFTER_UPLOAD_QUICK",
        "recon.sources[4].type=PSP_SETTLEMENT",
        "recon.sources[5].code=BANK_AFTER_UPLOAD_DONE",
        "recon.sources[5].type=BANK_STATEMENT",
        "recon.sources[5].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})"})
@ActiveProfiles("test")
@Import(HeldLedgerEntryStore.Injection.class)
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FR-MAT-1: a successful upload starts a run for its source and range in the background")
class RunAfterUploadTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @LocalServerPort
    private int port;

    @Autowired
    private RunMatching matching;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RunTrigger runTrigger;

    private StatementApi api;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @BeforeAll
    void createClient() {
        api = new StatementApi(port);
    }

    @AfterAll
    void closeClient() {
        api.close();
    }

    @Test
    @DisplayName("FR-MAT-1: with recon.matching.automatic-trigger.enabled=true the context's run trigger is the automatic "
            + "one; StatementUploadTest sees the test profile's NONE")
    void switchedOnTheTriggerIsTheAutomaticOne() {
        assertThat(runTrigger).isInstanceOf(AutomaticRunTrigger.class);
    }

    @Test
    @DisplayName("FR-MAT-1: an ingested PSP file leads to a COMPLETED run over its source and the range of its value "
            + "dates, triggered by the system")
    void ingestedFileLeadsToACompletedRun(CapturedOutput output) throws Exception {
        String source = "PSP_AFTER_UPLOAD_DONE";
        String id = unique();

        Response uploaded = api.upload(source, "STMT-" + id, psp(
                pspLine(id + "-1", "2026-09-24"), pspLine(id + "-2", "2026-09-22"), pspLine(id + "-3", "2026-09-23")));

        assertThat(uploaded.status()).isEqualTo(201);
        await().atMost(WAIT).untilAsserted(() -> assertThat(runsOf(source))
                .extracting(RunRow::status, RunRow::from, RunRow::to, RunRow::triggeredBy)
                .containsExactly(tuple("COMPLETED", "2026-09-22", "2026-09-24", "system")));
        assertThat(output).contains("for statement file " + uploaded.id("id") + " on source " + source + " is COMPLETED");
    }

    @Test
    @DisplayName("FR-MAT-1: an ingested bank file leads to a COMPLETED run for its source too")
    void ingestedBankFileLeadsToACompletedRun() throws Exception {
        String source = "BANK_AFTER_UPLOAD_DONE";
        String id = unique();

        Response uploaded = api.upload(source, "STMT-" + id, bank(List.of(bankLine(id + "-1", "2026-09-25"))));

        assertThat(uploaded.status()).isEqualTo(201);
        await().atMost(WAIT).untilAsserted(() -> assertThat(runsOf(source))
                .extracting(RunRow::status, RunRow::from, RunRow::to)
                .containsExactly(tuple("COMPLETED", "2026-09-25", "2026-09-25")));
    }

    @Test
    @DisplayName("FR-MAT-1: a rejected upload leads to no run; the next ingested file's run is its source's only one")
    void rejectedUploadLeadsToNoRun() throws Exception {
        String source = "PSP_AFTER_UPLOAD_REJECTED";
        String id = unique();
        Response rejected = api.upload(source, "STMT-" + id + "-A", psp(
                pspLine(id + "-1", "2026-09-21"), pspLine(id + "-2", "2026-09-21").replace(",TRY", ",ABC")));
        assertThat(rejected.status()).isEqualTo(422);

        Response ingested = api.upload(source, "STMT-" + id + "-B", psp(pspLine(id + "-3", "2026-09-28")));

        assertThat(ingested.status()).isEqualTo(201);
        // One thread takes the files in order, so a run for the rejected file would have come first.
        await().atMost(WAIT).untilAsserted(() -> assertThat(runsOf(source)).extracting(RunRow::status)
                .containsExactly("COMPLETED"));
        assertThat(runsOf(source)).extracting(RunRow::from, RunRow::to).containsExactly(tuple("2026-09-28", "2026-09-28"));
    }

    @Test
    @DisplayName("TDD 5.3: with a run in progress the triggered run waits, then runs once that run has finished, never "
            + "at the same time")
    void triggeredRunWaitsForTheRunningRun(CapturedOutput output) throws Exception {
        SourceCode source = SourceCode.of("PSP_AFTER_UPLOAD_BUSY");
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        held.hold(source);
        try {
            Future<ReconciliationRun> manual = thread.submit(() -> matching.run(source.value(),
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), ReconUsers.OPERATOR));
            assertThat(held.awaitEntered(1, WAIT)).as("the operator's run is held inside its work").isTrue();
            UUID manualId = runningRunOf(source.value());
            String id = unique();

            Response uploaded = api.upload(source.value(), "STMT-" + id, psp(pspLine(id + "-1", "2026-09-24")));

            assertThat(uploaded.status()).isEqualTo(201);
            await().atMost(WAIT).untilAsserted(() -> assertThat(output).contains("Run for statement file "
                    + uploaded.id("id") + " waits: source " + source.value() + " has a running run " + manualId));
            assertThat(runsOf(source.value())).as("the triggered run was refused while the other ran")
                    .extracting(RunRow::id).containsExactly(manualId);

            held.release(source);
            assertThat(manual.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            await().atMost(WAIT).untilAsserted(() -> assertThat(runsOf(source.value())).extracting(RunRow::status)
                    .containsExactly("COMPLETED", "COMPLETED"));
            List<RunRow> runs = runsOf(source.value());
            RunRow first = runs.get(0);
            RunRow triggered = runs.get(1);
            assertThat(first.id()).isEqualTo(manualId);
            assertThat(triggered.triggeredBy()).isEqualTo("system");
            assertThat(triggered.startedAt()).as("the triggered run started after the other had finished")
                    .isAfterOrEqualTo(first.finishedAt());
        } finally {
            held.release(source);
            thread.shutdownNow();
        }
    }

    @Test
    @DisplayName("FR-MAT-1: with the thread busy and its queue full, the next upload is still 201; its run is refused "
            + "and logged at WARN with the file's id, and the queued runs still run")
    void fullQueueRefusesTheTrigger(CapturedOutput output) throws Exception {
        SourceCode source = SourceCode.of("PSP_AFTER_UPLOAD_FULL");
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        held.hold(source);
        try {
            Future<ReconciliationRun> manual = thread.submit(() -> matching.run(source.value(),
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), ReconUsers.OPERATOR));
            assertThat(held.awaitEntered(1, WAIT)).isTrue();
            String id = unique();
            Response waiting = api.upload(source.value(), "STMT-" + id + "-A", psp(pspLine(id + "-1", "2026-09-21")));
            await().atMost(WAIT).untilAsserted(() -> assertThat(output)
                    .contains("Run for statement file " + waiting.id("id") + " waits"));
            Response queued = api.upload(source.value(), "STMT-" + id + "-B", psp(pspLine(id + "-2", "2026-09-22")));

            Response refused = api.upload(source.value(), "STMT-" + id + "-C", psp(pspLine(id + "-3", "2026-09-23")));

            assertThat(List.of(waiting.status(), queued.status(), refused.status())).containsOnly(201);
            assertThat(output).containsPattern("WARN.*Run for statement file " + refused.id("id") + " on source "
                    + source.value() + " was not queued");
            held.release(source);
            assertThat(manual.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            await().atMost(WAIT).untilAsserted(() -> assertThat(runsOf(source.value()))
                    .extracting(RunRow::status, RunRow::from, RunRow::to)
                    .containsExactly(tuple("COMPLETED", "2026-09-01", "2026-09-30"),
                            tuple("COMPLETED", "2026-09-21", "2026-09-21"),
                            tuple("COMPLETED", "2026-09-22", "2026-09-22")));
        } finally {
            held.release(source);
            thread.shutdownNow();
        }
    }

    @Test
    @DisplayName("FR-MAT-1: the upload is answered while its run cannot finish, so its response time does not include "
            + "the run")
    void responseDoesNotWaitForTheRun() throws Exception {
        SourceCode source = SourceCode.of("PSP_AFTER_UPLOAD_QUICK");
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        held.hold(source);
        try {
            String id = unique();

            Response uploaded = api.upload(source.value(), "STMT-" + id, psp(pspLine(id + "-1", "2026-09-24")));

            assertThat(uploaded.status()).isEqualTo(201);
            assertThat(runsOf(source.value())).as("when the response came, the run was not finished: it is held")
                    .allSatisfy(run -> assertThat(run.status()).isEqualTo("RUNNING"));
            assertThat(held.awaitEntered(1, WAIT)).as("the triggered run reached its work after the response").isTrue();
            held.release(source);
            await().atMost(WAIT).untilAsserted(() -> assertThat(runsOf(source.value())).extracting(RunRow::status)
                    .containsExactly("COMPLETED"));
        } finally {
            held.release(source);
        }
    }

    private List<RunRow> runsOf(String source) {
        return jdbc.sql("""
                        SELECT id, status, CAST(value_date_from AS TEXT) AS value_date_from,
                               CAST(value_date_to AS TEXT) AS value_date_to, triggered_by, started_at, finished_at
                          FROM reconciliation_runs WHERE source_code = :source ORDER BY started_at
                        """)
                .param("source", source)
                .query((row, n) -> new RunRow(row.getObject("id", UUID.class), row.getString("status"),
                        row.getString("value_date_from"), row.getString("value_date_to"), row.getString("triggered_by"),
                        row.getTimestamp("started_at").toInstant(),
                        Optional.ofNullable(row.getTimestamp("finished_at")).map(java.sql.Timestamp::toInstant)
                                .orElse(null)))
                .list();
    }

    private UUID runningRunOf(String source) {
        return jdbc.sql("SELECT id FROM reconciliation_runs WHERE source_code = :source AND status = 'RUNNING'")
                .param("source", source).query(UUID.class).single();
    }

    private record RunRow(UUID id, String status, String from, String to, String triggeredBy, Instant startedAt,
                          Instant finishedAt) {
    }
}
