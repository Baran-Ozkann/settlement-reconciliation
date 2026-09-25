package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recon_app holds exactly the privileges the application's code issues, table by table and verb by
 * verb, and nothing else: no column grants, no sequence grants beyond those an INSERT needs
 * (CLAUDE.md 3.2). A table not listed here is closed to it. Each grant migration adds its line.
 */
@DisplayName("Least privilege: recon_app holds exactly the verbs its repositories issue")
class ApplicationRoleGrantsTest {

    /** Relation, and the privileges recon_app holds on it. */
    private static final Map<String, Set<String>> EXPECTED = new TreeMap<>(Map.of(
            "ledger_entries", Set.of("SELECT", "INSERT"),
            "statement_files", Set.of("SELECT", "INSERT"),
            "psp_lines", Set.of("SELECT", "INSERT"),
            "bank_lines", Set.of("SELECT", "INSERT"),
            "reconciliation_runs", Set.of("SELECT", "INSERT"),
            "matches", Set.of("SELECT", "INSERT"),
            "match_items", Set.of("SELECT", "INSERT"),
            "match_events", Set.of("SELECT", "INSERT"),
            "match_events_id_seq", Set.of("USAGE")));

    private static final String GRANTS = """
            SELECT c.relname, a.privilege_type
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
              CROSS JOIN LATERAL aclexplode(c.relacl) a
             WHERE n.nspname = 'recon' AND a.grantee = 'recon_app'::regrole
            """;

    private static final String COLUMN_GRANTS = """
            SELECT c.relname || '.' || att.attname
              FROM pg_attribute att
              JOIN pg_class c ON c.oid = att.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              CROSS JOIN LATERAL aclexplode(att.attacl) a
             WHERE n.nspname = 'recon' AND a.grantee = 'recon_app'::regrole
            """;

    private static ReconPostgres database;

    @BeforeAll
    static void startDatabase() {
        database = ReconPostgres.throwaway();
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    @Test
    @DisplayName("the privileges on every table and sequence are exactly the expected ones")
    void grantsAreExactlyTheExpectedOnes() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            assertThat(grants(jdbc)).isEqualTo(EXPECTED);
            assertThat(columnGrants(jdbc)).as("column-level grants").isEmpty();
        });
    }

    /** The break proof: a privilege granted beyond the list, or on one column, is reported. */
    @Test
    @DisplayName("break proof: an extra grant, on a table or on a column, is reported")
    void anExtraGrantIsReported() throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            jdbc.execute("GRANT UPDATE ON recon.ledger_entries TO recon_app");
            jdbc.execute("GRANT UPDATE (tx_type) ON recon.ledger_entries TO recon_app");

            assertThat(grants(jdbc).get("ledger_entries")).contains("UPDATE");
            assertThat(columnGrants(jdbc)).containsExactly("ledger_entries.tx_type");
        });
    }

    private static Map<String, Set<String>> grants(Statement jdbc) throws SQLException {
        Map<String, Set<String>> found = new TreeMap<>();
        try (ResultSet rows = jdbc.executeQuery(GRANTS)) {
            while (rows.next()) {
                found.computeIfAbsent(rows.getString(1), table -> new TreeSet<>()).add(rows.getString(2));
            }
        }
        return found;
    }

    private static Set<String> columnGrants(Statement jdbc) throws SQLException {
        Set<String> found = new TreeSet<>();
        try (ResultSet rows = jdbc.executeQuery(COLUMN_GRANTS)) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }
}
