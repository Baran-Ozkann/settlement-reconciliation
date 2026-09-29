package com.baran.recon.config;

import java.time.Clock;
import java.util.List;

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
 * {@code recon.ingestion}: the limits of FR-ING-8 the parsers enforce and the invalid-line threshold
 * of FR-ING-7, and the ingestion use case built on them. A limit that cannot hold stops startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionConfiguration.IngestionProperties.class)
class IngestionConfiguration {

    @Bean
    IngestionLimits ingestionLimits(IngestionProperties properties) {
        long maxLineBytes = properties.maxLineLength().toBytes();
        if (maxLineBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("recon.ingestion.max-line-length is too large");
        }
        return new IngestionLimits((int) maxLineBytes, properties.maxLines(), properties.maxInvalidLineRatioBp());
    }

    @Bean
    IngestStatement ingestStatement(ConfiguredSources sources, List<StatementParser> parsers, StatementStore store,
                                    BreakStore breaks, Transactions transactions, Clock clock, IngestionLimits limits) {
        return new IngestStatement(sources, parsers, store, breaks, transactions, clock, limits);
    }

    /**
     * @param maxLineLength         FR-ING-8, 4 KB by default: the longest line, not counting its ending
     * @param maxLines              FR-ING-8, 2,000,000 by default: the most data lines a file may have
     * @param maxInvalidLineRatioBp FR-ING-7, 0 by default: invalid lines a file may have and still be
     *                              ingested, in basis points of its data lines
     */
    @ConfigurationProperties("recon.ingestion")
    record IngestionProperties(DataSize maxLineLength, long maxLines, int maxInvalidLineRatioBp) {
    }
}
