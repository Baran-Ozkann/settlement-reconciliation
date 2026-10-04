package com.baran.recon.adapters.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.adapters.in.web.StatementApi.Response;
import com.baran.recon.application.port.RunStore;
import com.baran.recon.application.run.RunMatching;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.run.ReconciliationRun;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run endpoints over real HTTP (TDD 11): starting a run as an OPERATOR, synchronously, and reading
 * one as a VIEWER.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_RUN_API",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FR-MAT-1, TDD 11: an OPERATOR starts a run and waits for it; a VIEWER reads a run's status and stats")
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

    @Autowired
    private RunStore runStore;

    @Autowired
    private JdbcClient jdbc;

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
    @DisplayName("FR-MAT-1: an OPERATOR's run is 201 once COMPLETED, with its id, status and stats, recorded as triggered by them")
    void operatorStartsARun() throws Exception {
        long before = runsOf(PSP);

        Response started = runs.start(PSP, "2026-09-21", "2026-09-25");

        assertThat(started.status()).isEqualTo(201);
        JsonNode body = started.json();
        UUID id = started.id("id");
        assertThat(started.location())
                .hasValueSatisfying(location -> assertThat(location).endsWith("/api/v1/runs/" + id));
        assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(body.get("source").asString()).isEqualTo(PSP);
        assertThat(body.get("valueDateFrom").asString()).isEqualTo("2026-09-21");
        assertThat(body.get("valueDateTo").asString()).isEqualTo("2026-09-25");
        assertThat(body.get("stats").get(ReconciliationRun.LEDGER_ENTRIES_IN_SCOPE).isNumber()).isTrue();
        assertThat(body.get("triggeredBy").asString()).isEqualTo(ReconUsers.OPERATOR);
        assertThat(runsOf(PSP)).isEqualTo(before + 1);
        assertThat(runs.get(VIEWER, id.toString()).json()).isEqualTo(body);
    }

    @Test
    @DisplayName("TDD 5.3: a source with a RUNNING run is 409 naming that run, and nothing is recorded; the running run "
            + "reads back RUNNING, with no stats and no finish time")
    void busySourceIs409() throws Exception {
        ReconciliationRun running = ReconciliationRun.start(UUID.randomUUID(), SourceCode.of(PSP), FROM, TO,
                Map.of(RunMatching.VALUE_DATE_ZONE, "Europe/Istanbul"), Instant.now().truncatedTo(ChronoUnit.MICROS),
                ReconUsers.OPERATOR);
        runStore.insert(running);
        try {
            long before = runsOf(PSP);

            Response refused = runs.start(PSP, "2026-09-21", "2026-09-25");

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.contentType()).startsWith("application/problem+json");
            assertThat(refused.id("runningRunId")).isEqualTo(running.id());
            LeakCheck.assertLeaksNothing(refused.body(), StatementApi.FILENAME);
            assertThat(runsOf(PSP)).isEqualTo(before);

            JsonNode read = runs.get(VIEWER, running.id().toString()).json();
            assertThat(read.get("status").asString()).isEqualTo("RUNNING");
            assertThat(read.get("stats").isNull()).isTrue();
            assertThat(read.get("finishedAt").isNull()).isTrue();
        } finally {
            runStore.recordOutcome(running.fail(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "an unknown source               | PSP_RUN_API_NONE | 2026-09-21 | 2026-09-25",
            "a malformed source              | psp-run-api      | 2026-09-21 | 2026-09-25",
            "valueDateFrom after valueDateTo | PSP_RUN_API      | 2026-09-26 | 2026-09-25",
            "a month that does not exist     | PSP_RUN_API      | 2026-13-01 | 2026-09-25",
            "a day the month does not have   | PSP_RUN_API      | 2026-02-30 | 2026-09-25",
            "a date not in ISO form          | PSP_RUN_API      | 21.09.2026 | 2026-09-25",
            "an empty date                   | PSP_RUN_API      | 2026-09-21 | ''"})
    @DisplayName("FR-API-2: a request naming no configured source, or a malformed or reversed range, is 400 as Problem "
            + "Details, leaking nothing and recording nothing")
    void badRequestIs400(String name, String source, String from, String to) throws Exception {
        long before = runsOf(PSP);

        Response refused = runs.start(source, from, to);

        assertBadRequest(refused);
        assertThat(runsOf(PSP)).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no source       | {\"valueDateFrom\":\"2026-09-21\",\"valueDateTo\":\"2026-09-25\"}",
            "no valueDateTo  | {\"source\":\"PSP_RUN_API\",\"valueDateFrom\":\"2026-09-21\"}",
            "a date number   | {\"source\":\"PSP_RUN_API\",\"valueDateFrom\":20260921,\"valueDateTo\":\"2026-09-25\"}",
            "no JSON at all  | source=PSP_RUN_API"})
    @DisplayName("FR-API-2: a body missing a field, or not JSON, is 400 as Problem Details, leaking nothing and recording nothing")
    void malformedBodyIs400(String name, String json) throws Exception {
        long before = runsOf(PSP);

        Response refused = runs.start(Optional.of(ReconUsers.operatorAuthorization()), json);

        assertBadRequest(refused);
        assertThat(runsOf(PSP)).isEqualTo(before);
    }

    @Test
    @DisplayName("TDD 11: a VIEWER starting a run is 403, and nothing is recorded")
    void viewerStartIs403() throws Exception {
        long before = runsOf(PSP);

        Response refused = runs.start(VIEWER, RunApi.body(PSP, "2026-09-21", "2026-09-25"));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.contentType()).startsWith("application/problem+json");
        assertThat(runsOf(PSP)).isEqualTo(before);
    }

    @Test
    @DisplayName("NFR-SEC-1: starting a run without credentials is 401, and nothing is recorded")
    void anonymousStartIs401() throws Exception {
        long before = runsOf(PSP);

        assertThat(runs.start(Optional.empty(), RunApi.body(PSP, "2026-09-21", "2026-09-25")).status()).isEqualTo(401);
        assertThat(runsOf(PSP)).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"Sec-Fetch-Site, cross-site", "Origin, http://attacker.example"})
    @DisplayName("TDD 11.1: a run started from another site is 403 even with an OPERATOR's credentials, and nothing is recorded")
    void crossSiteStartIs403(String header, String value) throws Exception {
        long before = runsOf(PSP);

        Response refused = runs.start(Optional.of(ReconUsers.operatorAuthorization()),
                RunApi.body(PSP, "2026-09-21", "2026-09-25"), header, value);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(runsOf(PSP)).isEqualTo(before);
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

    private static void assertBadRequest(Response refused) {
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.contentType()).startsWith("application/problem+json");
        LeakCheck.assertLeaksNothing(refused.body(), StatementApi.FILENAME);
    }

    private long runsOf(String source) {
        return jdbc.sql("SELECT count(*) FROM reconciliation_runs WHERE source_code = :source")
                .param("source", source).query(Long.class).single();
    }
}
