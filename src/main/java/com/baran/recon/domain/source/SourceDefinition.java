package com.baran.recon.domain.source;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * One configured source (TDD 8.1). {@code ledgerAccounts} are the public ids of the ledger accounts
 * whose entries this source settles: the account id is the only account attribute the ledger's
 * event carries, so it is the only thing a mapping can key on. {@code batchIdPattern} finds the PSP
 * batch id in a bank line's reference. {@code stageA} holds the windows and grace periods Stage A
 * reads, which only a PSP source has.
 *
 * <p>Stage B pairs a PSP source's batches with the lines of the bank source it pays into, and runs
 * in that bank source's run (TDD 5.3). So a PSP source names that bank source in {@code settlesTo},
 * and the bank source holds {@code graceDaysBatchUnpaid}, the business days a batch may wait for its
 * bank line before it is MISSING_SETTLEMENT. Whether {@code settlesTo} names a configured bank source
 * is checked with the other sources, by {@link ConfiguredSources}.
 */
public record SourceDefinition(SourceCode code, SourceType type, Set<UUID> ledgerAccounts,
                               Optional<BatchIdPattern> batchIdPattern, Optional<StageASettings> stageA,
                               Optional<SourceCode> settlesTo, OptionalInt graceDaysBatchUnpaid) {

    /** The value TDD 8.1 configures, for a bank source that sets none of its own. */
    public static final int TDD_GRACE_DAYS_BATCH_UNPAID = 2;

    /** The keys of {@link StageASettings}, as TDD 8.1 names them. */
    private static final String STAGE_A_KEYS = "value-date-window-days, grace-days-ledger-unmatched and "
            + "grace-days-psp-unmatched";

    public SourceDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(type, "type");
        ledgerAccounts = Set.copyOf(ledgerAccounts);
        Objects.requireNonNull(batchIdPattern, "batchIdPattern");
        Objects.requireNonNull(stageA, "stageA");
        Objects.requireNonNull(settlesTo, "settlesTo");
        Objects.requireNonNull(graceDaysBatchUnpaid, "graceDaysBatchUnpaid");
        // Only Stage A compares ledger entries with a source's lines, and Stage A reads PSP lines. A
        // bank source with ledger accounts would project entries nothing ever reconciles.
        if (type == SourceType.BANK_STATEMENT && !ledgerAccounts.isEmpty()) {
            throw new InvalidSourceConfigurationException(
                    "source " + code + " is a bank statement source; only a PSP source maps ledger accounts");
        }
        // A bank line without an extracted batch id can only ever be an unexpected bank line, so a
        // bank source without a pattern could reconcile nothing. A PSP line carries its batch id in a
        // column of its own, so a pattern on a PSP source would be read by nothing.
        if (type == SourceType.BANK_STATEMENT && batchIdPattern.isEmpty()) {
            throw new InvalidSourceConfigurationException("bank statement source " + code + " needs a batch-id-pattern");
        }
        if (type == SourceType.PSP_SETTLEMENT && batchIdPattern.isPresent()) {
            throw new InvalidSourceConfigurationException(
                    "source " + code + " is a PSP source; only a bank statement source has a batch-id-pattern");
        }
        // Stage A compares a PSP source's lines with ledger entries, so a PSP source cannot run it
        // without its windows, and on a bank source they would be read by nothing.
        if (type == SourceType.PSP_SETTLEMENT && stageA.isEmpty()) {
            throw new InvalidSourceConfigurationException("PSP source " + code + " needs " + STAGE_A_KEYS);
        }
        if (type == SourceType.BANK_STATEMENT && stageA.isPresent()) {
            throw new InvalidSourceConfigurationException(
                    "source " + code + " is a bank statement source; only a PSP source has " + STAGE_A_KEYS);
        }
        stageA.ifPresent(settings -> {
            requireBusinessDays(code, "value-date-window-days", settings.valueDateWindowDays());
            requireBusinessDays(code, "grace-days-ledger-unmatched", settings.graceDaysLedgerUnmatched());
            requireBusinessDays(code, "grace-days-psp-unmatched", settings.graceDaysPspUnmatched());
        });
        // Without settles-to, no bank run would read the PSP source's batches, so they would never be
        // reconciled and never reported missing.
        if (type == SourceType.PSP_SETTLEMENT && settlesTo.isEmpty()) {
            throw new InvalidSourceConfigurationException("PSP source " + code
                    + " needs settles-to, the bank statement source its batches are paid into");
        }
        if (type == SourceType.BANK_STATEMENT && settlesTo.isPresent()) {
            throw new InvalidSourceConfigurationException(
                    "source " + code + " is a bank statement source; only a PSP source has settles-to");
        }
        // A batch is reported missing by its bank source's run, so the bank source holds the grace.
        if (type == SourceType.BANK_STATEMENT && graceDaysBatchUnpaid.isEmpty()) {
            throw new InvalidSourceConfigurationException(
                    "bank statement source " + code + " needs grace-days-batch-unpaid");
        }
        if (type == SourceType.PSP_SETTLEMENT && graceDaysBatchUnpaid.isPresent()) {
            throw new InvalidSourceConfigurationException(
                    "source " + code + " is a PSP source; only a bank statement source has grace-days-batch-unpaid");
        }
        if (graceDaysBatchUnpaid.isPresent() && graceDaysBatchUnpaid.getAsInt() < 0) {
            throw new InvalidSourceConfigurationException("grace-days-batch-unpaid of bank statement source " + code
                    + " is a number of business days, not negative");
        }
    }

    private static void requireBusinessDays(SourceCode code, String key, int days) {
        if (days < 0) {
            throw new InvalidSourceConfigurationException(
                    key + " of PSP source " + code + " is a number of business days, not negative");
        }
    }

    /** A PSP source with the windows and grace periods of TDD 8.1. */
    public static SourceDefinition psp(SourceCode code, Set<UUID> ledgerAccounts, SourceCode settlesTo) {
        return psp(code, ledgerAccounts, StageASettings.TDD_DEFAULTS, settlesTo);
    }

    public static SourceDefinition psp(SourceCode code, Set<UUID> ledgerAccounts, StageASettings stageA,
                                       SourceCode settlesTo) {
        return new SourceDefinition(code, SourceType.PSP_SETTLEMENT, ledgerAccounts, Optional.empty(),
                Optional.of(stageA), Optional.of(settlesTo), OptionalInt.empty());
    }

    /** A bank source with the batch grace period of TDD 8.1. */
    public static SourceDefinition bank(SourceCode code, BatchIdPattern batchIdPattern) {
        return bank(code, batchIdPattern, TDD_GRACE_DAYS_BATCH_UNPAID);
    }

    public static SourceDefinition bank(SourceCode code, BatchIdPattern batchIdPattern, int graceDaysBatchUnpaid) {
        return new SourceDefinition(code, SourceType.BANK_STATEMENT, Set.of(), Optional.of(batchIdPattern),
                Optional.empty(), Optional.empty(), OptionalInt.of(graceDaysBatchUnpaid));
    }
}
