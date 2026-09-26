package com.baran.recon.application.port;

import java.util.function.Supplier;

/**
 * A database transaction around a unit of work, so a use case can say what must commit together
 * without naming the framework that does it. Work that throws is rolled back and the exception is
 * rethrown unchanged.
 */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
