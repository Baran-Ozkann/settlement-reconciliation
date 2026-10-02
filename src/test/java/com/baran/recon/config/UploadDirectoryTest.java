package com.baran.recon.config;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code recon.ingestion.temp-directory} must be a directory of the application's own. Everything
 * happens under target/: the JVM's {@code java.io.tmpdir} and {@code user.home} are overridden in
 * each context with directories made here, so a guard that failed would still touch nothing outside
 * the build's output. The rest of the configuration is lazy, but the upload directory and the
 * servlet settings are built at startup, as in the application.
 */
@DisplayName("FR-ING-8: the upload directory must be the application's own, or startup stops")
class UploadDirectoryTest {

    private Path base;
    private Path systemTemp;
    private Path home;

    @BeforeEach
    void makeStandIns() throws IOException {
        base = Path.of("target", "test-uploads", "upload-directory-" + UUID.randomUUID()).toAbsolutePath();
        systemTemp = Files.createDirectories(base.resolve("tmp"));
        home = Files.createDirectories(base.resolve("home"));
    }

    @Test
    @DisplayName("a directory of its own, inside the temp directory, is accepted and created")
    void dedicatedDirectoryStarts() {
        Path dedicated = systemTemp.resolve("settlement-reconciliation").resolve("uploads");

        context(dedicated.toString()).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(MultipartConfigElement.class).getLocation()).isEqualTo(dedicated.toString());
            assertThat(Files.isDirectory(dedicated)).isTrue();
        });
    }

    @Test
    @DisplayName("the system temp directory itself stops startup, however it is written")
    void systemTempStopsStartup() {
        for (String written : new String[] {systemTemp.toString(), systemTemp + "/.", systemTemp + "/x/.."}) {
            context(written).run(started -> assertThat(started).hasFailed().getFailure()
                    .rootCause().hasMessageContaining("is the system temp directory itself"));
        }
    }

    @Test
    @DisplayName("the user's home stops startup")
    void homeStopsStartup() {
        context(home.toString()).run(started -> assertThat(started).hasFailed().getFailure()
                .rootCause().hasMessageContaining("is the user's home directory"));
    }

    @Test
    @DisplayName("a filesystem root stops startup")
    void rootStopsStartup() {
        context(base.getRoot().toString()).run(started -> assertThat(started).hasFailed().getFailure()
                .rootCause().hasMessageContaining("is a filesystem root"));
    }

    @Test
    @DisplayName("a link to the system temp directory stops startup once the link is followed")
    void linkToSystemTempStopsStartup() throws IOException {
        Path link = base.resolve("uploads-link");
        try {
            Files.createSymbolicLink(link, systemTemp);
        } catch (FileSystemException | UnsupportedOperationException refused) {
            assumeTrue(false, "this OS account may not create symbolic links");
        }

        context(link.toString()).run(started -> assertThat(started).hasFailed().getFailure()
                .rootCause().hasMessageContaining("is the system temp directory itself"));
    }

    private WebApplicationContextRunner context(String directory) {
        return new WebApplicationContextRunner()
                .withInitializer(started -> started.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withBean(LazyInitializationExcludeFilter.class,
                        () -> LazyInitializationExcludeFilter.forBeanTypes(UploadDirectory.class, MultipartConfigElement.class))
                .withBean(IngestionConfiguration.class)
                .withPropertyValues("java.io.tmpdir=" + systemTemp, "user.home=" + home,
                        "recon.ingestion.max-file-size=200MB", "recon.ingestion.temp-directory=" + directory);
    }
}
