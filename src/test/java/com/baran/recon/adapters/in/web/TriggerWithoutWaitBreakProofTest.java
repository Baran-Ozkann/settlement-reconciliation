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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.adapters.in.web.StatementApi.Response;
import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.application.run.AutomaticRunTrigger;
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
 * The break proof for the trigger's wait (FR-MAT-1, TDD 9.1), kept as a test so it runs on every
 * build. RunAfterUploadTest sees a triggered run wait while its source is busy and then run. Here the
 * context's trigger is the same class built not to wait: a busy source ends the run's tries at once.
 * No file is edited. The upload's run is then refused by the one-running-run index and lost to a WARN,
 * so the wait is what turns that refusal into a later run. That the index, not the trigger, keeps two
 * runs of a source apart is OneRunningRunPerSourceBreakProofTest's proof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.matching.automatic-trigger.enabled=true",
        "recon.sources[0].code=PSP_TRIGGER_NO_WAIT",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import({HeldLedgerEntryStore.Injection.class, TriggerWithoutWaitBreakProofTest.Impatient.class})
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Break proof: a triggered run that does not wait for its busy source is refused, and never runs")
class TriggerWithoutWaitBreakProofTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final SourceCode SOURCE = SourceCode.of("PSP_TRIGGER_NO_WAIT");

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
    @DisplayName("with a run in progress, the upload's run is refused, logged at WARN, and its source gets no run of it")
    void withoutTheWaitTheRunIsRefused(CapturedOutput output) throws Exception {
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        held.hold(SOURCE);
        try {
            Future<ReconciliationRun> manual = thread.submit(() -> matching.run(SOURCE.value(),
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), ReconUsers.OPERATOR));
            assertThat(held.awaitEntered(1, WAIT)).isTrue();
            String id = unique();

            Response uploaded = api.upload(SOURCE.value(), "STMT-" + id, psp(pspLine(id + "-1", "2026-09-24")));

            assertThat(uploaded.status()).isEqualTo(201);
            await().atMost(WAIT).untilAsserted(() -> assertThat(output).containsPattern("WARN.*Run for statement file "
                    + uploaded.id("id") + " on source " + SOURCE.value() + " was not started: its source was busy"));
            held.release(SOURCE);
            assertThat(manual.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(runRanges()).as("the operator's run alone; the upload's was given up")
                    .containsExactly("2026-09-01..2026-09-30");
        } finally {
            held.release(SOURCE);
            thread.shutdownNow();
        }
    }

    private List<String> runRanges() {
        return jdbc.sql("""
                        SELECT CAST(value_date_from AS TEXT) || '..' || CAST(value_date_to AS TEXT)
                          FROM reconciliation_runs WHERE source_code = :source ORDER BY started_at
                        """)
                .param("source", SOURCE.value()).query(String.class).list();
    }

    /** The application's trigger, built to give a busy source up at the first refusal. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Impatient {

        @Bean
        @Primary
        AutomaticRunTrigger impatientRunTrigger(RunMatching matching) {
            return new AutomaticRunTrigger(matching::run, Executors.newSingleThreadExecutor(), () -> false);
        }
    }
}
