package com.baran.recon.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.RunStore;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.domain.run.RunStatus;
import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Reconciliation runs are recorded with their configuration and statistics, as recon_app")
class RunStoreTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 24);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);
    private static final Instant STARTED = Instant.parse("2026-09-26T10:00:00.000001Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private RunStore store;

    @Test
    @DisplayName("FR-MAT-8: a started run is read back with its configuration snapshot")
    void startedRunRoundTrips() {
        ReconciliationRun run = ReconciliationRun.start(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), FROM, TO,
                Map.of("value_date_zone", "Europe/Istanbul", "value_date_window_days", "2"), STARTED, "operator-001");

        store.insert(run);

        assertThat(store.findById(run.id())).contains(run);
    }

    @Test
    @DisplayName("FR-MAT-10: a completed run is read back with its statistics")
    void completedRunRoundTrips() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), SourceCode.of("PSP_ALPHA"), FROM, TO,
                RunStatus.COMPLETED, new TreeMap<>(Map.of("value_date_zone", "Europe/Istanbul")),
                Optional.of(new TreeMap<>(Map.of(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE, 12L))),
                STARTED, Optional.of(STARTED.plusSeconds(90)), "system");

        store.insert(run);

        assertThat(store.findById(run.id())).contains(run);
    }
}
