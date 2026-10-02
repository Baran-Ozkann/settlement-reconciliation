package com.baran.recon.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.application.statement.IngestionLimits;
import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.adapters.in.web.ApplicationFileSizeLimitTest.MAX_FILE_BYTES;
import static com.baran.recon.adapters.in.web.ApplicationFileSizeLimitTest.SERVLET_MAX_FILE_BYTES;
import static com.baran.recon.application.statement.StatementFiles.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Break proof for {@link ApplicationFileSizeLimitTest}: the same context, the servlet's limit raised
 * the same way, and in addition the use case's limits replaced with a copy whose file size limit is
 * the servlet's. No application file is edited. The file one byte over the configured limit, which
 * the test sees refused with 413, is then ingested: the application's check is what refuses it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "recon.sources[0].code=" + ApplicationFileSizeLimitBreakProofTest.PSP,
        "recon.sources[0].type=PSP_SETTLEMENT"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Break proof, FR-ING-8: without the application's size check the file over the limit is ingested")
class ApplicationFileSizeLimitBreakProofTest {

    static final String PSP = "PSP_APP_SIZE_PROOF";
    private static final Path TEMP_DIRECTORY = Path.of("target", "test-uploads", "app-size-proof-" + UUID.randomUUID());

    @LocalServerPort
    private int port;

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

    @Test
    @DisplayName("the file one byte over the configured limit is 201 once the application no longer checks the size")
    void withoutTheApplicationCheckTheFileIsIngested() throws Exception {
        String id = unique();
        String content = ApplicationFileSizeLimitTest.oneByteOver(id);
        assertThat(content.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_FILE_BYTES + 1);

        StatementApi.Response response = api.upload(PSP, "STMT-" + id, content);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("lineCount").asLong()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(ApplicationFileSizeLimitTest.tempFiles(TEMP_DIRECTORY)).isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TakeTheApplicationCheckAway {

        @Bean
        static BeanPostProcessor raiseBothLimits() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof IngestionLimits limits
                            ? new IngestionLimits(SERVLET_MAX_FILE_BYTES, limits.maxLineBytes(), limits.maxLines(),
                                    limits.maxInvalidLineRatioBasisPoints())
                            : ApplicationFileSizeLimitTest.servletLimitRaised(bean);
                }
            };
        }
    }
}
