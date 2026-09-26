package com.baran.recon.domain.source;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.baran.recon.domain.item.SourceCode;

/**
 * Which source a ledger account belongs to (FR-LED-2). An entry on an account that no source maps
 * is not this service's business and is not stored.
 *
 * <p>An account mapped to two sources is refused outright rather than resolved: its entries would
 * have to be reconciled against one of them, and choosing is a guess the configuration must make.
 */
public final class LedgerAccountSources {

    private final Map<UUID, SourceCode> sourceByAccount;

    private LedgerAccountSources(Map<UUID, SourceCode> sourceByAccount) {
        this.sourceByAccount = Map.copyOf(sourceByAccount);
    }

    public static LedgerAccountSources of(List<SourceDefinition> sources) {
        Set<SourceCode> codes = new HashSet<>();
        Map<UUID, SourceCode> sourceByAccount = new HashMap<>();
        for (SourceDefinition source : sources) {
            if (!codes.add(source.code())) {
                throw new InvalidSourceConfigurationException("source " + source.code() + " is configured twice");
            }
            for (UUID account : source.ledgerAccounts()) {
                SourceCode previous = sourceByAccount.putIfAbsent(account, source.code());
                if (previous != null) {
                    throw new InvalidSourceConfigurationException("a ledger account is mapped to both "
                            + previous + " and " + source.code());
                }
            }
        }
        return new LedgerAccountSources(sourceByAccount);
    }

    public Optional<SourceCode> sourceOf(UUID ledgerAccount) {
        return Optional.ofNullable(sourceByAccount.get(ledgerAccount));
    }
}
