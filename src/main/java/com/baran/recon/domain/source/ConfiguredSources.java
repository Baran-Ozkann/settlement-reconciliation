package com.baran.recon.domain.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.baran.recon.domain.item.SourceCode;

/**
 * The sources of {@code recon.sources} by code (TDD 8.1). An upload names its source by code, and
 * the source's type, not the file's name or extension, fixes the file's format (FR-ING-2).
 */
public final class ConfiguredSources {

    private final Map<SourceCode, SourceDefinition> byCode;

    private ConfiguredSources(Map<SourceCode, SourceDefinition> byCode) {
        this.byCode = byCode;
    }

    public static ConfiguredSources of(List<SourceDefinition> sources) {
        Map<SourceCode, SourceDefinition> byCode = new LinkedHashMap<>();
        for (SourceDefinition source : sources) {
            if (byCode.putIfAbsent(source.code(), source) != null) {
                throw new InvalidSourceConfigurationException("source " + source.code() + " is configured twice");
            }
        }
        return new ConfiguredSources(byCode);
    }

    public Optional<SourceDefinition> find(SourceCode code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** In configuration order. */
    public List<SourceDefinition> all() {
        return List.copyOf(byCode.values());
    }
}
