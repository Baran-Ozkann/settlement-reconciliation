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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code recon.ingestion.temp-directory} must be a directory of the application's own. At startup
 * the part files a stopped JVM left in it are deleted, and nothing else is. Everything
 * happens under target/: the JVM's {@code java.io.tmpdir} and {@code user.home} are overridden in
 * each context with directories made here, so a guard that failed would still touch nothing outside
 * the build's output. The rest of the configuration is lazy, but the upload directory and the
 * servlet settings are built at startup, as in the application.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TDD 14, Phase 4.1: startup clears stale part files from an upload directory that must be the application's own")
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
    @DisplayName("startup deletes a stale part file, and logs how many it deleted but never a name")
    void staleFileIsDeleted(CapturedOutput output) throws IOException {
        Path directory = Files.createDirectories(systemTemp.resolve("uploads"));
        String name = "upload_" + UUID.randomUUID().toString().replace('-', '_') + "_00000001.tmp";
        Path stale = Files.writeString(directory.resolve(name), "a part a killed JVM left behind");

        context(directory.toString()).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(stale).doesNotExist();
        });
        assertThat(output).contains("Deleted 1 stale upload part files").doesNotContain(name);
    }

    @Test
    @DisplayName("a file named otherwise, a subdirectory and what is in it are left, even when they match the part file pattern")
    void onlyPartFilesDirectlyInTheDirectoryAreDeleted() throws IOException {
        Path directory = Files.createDirectories(systemTemp.resolve("uploads"));
        Path stale = Files.writeString(directory.resolve("upload_stale_1.tmp"), "stale");
        Path otherName = Files.writeString(directory.resolve("upload_notes.txt"), "not a part file");
        Path otherPrefix = Files.writeString(directory.resolve("download_1.tmp"), "not a part file");
        Path subdirectory = Files.createDirectories(directory.resolve("upload_kept_2.tmp"));
        Path inside = Files.writeString(subdirectory.resolve("upload_inside_3.tmp"), "under a subdirectory");

        context(directory.toString()).run(started -> assertThat(started).hasNotFailed());

        assertThat(stale).doesNotExist();
        assertThat(otherName).exists();
        assertThat(otherPrefix).exists();
        assertThat(subdirectory).isDirectory();
        assertThat(inside).exists();
    }

    @Test
    @DisplayName("a symbolic link named like a part file is left, and so is the file it points to")
    void linkIsNeitherFollowedNorDeleted() throws IOException {
        Path directory = Files.createDirectories(systemTemp.resolve("uploads"));
        Path target = Files.writeString(base.resolve("upload_target_1.tmp"), "outside the upload directory");
        Path link = directory.resolve("upload_link_1.tmp");
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException refused) {
            assumeTrue(false, "this OS account may not create symbolic links");
        }

        context(directory.toString()).run(started -> assertThat(started).hasNotFailed());

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(target).exists();
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
