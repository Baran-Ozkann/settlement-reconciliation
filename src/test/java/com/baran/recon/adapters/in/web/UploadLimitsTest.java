package com.baran.recon.adapters.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.port.ParsedLine;
import com.baran.recon.application.port.StatementContext;
import com.baran.recon.application.port.StatementParser;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.statement.LineError;
import com.baran.recon.support.Multipart;
import com.baran.recon.support.ReconPostgres;
import com.baran.recon.support.ReconUsers;

import static com.baran.recon.application.statement.StatementFiles.PSP_HEADER;
import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR-ING-8 over real HTTP, with small limits so a test can cross each: a 16 KB file, 5 data lines,
 * 4 KB lines. Each outcome is checked for what it records, and every one of them for the temp file
 * it must not leave behind (TDD 14, Phase 4).
 *
 * <p>The temp directory is this context's own. The parsers are wrapped to note what it holds while
 * a file is read, so "empty afterwards" is shown about the directory the upload really went to,
 * not about one nothing was ever written to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=PSP_LIMITS",
        "recon.sources[0].type=PSP_SETTLEMENT",
        "recon.ingestion.max-file-size=16KB",
        "recon.ingestion.max-lines=5",
        "recon.ingestion.max-line-length=4KB"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FR-ING-8: upload limits over HTTP, and no temp file left after any outcome")
class UploadLimitsTest {

    private static final String PSP = "PSP_LIMITS";
    private static final Path TEMP_DIRECTORY = Path.of("target", "test-uploads", "limits-" + UUID.randomUUID());
    private static final int MAX_FILE_BYTES = 16 * 1024;
    /** What the temp directory held each time a parser started reading, in order. */
    private static final List<List<String>> SEEN_WHILE_PARSING = Collections.synchronizedList(new ArrayList<>());

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

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

    @BeforeEach
    void forgetWhatWasSeen() {
        SEEN_WHILE_PARSING.clear();
    }

