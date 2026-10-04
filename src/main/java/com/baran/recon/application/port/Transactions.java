package com.baran.recon.application.port;

import java.util.function.Supplier;

/**
 * A database transaction around a unit of work, so a use case can say what must commit together
 * without naming the framework that does it. Work that throws is rolled back and the exception is
 * rethrown unchanged.
 */
public interface Transactions {

    /** Each statement reads what was committed when it started (READ COMMITTED). */
    <T> T inTransaction(Supplier<T> work);

    /**
     * Every statement reads the one snapshot taken by the first (REPEATABLE READ), so what other
     * transactions commit meanwhile is seen by none of them. Writing a row that another transaction
     * changed after the snapshot fails the work with a serialization failure, which rolls it back
     * like any other failure.
     */
    <T> T inSnapshotTransaction(Supplier<T> work);
}
