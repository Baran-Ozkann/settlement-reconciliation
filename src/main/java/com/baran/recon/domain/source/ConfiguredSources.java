package com.baran.recon.domain.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.baran.recon.domain.item.SourceCode;

/**
 * The sources of {@code recon.sources} by code (TDD 8.1). An upload names its source by code, and
 * the source's type, not the file's name or extension, fixes the file's format (FR-ING-2). Each PSP
 * source's {@code settles-to} must name a configured bank statement source.
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
        for (SourceDefinition source : byCode.values()) {
            source.settlesTo().ifPresent(bank -> requireBankSource(source.code(), byCode.get(bank), bank));
        }
        return new ConfiguredSources(byCode);
    }

    /** A PSP source's batches are matched by the run of the bank source it settles to (TDD 5.3). */
    private static void requireBankSource(SourceCode psp, SourceDefinition named, SourceCode bank) {
        if (named == null) {
            throw new InvalidSourceConfigurationException(
                    "settles-to of PSP source " + psp + " names " + bank + ", which is not a configured source");
        }
        if (named.type() != SourceType.BANK_STATEMENT) {
            throw new InvalidSourceConfigurationException(
                    "settles-to of PSP source " + psp + " names " + bank + ", which is not a bank statement source");
        }
    }

    public Optional<SourceDefinition> find(SourceCode code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** In configuration order. */
    public List<SourceDefinition> all() {
        return List.copyOf(byCode.values());
    }
}
