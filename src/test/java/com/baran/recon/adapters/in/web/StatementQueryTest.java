package com.baran.recon.adapters.in.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /api/v1/statements/{id}} over real HTTP: a file's status, counts and errors, for a VIEWER (TDD 11). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_VIEW",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TDD 11: a VIEWER reads a statement file's status, counts and validation errors")
class StatementQueryTest {

    private static final String PSP = "PSP_VIEW";
    private static final Optional<String> VIEWER = Optional.of(ReconUsers.viewerAuthorization());

    @LocalServerPort
    private int port;

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
    @DisplayName("an ingested file reads back exactly as its upload answered")
    void ingestedFileReadsBack() throws Exception {
        String id = unique();
        StatementApi.Response uploaded = api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1"), pspLine(id + "-2"))));

        StatementApi.Response read = api.get(VIEWER, uploaded.id("id").toString());

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json()).isEqualTo(uploaded.json());
    }

    @Test
    @DisplayName("FR-ING-6, FR-ING-7: a rejected file reads back REJECTED with its line errors, and no line stored")
    void rejectedFileReadsBack() throws Exception {
        String id = unique();
        StatementApi.Response rejected = api.upload(PSP, "STMT-" + id,
                psp(List.of(pspLine(id + "-1"), pspLine(id + "-2").replace(",TRY", ",ABC"))));

        StatementApi.Response read = api.get(VIEWER, rejected.id("statementId").toString());

        assertThat(read.status()).isEqualTo(200);
        JsonNode body = read.json();
        assertThat(body.get("status").asString()).isEqualTo("REJECTED");
        assertThat(body.get("lineCount").asLong()).isEqualTo(2);
        assertThat(body.get("storedLineCount").asLong()).isZero();
        assertThat(body.get("invalidLineCount").asLong()).isEqualTo(1);
        assertThat(body.get("errors")).hasSize(1);
        assertThat(body.get("errors").get(0).get("line").asLong()).isEqualTo(3);
        assertThat(body.get("errors").get(0).get("code").asString()).isEqualTo("INVALID_CURRENCY");
    }

    @Test
    @DisplayName("an OPERATOR reads too: OPERATOR includes VIEWER")
    void operatorReads() throws Exception {
        String id = unique();
        UUID file = api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1")))).id("id");

        assertThat(api.get(Optional.of(ReconUsers.operatorAuthorization()), file.toString()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an id no file has is 404 as Problem Details")
    void unknownIdIs404() throws Exception {
        StatementApi.Response response = api.get(VIEWER, UUID.randomUUID().toString());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.contentType()).startsWith("application/problem+json");
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("an id that is not a UUID is 400 as Problem Details")
    void malformedIdIs400() throws Exception {
        StatementApi.Response response = api.get(VIEWER, "not-a-uuid");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("NFR-SEC-1: reading without credentials is 401")
    void anonymousReadIs401() throws Exception {
        assertThat(api.get(Optional.empty(), UUID.randomUUID().toString()).status()).isEqualTo(401);
    }
}
