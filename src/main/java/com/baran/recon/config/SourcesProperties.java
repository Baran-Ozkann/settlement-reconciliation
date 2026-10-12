package com.baran.recon.config;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.baran.recon.domain.item.InvalidItemException;
import com.baran.recon.domain.item.SourceCode;
import com.baran.recon.domain.source.BatchIdPattern;
import com.baran.recon.domain.source.InvalidSourceConfigurationException;
import com.baran.recon.domain.source.SourceDefinition;
import com.baran.recon.domain.source.SourceType;
import com.baran.recon.domain.source.StageASettings;

/**
 * {@code recon.sources} (TDD 8.1). Every key of TDD 8.1 is bound. Unknown keys are ignored by the
 * binder, so a configuration that already carries them still starts.
 *
 * <p>A PSP source that leaves a Stage A key out gets the value TDD 8.1 configures for it, and a bank
 * source that leaves out {@code grace-days-batch-unpaid} gets TDD 8.1's. A key set on the type of
 * source that does not read it is refused, as a key nothing would read. {@code settles-to} has no
 * default: a PSP source without it stops startup.
 */
@ConfigurationProperties("recon")
public record SourcesProperties(List<Source> sources) {

    public SourcesProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record Source(String code, SourceType type, List<UUID> ledgerAccounts, String batchIdPattern,
                         Integer valueDateWindowDays, Integer graceDaysLedgerUnmatched,
                         Integer graceDaysPspUnmatched, String settlesTo, Integer graceDaysBatchUnpaid) {

        SourceDefinition toDefinition() {
            return new SourceDefinition(SourceCode.of(code), type,
                    ledgerAccounts == null ? Set.of() : Set.copyOf(ledgerAccounts),
                    Optional.ofNullable(batchIdPattern).map(BatchIdPattern::of),
                    stageA(), settlesToCode(), batchGrace());
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

        /** Refused here with the source and the key named; a bad code alone would name neither. */
        private Optional<SourceCode> settlesToCode() {
            if (settlesTo == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(SourceCode.of(settlesTo));
            } catch (InvalidItemException notACode) {
                throw new InvalidSourceConfigurationException(
                        "settles-to of source " + code + " is not a source code: " + notACode.getMessage());
            }
        }

        private OptionalInt batchGrace() {
            if (graceDaysBatchUnpaid != null) {
                return OptionalInt.of(graceDaysBatchUnpaid);
            }
            return type == SourceType.BANK_STATEMENT
                    ? OptionalInt.of(SourceDefinition.TDD_GRACE_DAYS_BATCH_UNPAID)
                    : OptionalInt.empty();
        }
    }

    List<SourceDefinition> definitions() {
        return sources.stream().map(Source::toDefinition).toList();
    }
}
