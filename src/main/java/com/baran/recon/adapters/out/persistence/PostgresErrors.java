package com.baran.recon.adapters.out.persistence;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads which named constraint or index a failed statement violated. Every constraint and index in
 * the schema is named (see V2), so the name identifies the rule that was broken, which a SQLSTATE
 * alone does not: every unique violation is 23505.
 */
final class PostgresErrors {

    private PostgresErrors() {
    }

    static Optional<String> violatedConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql) {
                return Optional.ofNullable(psql.getServerErrorMessage()).map(ServerErrorMessage::getConstraint);
            }
        }
        return Optional.empty();
    }
}
