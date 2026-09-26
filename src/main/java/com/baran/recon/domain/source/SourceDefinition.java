package com.baran.recon.domain.source;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * One configured source (TDD 8.1). {@code ledgerAccounts} are the public ids of the ledger accounts
 * whose entries this source settles: the account id is the only account attribute the ledger's
 * event carries, so it is the only thing a mapping can key on.
 */
public record SourceDefinition(SourceCode code, SourceType type, Set<UUID> ledgerAccounts) {

    public SourceDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(type, "type");
        ledgerAccounts = Set.copyOf(ledgerAccounts);
        // Only Stage A compares ledger entries with a source's lines, and Stage A reads PSP lines. A
        // bank source with ledger accounts would project entries nothing ever reconciles.
        if (type == SourceType.BANK_STATEMENT && !ledgerAccounts.isEmpty()) {
            throw new InvalidSourceConfigurationException(
                    "source " + code + " is a bank statement source; only a PSP source maps ledger accounts");
        }
    }
}
