package com.baran.recon.adapters.out.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of fixed test data as SQL literals, so a mechanism test can state a valid row once and a
 * violating row as that row with one column changed. The values are constants written in the test
 * sources, never input, so building literal SQL from them is safe here.
 */
final class Row {

    private final String table;
    private final Map<String, String> literals;

    private Row(String table, Map<String, String> literals) {
        this.table = table;
        this.literals = literals;
    }

    /** Column names and SQL literals, alternating. */
    static Row of(String table, String... columnsAndLiterals) {
        Map<String, String> literals = new LinkedHashMap<>();
        for (int i = 0; i < columnsAndLiterals.length; i += 2) {
            literals.put(columnsAndLiterals[i], columnsAndLiterals[i + 1]);
        }
        return new Row(table, literals);
    }

    static String text(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    Row with(String column, String literal) {
        if (!literals.containsKey(column)) {
            throw new IllegalArgumentException(table + " has no column " + column + " in this fixture");
        }
        Map<String, String> changed = new LinkedHashMap<>(literals);
        changed.put(column, literal);
        return new Row(table, changed);
    }

    String literal(String column) {
        return literals.get(column);
    }

    String insert() {
        return "INSERT INTO recon." + table + " (" + String.join(", ", literals.keySet()) + ") VALUES ("
                + String.join(", ", literals.values()) + ")";
    }
}
