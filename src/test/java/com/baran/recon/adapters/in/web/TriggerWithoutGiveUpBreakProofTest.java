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
 * The break proof for the trigger's give-up bound (owner decision 2 of Phase 5 part 2b, TDD 9.1),
 * kept as a test so it runs on every build. RunTriggerGiveUpTest holds a source past a one-second
 * bound and sees the run given up and the next source's run complete. Here the context's bound is a
 * day, out of the test's reach, set through the property in this context alone; no file is edited.
 * For three times as long as RunTriggerGiveUpTest's bound, the stuck source's triggered run keeps
 * the trigger's one thread and the next source's run does not start: without the bound, one stuck
 * run holds every later triggered run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.matching.automatic-trigger.busy-retry-interval=50ms",
        "recon.matching.automatic-trigger.busy-give-up-after=1d",
        "recon.sources[0].code=PSP_NO_GIVE_UP_STUCK",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=PSP_NO_GIVE_UP_NEXT",
        "recon.sources[1].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import(HeldLedgerEntryStore.Injection.class)
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Break proof: a trigger without the give-up bound lets one stuck run hold the next source's run")
class TriggerWithoutGiveUpBreakProofTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    /** Three times the bound RunTriggerGiveUpTest gives up at. */
    private static final Duration PAST_THE_BOUND = Duration.ofSeconds(3);
    private static final SourceCode STUCK = SourceCode.of("PSP_NO_GIVE_UP_STUCK");
    private static final SourceCode NEXT = SourceCode.of("PSP_NO_GIVE_UP_NEXT");

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
    @DisplayName("with the bound out of reach, the next source's run does not start while the stuck run is held, and "
            + "both triggered runs complete only once it is released")
    void withoutTheBoundOneStuckRunHoldsTheNext(CapturedOutput output) throws Exception {
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
            await().atMost(WAIT).untilAsserted(() -> assertThat(output)
                    .contains("Run for statement file " + waiting.id("id") + " waits"));

            await().during(PAST_THE_BOUND).atMost(PAST_THE_BOUND.plus(WAIT))
                    .until(() -> runRanges(NEXT).isEmpty());
            assertThat(output).doesNotContain("still busy after");

            held.release(STUCK);
            assertThat(stuck.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            await().atMost(WAIT).untilAsserted(() -> assertThat(runRanges(NEXT))
                    .containsExactly("COMPLETED 2026-09-25..2026-09-25"));
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
