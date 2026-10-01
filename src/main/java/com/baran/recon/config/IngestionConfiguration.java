package com.baran.recon.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import com.baran.recon.application.port.BreakStore;
import com.baran.recon.application.port.StatementParser;
import com.baran.recon.application.port.StatementStore;
import com.baran.recon.application.port.Transactions;
import com.baran.recon.application.statement.IngestStatement;
import com.baran.recon.application.statement.IngestionLimits;
import com.baran.recon.domain.source.ConfiguredSources;

/**
 * {@code recon.ingestion}: the limits of FR-ING-8 and the invalid-line threshold of FR-ING-7, each
 * set once. The file size limit and the temp directory go to the servlet container, which refuses an
 * oversize upload while it is still arriving (TDD 11.1); the line limits go to the parsers. A limit
 * that cannot hold stops startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionConfiguration.IngestionProperties.class)
class IngestionConfiguration {

    /**
     * What a request may carry beyond the file: the source and the statement reference, the part
     * headers and the multipart boundaries. The file alone is held to the file size limit.
     */
    static final long MULTIPART_OVERHEAD_BYTES = 64 * 1024;

    @Bean
    IngestionLimits ingestionLimits(IngestionProperties properties) {
        long maxLineBytes = properties.maxLineLength().toBytes();
        if (maxLineBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("recon.ingestion.max-line-length is too large");
        }
        return new IngestionLimits((int) maxLineBytes, properties.maxLines(), properties.maxInvalidLineRatioBp());
    }

    /**
     * The servlet container keeps each upload in the configured directory, from its first byte: a
     * threshold of 0 never holds one in memory (FR-ING-5). The directory is the only place an upload
     * is written, under a name the container makes up; the client's file name never becomes a path
     * (FR-ING-9). The container deletes the file when the request ends, whatever its outcome.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    MultipartConfigElement multipartConfigElement(IngestionProperties properties) {
        Path directory = Objects.requireNonNull(properties.tempDirectory(), "recon.ingestion.temp-directory must be set")
                .toAbsolutePath();
        try {
            Files.createDirectories(directory);
        } catch (IOException unusable) {
            throw new UncheckedIOException("recon.ingestion.temp-directory cannot be created", unusable);
        }
        long maxFileBytes = Objects.requireNonNull(properties.maxFileSize(), "recon.ingestion.max-file-size must be set")
                .toBytes();
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("recon.ingestion.max-file-size must be positive");
        }
        return new MultipartConfigElement(directory.toString(), maxFileBytes,
                Math.addExact(maxFileBytes, MULTIPART_OVERHEAD_BYTES), 0);
    }

    @Bean
    IngestStatement ingestStatement(ConfiguredSources sources, List<StatementParser> parsers, StatementStore store,
                                    BreakStore breaks, Transactions transactions, Clock clock, IngestionLimits limits) {
        return new IngestStatement(sources, parsers, store, breaks, transactions, clock, limits);
    }

    /**
     * @param maxFileSize           FR-ING-8, 200 MB by default: the largest file an upload may carry
     * @param tempDirectory         where the servlet container keeps uploads while they are ingested
     * @param maxLineLength         FR-ING-8, 4 KB by default: the longest line, not counting its ending
     * @param maxLines              FR-ING-8, 2,000,000 by default: the most data lines a file may have
     * @param maxInvalidLineRatioBp FR-ING-7, 0 by default: invalid lines a file may have and still be
     *                              ingested, in basis points of its data lines
     */
    @ConfigurationProperties("recon.ingestion")
    record IngestionProperties(DataSize maxFileSize, Path tempDirectory, DataSize maxLineLength, long maxLines,
                               int maxInvalidLineRatioBp) {
    }
}
