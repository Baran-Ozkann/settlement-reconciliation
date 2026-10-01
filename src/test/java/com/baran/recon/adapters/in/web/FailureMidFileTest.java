package com.baran.recon.adapters.in.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.StatementStore;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR-ING-6 and NFR-REL-1 over real HTTP: the store fails on the second batch of a file's lines,
 * after the first batch was written and a DUPLICATE_LINE break opened inside the same transaction,
 * the way a crash between the write and the commit would. The upload is 500, and nothing of it is
 * left: no file row, no line, no break, no temp file. The same file then uploads successfully.
 *
 * <p>A failure after the file row is written leaves nothing either. There the deferred line-to-file
 * check no longer helps, since every line has its file, so the rollback alone removes it;
 * {@code FailureAfterFileRowBreakProofTest} shows the same failure kept when the transaction commits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_FAIL",
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@Import(FailingStatementStore.Injection.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FR-ING-6, NFR-REL-1: a failure mid-file leaves nothing, and the same file then succeeds")
class FailureMidFileTest {

    private static final String PSP = "PSP_FAIL";
    private static final Path TEMP_DIRECTORY = Path.of("target", "test-uploads", "failure-" + UUID.randomUUID());
    /** Three batches of 1,000: the failure comes on the second. */
    private static final int LINES = 2_500;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StatementStore store;

    private StatementApi api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        registry.add("recon.ingestion.temp-directory", TEMP_DIRECTORY::toString);
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
    @DisplayName("the second batch fails: 500, nothing recorded, the first batch's lines and break rolled back; then 201")
    void failureMidFileRollsBackAndTheSameFileThenSucceeds() throws Exception {
        String id = unique();
        UUID earlier = api.upload(PSP, "STMT-" + id + "-earlier", psp(List.of(pspLine(id + "-0001")))).id("id");
        UUID storedLine = jdbc.sql("SELECT id FROM psp_lines WHERE file_id = :file").param("file", earlier)
                .query(UUID.class).single();
        String content = psp(lines(id));
        FailingStatementStore failing = (FailingStatementStore) store;

        failing.failOnPspBatch(2);
        StatementApi.Response failed = api.upload(PSP, "STMT-" + id, content);

        assertThat(failing.batchesSeen()).isEqualTo(2);
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.contentType()).startsWith("application/problem+json");
        LeakCheck.assertLeaksNothing(failed.body(), StatementApi.FILENAME);
        assertThat(count("SELECT count(*) FROM statement_files WHERE statement_reference = :value", "STMT-" + id)).isZero();
        assertThat(count("SELECT count(*) FROM psp_lines WHERE line_id LIKE :value", id + "-%")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM breaks WHERE item_id = :value", storedLine)).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isEmpty());

        failing.disarm();
        StatementApi.Response retried = api.upload(PSP, "STMT-" + id, content);

        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.json().get("lineCount").asLong()).isEqualTo(LINES);
        assertThat(retried.json().get("storedLineCount").asLong()).isEqualTo(LINES - 1);
        assertThat(retried.json().get("duplicateLineCount").asLong()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM psp_lines WHERE line_id LIKE :value", id + "-%")).isEqualTo(LINES);
        assertThat(count("SELECT count(*) FROM breaks WHERE item_id = :value", storedLine)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isEmpty());
    }

    @Test
    @DisplayName("FR-ING-6: a failure after the file row and every line are written: 500, nothing left; then 201")
    void failureAfterTheFileRowRollsBackAndTheSameFileThenSucceeds() throws Exception {
        String id = unique();
        String content = psp(lines(id));
        FailingStatementStore failing = (FailingStatementStore) store;

        failing.failAfterStoringFile();
        StatementApi.Response failed = api.upload(PSP, "STMT-" + id, content);

        assertThat(failing.filesStored()).isEqualTo(1);
        assertThat(failed.status()).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM statement_files WHERE statement_reference = :value", "STMT-" + id)).isZero();
        assertThat(count("SELECT count(*) FROM psp_lines WHERE line_id LIKE :value", id + "-%")).isZero();

        failing.disarm();
        StatementApi.Response retried = api.upload(PSP, "STMT-" + id, content);

        assertThat(retried.status()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM psp_lines WHERE line_id LIKE :value", id + "-%")).isEqualTo(LINES);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isEmpty());
    }

    /** Line 0001 repeats the earlier file's line, in the first batch; the rest are new. */
    private static List<String> lines(String id) {
        return IntStream.rangeClosed(1, LINES).mapToObj(n -> pspLine(id + "-" + String.format("%04d", n))).toList();
    }

    private long count(String sql, Object value) {
        return jdbc.sql(sql).param("value", value).query(Long.class).single();
    }

    private static List<String> tempFiles() throws IOException {
        if (!Files.isDirectory(TEMP_DIRECTORY)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(TEMP_DIRECTORY)) {
            return new ArrayList<>(files.map(file -> file.getFileName().toString()).toList());
        }
    }
}
