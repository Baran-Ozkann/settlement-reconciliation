package com.baran.recon.config;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;

/**
 * {@code recon.sources} (TDD 8.1). Only the keys the ledger projection reads are bound so far; the
 * windows and grace periods join when matching does. Unknown keys are ignored by the binder, so a
 * configuration that already carries them still starts.
 */
@ConfigurationProperties("recon")
public record SourcesProperties(List<Source> sources) {

    public SourcesProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record Source(String code, SourceType type, List<UUID> ledgerAccounts) {

        SourceDefinition toDefinition() {
            return new SourceDefinition(SourceCode.of(code), type,
                    ledgerAccounts == null ? Set.of() : Set.copyOf(ledgerAccounts));
        }
    }

    List<SourceDefinition> definitions() {
        return sources.stream().map(Source::toDefinition).toList();
    }
}