    @AfterEach
    void noTempFileIsLeft() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles()).isEmpty());
    }

    @Test
    @DisplayName("the upload is kept in the configured directory while it is read, one container file per part")
    void uploadIsKeptInTheConfiguredDirectory() throws Exception {
        String id = unique();

        assertThat(api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1")))).status()).isEqualTo(201);

        // With nothing held in memory, the two text fields are kept on disk too, beside the file.
        assertThat(SEEN_WHILE_PARSING).singleElement().satisfies(files -> assertThat(files).hasSize(3)
                .allSatisfy(name -> assertThat(name).startsWith("upload_").endsWith(".tmp")));
    }

    @Test
    @DisplayName("FR-ING-8: a file over the size limit is 413, never parsed, never recorded")
    void fileOverTheSizeLimitIs413() throws Exception {
        String id = unique();
        String content = padded(id, MAX_FILE_BYTES + 1);

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(SEEN_WHILE_PARSING).isEmpty();
        assertThat(filesWithReference("STMT-" + id)).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-8: a file of exactly the size limit is accepted")
    void fileOfExactlyTheSizeLimitIsAccepted() throws Exception {
        String id = unique();
        String content = padded(id, MAX_FILE_BYTES);
        assertThat(content.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_FILE_BYTES);

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        // Its padding lines are invalid, so it is rejected; what matters is that it was read.
        assertThat(response.status()).isEqualTo(422);
        assertThat(SEEN_WHILE_PARSING).hasSize(1);
    }

    @Test
    @DisplayName("FR-ING-8: a request whose declared length is far over the limit is 413 before its body is read")
    void requestFarOverTheLimitIs413() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, padded(id, 10 * MAX_FILE_BYTES));

        assertThat(response.status()).isEqualTo(413);
        assertThat(filesWithReference("STMT-" + id)).isZero();
    }

    @Test
    @DisplayName("FR-ING-8: exactly the line limit is ingested")
    void exactlyTheLineLimitIsIngested() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, psp(lines(id, 5)));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("lineCount").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("FR-ING-8: one data line over the limit is 413, and nothing is recorded")
    void oneLineOverTheLimitIs413() throws Exception {
        String id = unique();

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, psp(lines(id, 6)));

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(filesWithReference("STMT-" + id)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE line_id LIKE :prefix").param("prefix", id + "-%")
                .query(Long.class).single()).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("LINE_TOO_LONG: a line over 4 KB is 422 with its line number")
    void lineOverTheLengthLimitIs422() throws Exception {
        String id = unique();
        String longLine = pspLine(id + "-2").replace("5f0c7a1e-0000-4000-8000-000000000001", "r".repeat(4100));

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, psp(List.of(pspLine(id + "-1"), longLine)));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("errors").get(0).get("line").asLong()).isEqualTo(3);
        assertThat(response.json().get("errors").get(0).get("code").asString()).isEqualTo("LINE_TOO_LONG");
    }

    @Test
    @DisplayName("INVALID_ENCODING: bytes that are not UTF-8 are 422 with their line number")
    void nonUtf8BytesAre422() throws Exception {
        String id = unique();
        byte[] content = psp(List.of(pspLine(id + "-1"), pspLine(id + "-2").replace("5f0c7a1e", "5f0cÿ7a1e")))
                .getBytes(StandardCharsets.ISO_8859_1);

        StatementApi.Response response = upload("STMT-" + id, content);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("errors").get(0).get("line").asLong()).isEqualTo(3);
        assertThat(response.json().get("errors").get(0).get("code").asString()).isEqualTo("INVALID_ENCODING");
    }

    @Test
    @DisplayName("FR-ING-8, TDD 7: a leading BOM is dropped and CRLF endings are read, so the file is ingested")
    void bomAndCrlfAreIngested() throws Exception {
        String id = unique();
        String content = "﻿" + PSP_HEADER + "\r\n" + pspLine(id + "-1") + "\r\n" + pspLine(id + "-2") + "\r\n";

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("lineCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-ING-8: the header is required: an empty file is 422 HEADER_MISMATCH on line 1")
    void emptyFileIs422() throws Exception {
        StatementApi.Response response = upload("STMT-" + unique(), new byte[0]);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("errors").get(0).get("line").asLong()).isEqualTo(1);
        assertThat(response.json().get("errors").get(0).get("code").asString()).isEqualTo("HEADER_MISMATCH");
    }

    @Test
    @DisplayName("a header-only file has no data lines, and is ingested with none")
    void headerOnlyFileIsIngested() throws Exception {
        StatementApi.Response response = api.upload(PSP, "STMT-" + unique(), PSP_HEADER + "\n");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("lineCount").asLong()).isZero();
    }

    @Test
    @DisplayName("no temp file is left after a duplicate, a refusal, a 401 or a 403 either")
    void refusalsLeaveNoTempFile() throws Exception {
        String id = unique();
        String content = psp(List.of(pspLine(id + "-1")));
        assertThat(api.upload(PSP, "STMT-" + id, content).status()).isEqualTo(201);
        noTempFileIsLeft();

        assertThat(api.upload(PSP, "STMT-" + id + "-again", content).status()).isEqualTo(409);
        noTempFileIsLeft();
        assertThat(api.upload("PSP_NOWHERE", "STMT-" + id, content).status()).isEqualTo(400);
        noTempFileIsLeft();
        assertThat(api.upload(Optional.empty(), body("STMT-" + id + "-x", content.getBytes(StandardCharsets.UTF_8)))
                .status()).isEqualTo(401);
        noTempFileIsLeft();
        assertThat(api.upload(Optional.of(ReconUsers.viewerAuthorization()),
                body("STMT-" + id + "-y", content.getBytes(StandardCharsets.UTF_8))).status()).isEqualTo(403);
    }

    private StatementApi.Response upload(String reference, byte[] content) throws IOException, InterruptedException {
        return api.upload(Optional.of(ReconUsers.operatorAuthorization()), body(reference, content));
    }

    private static Multipart body(String reference, byte[] content) {
        return new Multipart().field("source", PSP).field("statementReference", reference)
                .file("file", StatementApi.FILENAME, content);
    }

    private static List<String> lines(String id, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(n -> pspLine(id + "-" + n)).toList();
    }

    /**
     * A PSP file of exactly {@code bytes} bytes: one valid line, then invalid padding lines as long as
     * the line limit allows, so 16 KB takes five data lines and stays within the line limit too.
     */
    private static String padded(String id, int bytes) {
        StringBuilder content = new StringBuilder(PSP_HEADER).append('\n').append(pspLine(id + "-1")).append('\n');
        while (content.length() < bytes) {
            int left = bytes - content.length();
            content.append("#".repeat(Math.min(left - 1, 4095))).append('\n');
        }
        return content.toString();
    }

    private long filesWithReference(String reference) {
        return jdbc.sql("SELECT count(*) FROM statement_files WHERE statement_reference = :reference")
                .param("reference", reference).query(Long.class).single();
    }

    private static List<String> tempFiles() throws IOException {
        if (!Files.isDirectory(TEMP_DIRECTORY)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(TEMP_DIRECTORY)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WatchTheTempDirectory {

        @Bean
        static BeanPostProcessor recordTempDirectoryWhileParsing() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof StatementParser parser ? new Watching(parser) : bean;
                }
            };
        }
    }

    private record Watching(StatementParser parser) implements StatementParser {

        @Override
        public SourceType sourceType() {
            return parser.sourceType();
        }

        @Override
        public Optional<LineError> parse(InputStream content, StatementContext context, Consumer<ParsedLine> lines)
                throws IOException {
            SEEN_WHILE_PARSING.add(tempFiles());
            return parser.parse(content, context, lines);
        }
    }
}
