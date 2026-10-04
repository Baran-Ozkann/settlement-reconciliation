package com.baran.recon.adapters.in.web;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.adapters.in.web.StatementApi.Response;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static org.assertj.core.api.Assertions.assertThat;

/** The run endpoints over real HTTP (TDD 11): reading a run as a VIEWER. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_RUN_API",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TDD 11: a VIEWER reads a run's status and stats")
class RunApiTest {

    /** A source no other test class uses; the shared database outlives each class. */
    private static final String PSP = "PSP_RUN_API";
    private static final Optional<String> VIEWER = Optional.of(ReconUsers.viewerAuthorization());
    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2026, 9, 25);

    @LocalServerPort
    private int port;

    @Autowired
    private RunMatching matching;

    private RunApi runs;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @BeforeAll
    void createClient() {
        runs = new RunApi(port);
    }

    @AfterAll
    void closeClient() {
        runs.close();
    }

    @Test
    @DisplayName("FR-MAT-8, FR-MAT-10: a completed run reads back with its range, status, snapshot and stats")
    void completedRunReadsBack() throws Exception {
        ReconciliationRun run = matching.run(PSP, FROM, TO, ReconUsers.OPERATOR);

        Response read = runs.get(VIEWER, run.id().toString());

        assertThat(read.status()).isEqualTo(200);
        JsonNode body = read.json();
        assertThat(body.get("id").asString()).isEqualTo(run.id().toString());
        assertThat(body.get("source").asString()).isEqualTo(PSP);
        assertThat(body.get("valueDateFrom").asString()).isEqualTo("2026-09-21");
        assertThat(body.get("valueDateTo").asString()).isEqualTo("2026-09-25");
        assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(body.get("configSnapshot").get(RunMatching.VALUE_DATE_ZONE).asString()).isEqualTo("Europe/Istanbul");
        assertThat(body.get("configSnapshot").get(RunMatching.VALUE_DATE_WINDOW_DAYS).asString()).isEqualTo("2");
        assertThat(body.get("stats").get(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE).asLong())
                .isEqualTo(run.stats().orElseThrow().get(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE));
        assertThat(body.get("stats").get(ReconciliationRun.LEDGER_ENTRIES_WITHOUT_VALUE_DATE).isNumber()).isTrue();
        assertThat(body.get("startedAt").asString()).isEqualTo(run.startedAt().toString());
        assertThat(body.get("finishedAt").asString()).isEqualTo(run.finishedAt().orElseThrow().toString());
        assertThat(body.get("triggeredBy").asString()).isEqualTo(ReconUsers.OPERATOR);
    }

    @Test
    @DisplayName("an OPERATOR reads too: OPERATOR includes VIEWER")
    void operatorReads() throws Exception {
        ReconciliationRun run = matching.run(PSP, FROM, TO, ReconUsers.OPERATOR);

        assertThat(runs.get(Optional.of(ReconUsers.operatorAuthorization()), run.id().toString()).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("FR-API-2: an id no run has is 404 as Problem Details, leaking nothing")
    void unknownIdIs404() throws Exception {
        Response response = runs.get(VIEWER, UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.contentType()).startsWith("application/problem+json");
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-API-2: an id that is not a UUID is 400 as Problem Details, leaking nothing")
    void malformedIdIs400() throws Exception {
        Response response = runs.get(VIEWER, "not-a-uuid");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("NFR-SEC-1: reading a run without credentials is 401")
    void anonymousReadIs401() throws Exception {
        assertThat(runs.get(Optional.empty(), UUID.randomUUID().toString()).status()).isEqualTo(401);
    }
}
