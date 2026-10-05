package com.baran.recon.adapters.in.web;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
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
import com.baran.recon.application.run.HeldLedgerEntryStore;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * TDD 14 Phase 5 (owner decision 2 of part 2b): the automatic trigger gives up a run whose source is
 * still busy after {@code busy-give-up-after}, logs it at WARN with the file's id, and goes on to the
 * next file, so one stuck run cannot hold every later triggered run. The stuck run is a run of the
 * source held inside its work (HeldLedgerEntryStore) for longer than the bound, here one second.
 * TriggerWithoutGiveUpBreakProofTest builds the same case with the bound out of reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.matching.automatic-trigger.busy-retry-interval=50ms",
        "recon.matching.automatic-trigger.busy-give-up-after=1s",
        "recon.sources[0].code=PSP_GIVE_UP_STUCK",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=PSP_GIVE_UP_NEXT",
        "recon.sources[1].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import(HeldLedgerEntryStore.Injection.class)
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TDD 14 Phase 5: a triggered run whose source stays busy past busy-give-up-after is given up")
class RunTriggerGiveUpTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final SourceCode STUCK = SourceCode.of("PSP_GIVE_UP_STUCK");
    private static final SourceCode NEXT = SourceCode.of("PSP_GIVE_UP_NEXT");

    @LocalServerPort
    private int port;

    @Autowired
    private RunMatching matching;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private JdbcClient jdbc;

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
    @DisplayName("TDD 14 Phase 5: the stuck source's run is given up with a WARN naming the file, the next file's run "
            + "completes while the source is still stuck, and the given-up run can be started by hand")
    void runOfAStuckSourceIsGivenUp(CapturedOutput output) throws Exception {
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        held.hold(STUCK);
        try {
            Future<ReconciliationRun> stuck = thread.submit(() -> matching.run(STUCK.value(),
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), ReconUsers.OPERATOR));
            assertThat(held.awaitEntered(1, WAIT)).as("the operator's run is held inside its work").isTrue();
            String id = unique();
            Response waiting = api.upload(STUCK.value(), "STMT-" + id + "-A", psp(pspLine(id + "-1", "2026-09-24")));
            Response next = api.upload(NEXT.value(), "STMT-" + id + "-B", psp(pspLine(id + "-2", "2026-09-25")));
            assertThat(List.of(waiting.status(), next.status())).containsOnly(201);

            await().atMost(WAIT).untilAsserted(() -> assertThat(output).containsPattern("WARN.*Run for statement file "
                    + waiting.id("id") + " on source " + STUCK.value()
                    + " was not started: its source was still busy after PT1S; start it with POST /api/v1/runs"));
            await().atMost(WAIT).untilAsserted(() -> assertThat(runRanges(NEXT)).containsExactly(
                    "COMPLETED 2026-09-25..2026-09-25"));
            assertThat(runRanges(STUCK)).as("the stuck run is still running, and the given-up run was never recorded")
                    .containsExactly("RUNNING 2026-09-01..2026-09-30");

            held.release(STUCK);
            assertThat(stuck.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            ReconciliationRun byHand = matching.run(STUCK.value(), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24),
                    ReconUsers.OPERATOR);
            assertThat(byHand.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(runRanges(STUCK)).containsExactly("COMPLETED 2026-09-01..2026-09-30",
                    "COMPLETED 2026-09-24..2026-09-24");
        } finally {
            held.release(STUCK);
            thread.shutdownNow();
        }
    }

    private List<String> runRanges(SourceCode source) {
        return jdbc.sql("""
                        SELECT status || ' ' || CAST(value_date_from AS TEXT) || '..' || CAST(value_date_to AS TEXT)
                          FROM reconciliation_runs WHERE source_code = :source ORDER BY started_at
                        """)
                .param("source", source.value()).query(String.class).list();
    }
}
