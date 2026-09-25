package com.baran.recon.adapters.out.persistence;

import java.util.Optional;

/**
 * The kinds of database mechanism the schema uses, each with the SQLSTATE a violation of it raises
 * and the statement that removes one of them for a break proof.
 */
enum MechanismKind {

    PRIMARY_KEY("p", "23505"),
    UNIQUE("u", "23505"),
    FOREIGN_KEY("f", "23503"),
    CHECK("c", "23514"),
    /** A unique index that backs no constraint: in this schema, always a partial one. */
    UNIQUE_INDEX(null, "23505"),
    /** The append-only trigger raises its own SQLSTATE, so its refusal cannot be mistaken for another. */
    TRIGGER(null, "RC001");

    private final String constraintType;
    private final String sqlState;

    MechanismKind(String constraintType, String sqlState) {
        this.constraintType = constraintType;
        this.sqlState = sqlState;
    }

    String sqlState() {
        return sqlState;
    }

    /** The kind of a pg_constraint row by its contype. */
    static Optional<MechanismKind> ofConstraintType(String contype) {
        for (MechanismKind kind : values()) {
            if (contype.equals(kind.constraintType)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * Removes the mechanism, inside the proof's transaction. CASCADE because dropping a primary key
     * also drops the foreign keys that point at it; the transaction is rolled back either way.
     */
    String removal(String table, String name) {
        return switch (this) {
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK -> "ALTER TABLE recon." + table + " DROP CONSTRAINT " + name + " CASCADE";
            case UNIQUE_INDEX -> "DROP INDEX recon." + name;
            case TRIGGER -> "ALTER TABLE recon." + table + " DISABLE TRIGGER " + name;
        };
    }
}
