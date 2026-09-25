package com.baran.recon.adapters.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.baran.recon.support.ReconPostgres;

/**
 * Runs SQL in one transaction that is always rolled back, so a test can build any state - a broken
 * mechanism included - without it outliving the test.
 */
final class RolledBackTransaction {

    @FunctionalInterface
    interface SqlWork {
        void run(Statement jdbc) throws SQLException;
    }

    private RolledBackTransaction() {
    }

    /** As the superuser of the given throwaway database. */
    static void run(ReconPostgres database, SqlWork work) throws SQLException {
        run(database.connectAsSuperuser(), work);
    }

    /** On the given connection, which this closes. */
    static void run(Connection connection, SqlWork work) throws SQLException {
        try (connection; Statement jdbc = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                work.run(jdbc);
            } finally {
                connection.rollback();
            }
        }
    }
}
