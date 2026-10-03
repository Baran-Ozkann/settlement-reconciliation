package com.baran.recon.domain.run;

/**
 * Where an in-scope item stands after a run (INV-1, TDD 8.2), decided in this order: MATCHED if it
 * is in an active match; otherwise BROKEN if it is the subject of an unresolved break or named among
 * the related items of one; otherwise PENDING, which is an item still inside its grace period.
 */
public enum ItemStatus {
    MATCHED,
    PENDING,
    BROKEN
}
