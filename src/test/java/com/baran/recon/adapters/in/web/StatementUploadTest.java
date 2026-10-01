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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.Multipart;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.bank;
import static com.baran.recon.application.statement.StatementFiles.bankLine;
import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/statements} over real HTTP, against the real database (FR-ING-1..4, FR-ING-7,
 * FR-ING-9, TDD 11.1). The database is shared and nothing can be deleted, so each test's line ids
 * and statement references are its own. Every error body is checked for what it must not carry
 * (FR-API-2) where it is received.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_WEB",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.sources[1].code=BANK_WEB",
        "recon.sources[1].type=BANK_STATEMENT",
        "recon.sources[1].batch-id-pattern=BATCH[-_]?([A-Za-z0-9_-]{1,64})"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FR-ING-1: statements are uploaded over HTTP, and each outcome has its own status")
class StatementUploadTest {

    private static final String PSP = "PSP_WEB";
    private static final String BANK = "BANK_WEB";

    @LocalServerPort
    private int port;

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
    @DisplayName("FR-ING-1, FR-ING-6: a valid PSP file is 201 with its summary and location, every line stored")
    void validPspFileIs201() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1"), pspLine(id + "-2"))));

        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        UUID fileId = response.id("id");
        assertThat(response.location()).contains("/api/v1/statements/" + fileId);
        assertThat(body.get("status").asString()).isEqualTo("INGESTED");
        assertThat(body.get("source").asString()).isEqualTo(PSP);
        assertThat(body.get("statementReference").asString()).isEqualTo("STMT-" + id);
        assertThat(body.get("lineCount").asLong()).isEqualTo(2);
        assertThat(body.get("storedLineCount").asLong()).isEqualTo(2);
        assertThat(body.get("errors").isEmpty()).isTrue();
        assertThat(rowCount("SELECT count(*) FROM psp_lines WHERE file_id = :id", fileId)).isEqualTo(2);
    }

    @Test
    @DisplayName("TDD 11.1: the authenticated operator is recorded as uploaded_by, never a name the client sends")
    void uploaderIsThePrincipal() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(Optional.of(ReconUsers.operatorAuthorization()), new Multipart()
                .field("source", PSP)
                .field("statementReference", "STMT-" + id)
                .field("uploadedBy", "someone-else")
                .file("file", StatementApi.FILENAME, psp(List.of(pspLine(id + "-1")))));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("uploadedBy").asString()).isEqualTo(ReconUsers.OPERATOR);
        assertThat(jdbc.sql("SELECT uploaded_by FROM statement_files WHERE id = :id").param("id", response.id("id"))
                .query(String.class).single()).isEqualTo(ReconUsers.OPERATOR);
    }

    @Test
    @DisplayName("FR-ING-2: the source's type picks the format, whatever the file is called")
    void formatComesFromTheSource() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(Optional.of(ReconUsers.operatorAuthorization()), new Multipart()
                .field("source", BANK)
                .field("statementReference", "STMT-" + id)
                .file("file", "payout-report.psp.xml", bank(List.of(bankLine(id + "-1")))));

        assertThat(response.status()).isEqualTo(201);
        assertThat(rowCount("SELECT count(*) FROM bank_lines WHERE file_id = :id", response.id("id"))).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-ING-9: only the sanitized file name is stored and shown")
    void filenameIsSanitized() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(Optional.of(ReconUsers.operatorAuthorization()), new Multipart()
                .field("source", PSP)
                .field("statementReference", "STMT-" + id)
                .file("file", "..\\..\\x/report 09;rm -rf.csv", psp(List.of(pspLine(id + "-1")))));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("filename").asString()).isEqualTo("report_09_rm_-rf.csv");
    }

    @Test
    @DisplayName("FR-ING-7: a file with an invalid line is 422 with line numbers and codes, recorded REJECTED, nothing stored")
    void invalidLineIs422() throws Exception {
        String id = unique();
        String content = psp(List.of(pspLine(id + "-1"), pspLine(id + "-2").replace(",TRY", ",ABC")));

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.contentType()).startsWith("application/problem+json");
        JsonNode body = response.json();
        UUID fileId = response.id("statementId");
        assertThat(body.get("lineCount").asLong()).isEqualTo(2);
        assertThat(body.get("invalidLineCount").asLong()).isEqualTo(1);
        assertThat(body.get("errors").get(0).get("line").asLong()).isEqualTo(3);
        assertThat(body.get("errors").get(0).get("code").asString()).isEqualTo("INVALID_CURRENCY");
        assertThat(jdbc.sql("SELECT status FROM statement_files WHERE id = :id").param("id", fileId)
                .query(String.class).single()).isEqualTo("REJECTED");
        assertThat(rowCount("SELECT count(*) FROM psp_lines WHERE file_id = :id", fileId)).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("HEADER_MISMATCH: a file without the source's header is 422 with the error on line 1")
    void wrongHeaderIs422() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, bank(List.of(bankLine(id + "-1"))));

        assertThat(response.status()).isEqualTo(422);
        JsonNode error = response.json().get("errors").get(0);
        assertThat(error.get("line").asLong()).isEqualTo(1);
        assertThat(error.get("code").asString()).isEqualTo("HEADER_MISMATCH");
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-3, INV-3: the same content again is 409 with the original's id, and adds no row")
    void sameContentIs409() throws Exception {
        String id = unique();
        String content = psp(List.of(pspLine(id + "-1")));
        UUID original = api.upload(PSP, "STMT-" + id, content).id("id");
        long files = rowCount("SELECT count(*) FROM statement_files WHERE source_code = :id", PSP);

        StatementApi.Response response = api.upload(PSP, "STMT-" + id + "-again", content);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.id("originalStatementId")).isEqualTo(original);
        assertThat(rowCount("SELECT count(*) FROM statement_files WHERE source_code = :id", PSP)).isEqualTo(files);
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-3: a second file under the same source and statement reference is 409 with the original's id")
    void sameReferenceIs409() throws Exception {
        String id = unique();
        UUID original = api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1")))).id("id");

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-2"))));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.id("originalStatementId")).isEqualTo(original);
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-4: a line another file stored is listed with the DUPLICATE_LINE break it opened")
    void duplicateLineAcrossFiles() throws Exception {
        String id = unique();
        api.upload(PSP, "STMT-" + id + "-1", psp(List.of(pspLine(id + "-1"))));

        StatementApi.Response response = api.upload(PSP, "STMT-" + id + "-2",
                psp(List.of(pspLine(id + "-2"), pspLine(id + "-1").replace("125.00,2.50,122.50", "125.00,2.00,123.00"))));

        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("storedLineCount").asLong()).isEqualTo(1);
        assertThat(body.get("duplicateLineCount").asLong()).isEqualTo(1);
        JsonNode duplicate = body.get("duplicates").get(0);
        assertThat(duplicate.get("line").asLong()).isEqualTo(3);
        assertThat(duplicate.get("breakOpened").asBoolean()).isTrue();
        UUID breakId = UUID.fromString(duplicate.get("breakId").asString());
        assertThat(jdbc.sql("SELECT break_type FROM breaks WHERE id = :id").param("id", breakId)
                .query(String.class).single()).isEqualTo("DUPLICATE_LINE");
    }

    @Test
    @DisplayName("FR-ING-1: an unknown source is 400, and nothing is recorded")
    void unknownSourceIs400() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload("PSP_NOWHERE", "STMT-" + id, psp(List.of(pspLine(id + "-1"))));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(rowCount("SELECT count(*) FROM statement_files WHERE statement_reference = :id", "STMT-" + id)).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-1: a malformed statement reference is 400")
    void malformedReferenceIs400() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(PSP, "STMT " + id + "/../x", psp(List.of(pspLine(id + "-1"))));

        assertThat(response.status()).isEqualTo(400);
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-1: a request without the file part, or without the source, is 400")
    void missingPartIs400() throws Exception {
        String id = unique();

        StatementApi.Response noFile = api.upload(Optional.of(ReconUsers.operatorAuthorization()),
                new Multipart().field("source", PSP).field("statementReference", "STMT-" + id));
        StatementApi.Response noSource = api.upload(Optional.of(ReconUsers.operatorAuthorization()),
                new Multipart().field("statementReference", "STMT-" + id).file("file", "a.csv", psp(List.of(pspLine(id)))));

        assertThat(noFile.status()).isEqualTo(400);
        assertThat(noSource.status()).isEqualTo(400);
        LeakCheck.assertLeaksNothing(noFile.body(), StatementApi.FILENAME);
        LeakCheck.assertLeaksNothing(noSource.body(), "a.csv");
    }

    @Test
    @DisplayName("TDD 11.1: a VIEWER uploading is 403, and nothing is recorded")
    void viewerUploadIs403() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(Optional.of(ReconUsers.viewerAuthorization()), new Multipart()
                .field("source", PSP)
                .field("statementReference", "STMT-" + id)
                .file("file", StatementApi.FILENAME, psp(List.of(pspLine(id + "-1")))));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(rowCount("SELECT count(*) FROM statement_files WHERE statement_reference = :id", "STMT-" + id)).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("NFR-SEC-1: an upload without credentials is 401, and nothing is recorded")
    void anonymousUploadIs401() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(Optional.empty(), new Multipart()
                .field("source", PSP)
                .field("statementReference", "STMT-" + id)
                .file("file", StatementApi.FILENAME, psp(List.of(pspLine(id + "-1")))));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(rowCount("SELECT count(*) FROM statement_files WHERE statement_reference = :id", "STMT-" + id)).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-API-2: a rejected or refused upload's body does not name the file it was sent as")
    void errorBodiesDoNotNameTheFile() throws Exception {
        String id = unique();
        String probeName = "leak-probe-" + id + ".csv";
        for (String reference : List.of("STMT-" + id, "bad reference")) {
            StatementApi.Response response = api.upload(Optional.of(ReconUsers.operatorAuthorization()), new Multipart()
                    .field("source", PSP)
                    .field("statementReference", reference)
                    .file("file", probeName, psp(List.of(pspLine(id + "-1").replace(",TRY", ",ABC")))));

            assertThat(response.status()).isIn(400, 422);
            LeakCheck.assertLeaksNothing(response.body(), probeName);
        }
    }

    private long rowCount(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }
}
