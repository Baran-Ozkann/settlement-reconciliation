package com.baran.recon.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD 5.3: a run a stopped JVM left RUNNING is set FAILED when the application starts. The
 * database is a throwaway one, so the leftover runs can be written before the context exists, as a
 * crashed instance would have left them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "recon.sources[0].code=PSP_STALE",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@DirtiesContext
@DisplayName("TDD 5.3: at startup, a run left RUNNING is set FAILED and its source is free again")
class StaleRunRecoveryTest {

    private static final UUID STALE = UUID.fromString("c0000000-0000-4000-8000-0000000005a1");
    private static final UUID COMPLETED = UUID.fromString("c0000000-0000-4000-8000-0000000005a2");
    private static final SourceCode SOURCE = SourceCode.of("PSP_STALE");

    private static ReconPostgres database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        database = ReconPostgres.throwaway();
        try (Connection connection = database.connectAsMigrator(); Statement jdbc = connection.createStatement()) {
            jdbc.execute("""
                    INSERT INTO recon.reconciliation_runs (id, source_code, value_date_from, value_date_to, status,
                                                           config_snapshot, stats, started_at, finished_at, triggered_by)
                    VALUES ('c0000000-0000-4000-8000-0000000005a1', 'PSP_STALE', '2026-09-24', '2026-09-25', 'RUNNING',
                            '{"value_date_zone": "Europe/Istanbul"}', NULL, '2026-09-26T10:00:00Z', NULL, 'system'),
                           ('c0000000-0000-4000-8000-0000000005a2', 'PSP_STALE', '2026-09-22', '2026-09-23', 'COMPLETED',
                            '{"value_date_zone": "Europe/Istanbul"}', '{"ledger_entries_without_value_date": 0}',
                            '2026-09-25T10:00:00Z', '2026-09-25T10:01:00Z', 'system')
                    """);
        }
        database.registerIn(registry);
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    @Autowired
    private RunStore runs;

    @Autowired
    private RunMatching matching;

    @Test
    @DisplayName("the leftover run is FAILED with no statistics, and a finished run is left as it was")
    void leftoverRunIsFailed() {
        ReconciliationRun stale = runs.findById(STALE).orElseThrow();
        assertThat(stale.status()).isEqualTo(RunStatus.FAILED);
        assertThat(stale.stats()).isEmpty();
        assertThat(stale.finishedAt()).hasValueSatisfying(finished -> assertThat(finished).isAfter(stale.startedAt()));

        ReconciliationRun completed = runs.findById(COMPLETED).orElseThrow();
        assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.stats()).isPresent();

        assertThat(runs.findRunning(SOURCE)).isEmpty();
        assertThat(matching.run(SOURCE.value(), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), "system")
                .status()).as("the source is free for a new run").isEqualTo(RunStatus.COMPLETED);
    }
}
