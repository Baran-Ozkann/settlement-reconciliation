package com.baran.recon.adapters.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.servlet.MultipartConfigElement;
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
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.application.statement.StatementFiles.psp;
import static com.baran.recon.application.statement.StatementFiles.pspLine;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FR-ING-8, the application's own size check behind the servlet's. The servlet container here is
 * given four times the file size limit, so a file one byte over the limit gets past it and reaches
 * the use case, which counts the bytes it reads from the spooled file and refuses it with 413: not
 * parsed, nothing recorded, no temp file left. The limit is the size of a valid three-line file, so
 * the file over it is valid too and its refusal can only be the size's doing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=" + ApplicationFileSizeLimitTest.PSP,
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("FR-ING-8: the application refuses a file over the size limit even when the servlet lets it through")
class ApplicationFileSizeLimitTest {

    static final String PSP = "PSP_APP_SIZE";
    /** A valid three-line file for any id of {@code unique()}'s eight characters is exactly this long. */
    static final int MAX_FILE_BYTES = atTheLimit("00000000").getBytes(StandardCharsets.UTF_8).length;
    static final long SERVLET_MAX_FILE_BYTES = 4L * MAX_FILE_BYTES;
    private static final Path TEMP_DIRECTORY = Path.of("target", "test-uploads", "app-size-" + UUID.randomUUID());
    private static final AtomicInteger PARSED = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MultipartConfigElement multipart;

    private StatementApi api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
        registry.add("recon.ingestion.temp-directory", TEMP_DIRECTORY::toString);
        registry.add("recon.ingestion.max-file-size", () -> MAX_FILE_BYTES + "B");
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
    void forgetWhatWasParsed() {
        PARSED.set(0);
    }

    @AfterEach
    void noTempFileIsLeft() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tempFiles(TEMP_DIRECTORY)).isEmpty());
    }

    @Test
    @DisplayName("the servlet container here would let a file one byte over the limit through")
    void servletLimitIsHigher() {
        assertThat(multipart.getMaxFileSize()).isEqualTo(SERVLET_MAX_FILE_BYTES).isGreaterThan(MAX_FILE_BYTES + 1L);
    }

    @Test
    @DisplayName("FR-ING-8: one byte over the limit is 413 from the application, never parsed, never recorded")
    void oneByteOverIs413() throws Exception {
        String id = unique();
        String content = oneByteOver(id);
        assertThat(content.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_FILE_BYTES + 1);

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body()).contains("The file is larger than the file size limit.");
        assertThat(PARSED).hasValue(0);
        assertThat(jdbc.sql("SELECT count(*) FROM statement_files WHERE statement_reference = :reference")
                .param("reference", "STMT-" + id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM psp_lines WHERE line_id LIKE :prefix")
                .param("prefix", id + "-%").query(Long.class).single()).isZero();
        LeakCheck.assertLeaksNothing(response.body(), StatementApi.FILENAME);
    }

    @Test
    @DisplayName("FR-ING-8: a file of exactly the limit is ingested")
    void exactlyTheLimitIsIngested() throws Exception {
        String id = unique();
        String content = atTheLimit(id);
        assertThat(content.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_FILE_BYTES);

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("lineCount").asLong()).isEqualTo(3);
        assertThat(PARSED).hasValue(1);
    }

    /** Three valid lines. */
    static String atTheLimit(String id) {
        return psp(List.of(pspLine(id + "-1"), pspLine(id + "-2"), pspLine(id + "-3")));
    }

    /** The same three lines, the last with a reference one character longer: still valid, one byte more. */
    static String oneByteOver(String id) {
        return psp(List.of(pspLine(id + "-1"), pspLine(id + "-2"),
                pspLine(id + "-3").replace("-000000000001,", "-0000000000012,")));
    }

    static List<String> tempFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    /** Raises the servlet's limit above the application's, so the application's check is the one that fires. */
    static Object servletLimitRaised(Object bean) {
        return bean instanceof MultipartConfigElement multipart
                ? new MultipartConfigElement(multipart.getLocation(), SERVLET_MAX_FILE_BYTES,
                        SERVLET_MAX_FILE_BYTES + 64 * 1024, multipart.getFileSizeThreshold())
                : bean;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RaiseTheServletLimit {

        @Bean
        static BeanPostProcessor raiseTheServletLimitAndCountParsing() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof StatementParser parser ? new Counting(parser) : servletLimitRaised(bean);
                }
            };
        }
    }

    private record Counting(StatementParser parser) implements StatementParser {

        @Override
        public SourceType sourceType() {
            return parser.sourceType();
        }

        @Override
        public Optional<LineError> parse(InputStream content, StatementContext context, Consumer<ParsedLine> lines)
                throws IOException {
            PARSED.incrementAndGet();
            return parser.parse(content, context, lines);
        }
    }
}
