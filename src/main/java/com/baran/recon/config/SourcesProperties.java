package com.baran.recon.config;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.BatchIdPattern;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.source.StageASettings;

/**
 * {@code recon.sources} (TDD 8.1). The keys the ledger projection, statement ingestion and Stage A
 * read are bound; Stage B's join when it does. Unknown keys are ignored by the binder, so a
 * configuration that already carries them still starts.
 *
 * <p>A PSP source that leaves a Stage A key out gets the value TDD 8.1 configures for it. A bank
 * source that sets one is refused, as a key nothing would read.
 */
@ConfigurationProperties("recon")
public record SourcesProperties(List<Source> sources) {

    public SourcesProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record Source(String code, SourceType type, List<UUID> ledgerAccounts, String batchIdPattern,
                         Integer valueDateWindowDays, Integer graceDaysLedgerUnmatched,
                         Integer graceDaysPspUnmatched) {

        SourceDefinition toDefinition() {
            return new SourceDefinition(SourceCode.of(code), type,
                    ledgerAccounts == null ? Set.of() : Set.copyOf(ledgerAccounts),
                    Optional.ofNullable(batchIdPattern).map(BatchIdPattern::of),
                    stageA());
        }

        private Optional<StageASettings> stageA() {
            boolean anySet = valueDateWindowDays != null || graceDaysLedgerUnmatched != null
                    || graceDaysPspUnmatched != null;
            if (type != SourceType.PSP_SETTLEMENT && !anySet) {
                return Optional.empty();
            }
            StageASettings defaults = StageASettings.TDD_DEFAULTS;
            return Optional.of(new StageASettings(
                    valueDateWindowDays == null ? defaults.valueDateWindowDays() : valueDateWindowDays,
                    graceDaysLedgerUnmatched == null ? defaults.graceDaysLedgerUnmatched() : graceDaysLedgerUnmatched,
                    graceDaysPspUnmatched == null ? defaults.graceDaysPspUnmatched() : graceDaysPspUnmatched));
        }
    }

    List<SourceDefinition> definitions() {
        return sources.stream().map(Source::toDefinition).toList();
    }
}
