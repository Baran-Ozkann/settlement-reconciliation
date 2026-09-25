package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every database mechanism is shown to be the thing that refuses its violation (TDD 9.1): refused
 * with the mechanism's own name while it is there, accepted once it alone is removed. A violation
 * that also broke some other rule would fail the second half, so each one is shown to test exactly
 * one mechanism. The catalog test then fails the build on a constraint, unique index or trigger in
 * the schema that {@link DatabaseMechanism} does not list, and on a listed one the schema has lost.
 *
 * <p>Each case runs in a transaction on a superuser connection to a throwaway database and is
 * rolled back, so the cases are independent and the shared database is never touched.
 */
@DisplayName("TDD 9.1: each database mechanism refuses its violation, and only it does")
class DatabaseMechanismTest {

    private static final String SCHEMA_OBJECTS = """
            SELECT 'CONSTRAINT:' || c.contype::text || ':' || c.conname
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'recon' AND t.relname <> 'flyway_schema_history'
            UNION ALL
            SELECT 'UNIQUE_INDEX:' || ic.relname
              FROM pg_index i
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_class t ON t.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'recon' AND t.relname <> 'flyway_schema_history' AND i.indisunique
               AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
            UNION ALL
            SELECT 'TRIGGER:' || tg.tgname
              FROM pg_trigger tg
              JOIN pg_class t ON t.oid = tg.tgrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = 'recon' AND NOT tg.tgisinternal
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(DatabaseMechanism.class)
    @DisplayName("the violation is refused, by this mechanism by name")
    void violationIsRefusedByThisMechanism(DatabaseMechanism mechanism) throws SQLException {
        inRolledBackTransaction(jdbc -> {
            for (String statement : mechanism.setup()) {
                jdbc.execute(statement);
            }
            assertThatThrownBy(() -> jdbc.execute(mechanism.violation()))
                    .isInstanceOf(PSQLException.class)
                    .satisfies(e -> assertRefusedBy(mechanism, (PSQLException) e));
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DatabaseMechanism.class)
    @DisplayName("break proof: with this mechanism alone removed, the same violation goes through")
    void withoutThisMechanismTheViolationGoesThrough(DatabaseMechanism mechanism) throws SQLException {
        inRolledBackTransaction(jdbc -> {
            for (String statement : mechanism.setup()) {
                jdbc.execute(statement);
            }
            jdbc.execute(mechanism.removal());
            assertThatCode(() -> jdbc.execute(mechanism.violation()))
                    .as("%s after: %s", mechanism.violation(), mechanism.removal())
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("every constraint, unique index and trigger in the schema is listed, and nothing else is")
    void everyMechanismInTheSchemaIsListed() throws SQLException {
        Set<String> inSchema = new TreeSet<>();
        inRolledBackTransaction(jdbc -> inSchema.addAll(mechanismsInSchema(jdbc)));

        assertThat(inSchema).as("mechanisms in the schema").isNotEmpty();
        assertThat(listed()).as("listed in DatabaseMechanism").containsExactlyElementsOf(inSchema);
    }

    /**
     * The break proof for the catalog test: one mechanism of each kind is added, unlisted, and the
     * catalog query reports each of them, so a new mechanism without a proof would fail the build.
     */
    @Test
    @DisplayName("break proof: an unlisted constraint, unique index or trigger is reported")
    void anUnlistedMechanismIsReported() throws SQLException {
        inRolledBackTransaction(jdbc -> {
            jdbc.execute("ALTER TABLE recon.bank_lines ADD CONSTRAINT bank_lines_probe CHECK (amount < 1000000000000)");
            jdbc.execute("CREATE UNIQUE INDEX bank_lines_probe_unique ON recon.bank_lines (line_id) WHERE amount > 0");
            jdbc.execute("CREATE FUNCTION recon.probe() RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
            jdbc.execute("CREATE TRIGGER bank_lines_probe BEFORE INSERT ON recon.bank_lines "
                    + "FOR EACH ROW EXECUTE FUNCTION recon.probe()");

            Set<String> unlisted = new TreeSet<>(mechanismsInSchema(jdbc));
            unlisted.removeAll(listed());

            assertThat(unlisted).containsExactly(
                    "CHECK:bank_lines_probe", "TRIGGER:bank_lines_probe", "UNIQUE_INDEX:bank_lines_probe_unique");
        });
    }

    private static Set<String> mechanismsInSchema(Statement jdbc) throws SQLException {
        Set<String> found = new TreeSet<>();
        try (ResultSet rows = jdbc.executeQuery(SCHEMA_OBJECTS)) {
            while (rows.next()) {
                found.add(catalogKey(rows.getString(1)));
            }
        }
        return found;
    }

    private static Set<String> listed() {
        return Arrays.stream(DatabaseMechanism.values())
                .map(mechanism -> mechanism.kind() + ":" + mechanism.objectName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** CONSTRAINT:c:name becomes CHECK:name, so both sides use MechanismKind's names. */
    private static String catalogKey(String row) {
        String[] parts = row.split(":", 3);
        if (!parts[0].equals("CONSTRAINT")) {
            return row;
        }
        MechanismKind kind = MechanismKind.ofConstraintType(parts[1])
                .orElseThrow(() -> new AssertionError("constraint type the mechanism list has no kind for: " + row));
        return kind + ":" + parts[2];
    }

    private static void assertRefusedBy(DatabaseMechanism mechanism, PSQLException refusal) {
        assertThat(refusal.getSQLState()).as("SQLSTATE").isEqualTo(mechanism.kind().sqlState());
        if (mechanism.kind() == MechanismKind.TRIGGER) {
            assertThat(refusal.getMessage()).as("message").contains(mechanism.objectName());
        } else {
            assertThat(refusal.getServerErrorMessage()).isNotNull();
            assertThat(refusal.getServerErrorMessage().getConstraint()).as("constraint").isEqualTo(mechanism.objectName());
        }
    }

    private static void inRolledBackTransaction(RolledBackTransaction.SqlWork work) throws SQLException {
        RolledBackTransaction.run(database, work);
    }
}
