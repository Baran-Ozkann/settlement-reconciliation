package com.baran.recon.domain.item;

import java.util.Arrays;

/**
 * The transaction types the ledger's producer emits today (docs/ledger-integration-notes.md 5.5).
 * A ledger entry's {@code tx_type} is stored as the string it arrived as, and is not restricted to
 * these: the ledger's database already admits more, and an entry of a type this service has not
 * learned yet still moved money on an account it reconciles (FR-LED-9). No matching rule reads it.
 */
public enum LedgerTxType {
    TRANSFER,
    FUNDING,
    REVERSAL;

    /** False for a type the ledger may emit that this service does not know yet. */
    public static boolean isKnown(String txType) {
        return Arrays.stream(values()).anyMatch(known -> known.name().equals(txType));
    }
}
