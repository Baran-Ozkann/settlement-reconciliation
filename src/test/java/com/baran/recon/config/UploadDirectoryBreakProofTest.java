package com.baran.recon.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break proof for the startup cleanup that {@code UploadDirectoryTest.staleFileIsDeleted} sees: the
 * same context, except that the upload directory bean is replaced, after the configuration has been
 * read, by one that is simply the directory, neither checked nor cleaned. No application file is
 * edited. The stale part file is then still there after startup, so the cleanup is what removes it
 * and not the container or the test.
 */
@DisplayName("Break proof: without the startup cleanup a stale part file stays")
class UploadDirectoryBreakProofTest {

    @Test
    @DisplayName("a context whose upload directory is not cleaned leaves the stale part file in place")
    void withoutTheCleanupTheStaleFileStays() throws IOException {
        Path directory = Files.createDirectories(
                Path.of("target", "test-uploads", "upload-proof-" + UUID.randomUUID()).toAbsolutePath());
        Path stale = Files.writeString(directory.resolve("upload_" + UUID.randomUUID() + "_1.tmp"), "stale");

        new WebApplicationContextRunner()
                .withInitializer(started -> {
                    started.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
                    started.addBeanFactoryPostProcessor(factory -> {
                        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
                        registry.removeBeanDefinition("uploadDirectory");
                        registry.registerBeanDefinition("uploadDirectory",
                                new RootBeanDefinition(UploadDirectory.class, () -> new UploadDirectory(directory)));
                    });
                })
                .withBean(LazyInitializationExcludeFilter.class,
                        () -> LazyInitializationExcludeFilter.forBeanTypes(UploadDirectory.class, MultipartConfigElement.class))
                .withBean(IngestionConfiguration.class)
                .withPropertyValues("recon.ingestion.max-file-size=200MB", "recon.ingestion.temp-directory=" + directory)
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started.getBean(MultipartConfigElement.class).getLocation()).isEqualTo(directory.toString());
                    assertThat(stale).exists();
                });
    }
}
