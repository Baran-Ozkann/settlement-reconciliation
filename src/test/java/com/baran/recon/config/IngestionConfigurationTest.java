package com.baran.recon.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import com.baran.recon.application.statement.IngestionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The limits alone: the context is lazy, so the use case, which needs the whole application, is never built. */
@DisplayName("FR-ING-7, FR-ING-8: recon.ingestion binds to the ingestion limits and the servlet's upload settings")
class IngestionConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(started -> started.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
            .withBean(IngestionConfiguration.class)
            .withPropertyValues("recon.ingestion.max-line-length=4KB", "recon.ingestion.max-lines=2000000",
                    "recon.ingestion.max-invalid-line-ratio-bp=0");

    @Test
    @DisplayName("the defaults of FR-ING-8 bind: 4 KB lines, 2,000,000 lines, no invalid line allowed")
    void defaultsBind() {
        context.run(started -> assertThat(started.getBean(IngestionLimits.class))
                .isEqualTo(new IngestionLimits(4096, 2_000_000, 0)));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "recon.ingestion.max-invalid-line-ratio-bp, 10001, the invalid-line ratio is 0-10000 basis points",
            "recon.ingestion.max-lines, 0, the line limits must be positive",
            "recon.ingestion.max-line-length, 4GB, recon.ingestion.max-line-length is too large"})
    @DisplayName("a limit that cannot hold fails the limits, and so the application's startup")
    void impossibleLimitFails(String property, String value, String message) {
        context.withPropertyValues(property + "=" + value).run(started ->
                assertThatThrownBy(() -> started.getBean(IngestionLimits.class)).hasRootCauseMessage(message));
    }

    @Test
    @DisplayName("FR-ING-8: the file size limit and the temp directory go to the servlet container, which never buffers in memory")
    void multipartSettingsBind() {
        Path directory = Path.of("target", "test-uploads", "config-" + UUID.randomUUID());
        new WebApplicationContextRunner()
                .withInitializer(started -> started.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withBean(IngestionConfiguration.class)
                .withPropertyValues("recon.ingestion.max-file-size=200MB", "recon.ingestion.temp-directory=" + directory)
                .run(started -> {
                    MultipartConfigElement multipart = started.getBean(MultipartConfigElement.class);

                    assertThat(multipart.getLocation()).isEqualTo(directory.toAbsolutePath().toString());
                    assertThat(Files.isDirectory(directory)).isTrue();
                    assertThat(multipart.getMaxFileSize()).isEqualTo(200L * 1024 * 1024);
                    assertThat(multipart.getMaxRequestSize())
                            .isEqualTo(200L * 1024 * 1024 + IngestionConfiguration.MULTIPART_OVERHEAD_BYTES);
                    assertThat(multipart.getFileSizeThreshold()).isZero();
                });
    }

    @Test
    @DisplayName("a context without a web server has no upload to keep, and no multipart settings")
    void nonWebContextHasNoMultipartSettings() {
        context.run(started -> assertThat(started).doesNotHaveBean(MultipartConfigElement.class));
    }
}
