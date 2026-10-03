package com.baran.recon.application.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.LedgerEntryStore;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The break proof for one running run per source (TDD 5.3, 9.1), kept as a test so it runs on every
 * build. {@code RunMatchingTest} sees the second of two runs started together refused. Here the
 * same two runs meet a throwaway database migrated as usual and then stripped of the partial unique
 * index alone; no file is edited. Both runs then start and are inside their work at once, so the
 * index is what refuses the second, and nothing in the application stands in for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_NO_INDEX",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import(HeldLedgerEntryStore.Injection.class)
@DirtiesContext
@DisplayName("Break proof: without the running-run index, two runs of one source both start")
class OneRunningRunPerSourceBreakProofTest {

    private static final SourceCode SOURCE = SourceCode.of("PSP_NO_INDEX");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);
    private static final Duration WAIT = Duration.ofSeconds(20);

    private static ReconPostgres database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        database = ReconPostgres.throwaway();
        try (Connection connection = database.connectAsMigrator(); Statement jdbc = connection.createStatement()) {
            jdbc.execute("DROP INDEX recon.reconciliation_runs_one_running_per_source");
        }
        database.registerIn(registry);
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    @Autowired
    private RunMatching matching;

    @Autowired
    private LedgerEntryStore ledgerEntries;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("both runs started together are RUNNING at once, and both complete")
    void withoutTheIndexBothRunsStart() throws Exception {
        HeldLedgerEntryStore held = HeldLedgerEntryStore.of(ledgerEntries);
        held.hold(SOURCE);
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<ReconciliationRun>> started = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                started.add(threads.submit(() -> {
                    together.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                    return matching.run(SOURCE.value(), FROM, TO, "operator-001");
                }));
            }

            assertThat(held.awaitEntered(2, WAIT)).as("both runs are inside their work at once").isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_runs WHERE source_code = :source AND status = 'RUNNING'")
                    .param("source", SOURCE.value()).query(Long.class).single()).isEqualTo(2);

            held.release(SOURCE);
            for (Future<ReconciliationRun> run : started) {
                assertThat(run.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(RunStatus.COMPLETED);
            }
        } finally {
            held.release(SOURCE);
            threads.shutdownNow();
        }
    }
}
