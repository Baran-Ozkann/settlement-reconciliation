package com.baran.recon.adapters.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.baran.recon.support.ReconPostgres;

/**
 * Runs SQL as the superuser of a throwaway database, in one transaction that is always rolled back,
 * so a test can build any state - a broken mechanism included - without it outliving the test.
 */
final class RolledBackTransaction {

    @FunctionalInterface
    interface SqlWork {
        void run(Statement jdbc) throws SQLException;
    }

    private RolledBackTransaction() {
    }

    static void run(ReconPostgres database, SqlWork work) throws SQLException {
        try (Connection connection = database.connectAsSuperuser();
             Statement jdbc = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                work.run(jdbc);
            } finally {
                connection.rollback();
            }
        }
    }
}
