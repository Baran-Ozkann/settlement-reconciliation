package com.baran.recon.domain.match;

/** Stage A pairs one ledger entry with one PSP line; Stage B pays a whole batch with one bank line. */
public enum Cardinality {
    ONE_TO_ONE,
    MANY_TO_ONE
}
