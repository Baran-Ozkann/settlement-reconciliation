package com.baran.recon.domain.item;

/** Which kind of item a match or a break refers to (TDD 10). */
public enum ItemSide {
    LEDGER,
    PSP,
    BANK,
    /** A PSP batch as a whole: the unit Stage B matches against one bank credit. */
    BATCH
}
