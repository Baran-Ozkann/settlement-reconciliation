package com.baran.recon.domain.source;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * One configured source (TDD 8.1). {@code ledgerAccounts} are the public ids of the ledger accounts
 * whose entries this source settles: the account id is the only account attribute the ledger's
 * event carries, so it is the only thing a mapping can key on. {@code batchIdPattern} finds the PSP
 * batch id in a bank line's reference.
 */
public record SourceDefinition(SourceCode code, SourceType type, Set<UUID> ledgerAccounts,
                               Optional<BatchIdPattern> batchIdPattern) {

    public SourceDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(type, "type");
        ledgerAccounts = Set.copyOf(ledgerAccounts);
        Objects.requireNonNull(batchIdPattern, "batchIdPattern");
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
    }

    public static SourceDefinition psp(SourceCode code, Set<UUID> ledgerAccounts) {
        return new SourceDefinition(code, SourceType.PSP_SETTLEMENT, ledgerAccounts, Optional.empty());
    }

    public static SourceDefinition bank(SourceCode code, BatchIdPattern batchIdPattern) {
        return new SourceDefinition(code, SourceType.BANK_STATEMENT, Set.of(), Optional.of(batchIdPattern));
    }
}
