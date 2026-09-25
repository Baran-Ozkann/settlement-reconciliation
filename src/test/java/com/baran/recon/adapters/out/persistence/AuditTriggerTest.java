package com.baran.recon.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;

import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.adapters.out.persistence.Rows.BREAK;
import static com.baran.recon.adapters.out.persistence.Rows.BREAK_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH;
import static com.baran.recon.adapters.out.persistence.Rows.MATCH_EVENT;
import static com.baran.recon.adapters.out.persistence.Rows.RUN;
import static com.baran.recon.adapters.out.persistence.Rows.inserts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INV-6, the trigger half. Every statement here runs as recon_migrator, which owns both audit
 * tables and therefore holds UPDATE, DELETE and TRUNCATE on them - the test asserts it does. No
 * privilege check can stand in the way, so the refusal seen is the trigger's, identified by its own
 * SQLSTATE and its name. The privilege half, where recon_app is refused before any trigger runs,
 * is tested with the grants that open these tables to the application.
 */
@DisplayName("INV-6: the append-only trigger refuses changes to audit events, even from their owner")
class AuditTriggerTest {

    private static final String TRIGGER_REFUSAL = "RC001";

    private static ReconPostgres database;

    @BeforeAll
    static void startDatabase() {
        database = ReconPostgres.throwaway();
    }

    @AfterAll
    static void stopDatabase() {
        database.close();
    }

    static Stream<Arguments> changes() {
        List<String> matchEvent = inserts(RUN, MATCH, MATCH_EVENT);
        List<String> breakEvent = inserts(RUN, BREAK, BREAK_EVENT);
        return Stream.of(
                Arguments.of("match_events", "match_events_append_only", matchEvent,
                        "UPDATE recon.match_events SET actor = 'someone-else' WHERE id = 1"),
                Arguments.of("match_events", "match_events_append_only", matchEvent,
                        "DELETE FROM recon.match_events WHERE id = 1"),
                Arguments.of("match_events", "match_events_no_truncate", matchEvent,
                        "TRUNCATE recon.match_events"),
                Arguments.of("break_events", "break_events_append_only", breakEvent,
                        "UPDATE recon.break_events SET reason = 'Rewritten after the fact' WHERE id = 1"),
                Arguments.of("break_events", "break_events_append_only", breakEvent,
                        "DELETE FROM recon.break_events WHERE id = 1"),
                Arguments.of("break_events", "break_events_no_truncate", breakEvent,
                        "TRUNCATE recon.break_events"));
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("changes")
    @DisplayName("the owner's change is refused by the trigger, not by a missing privilege")
    void ownerIsRefusedByTheTrigger(String table, String trigger, List<String> setup, String change)
            throws SQLException {
        RolledBackTransaction.run(database.connectAsMigrator(), jdbc -> {
            assertOwnerHoldsEveryPrivilege(jdbc, table);
            for (String statement : setup) {
                jdbc.execute(statement);
            }
            assertThatThrownBy(() -> jdbc.execute(change))
                    .isInstanceOf(PSQLException.class)
                    .satisfies(e -> {
                        assertThat(((PSQLException) e).getSQLState()).isEqualTo(TRIGGER_REFUSAL);
                        assertThat(e.getMessage()).contains(trigger);
                    });
        });
    }

    /** The break proof: with only the trigger disabled, by its owner, the same change is made. */
    @ParameterizedTest(name = "{3}")
    @MethodSource("changes")
    @DisplayName("break proof: with the trigger disabled, the owner's change goes through")
    void withoutTheTriggerTheOwnersChangeGoesThrough(String table, String trigger, List<String> setup, String change)
            throws SQLException {
        RolledBackTransaction.run(database.connectAsMigrator(), jdbc -> {
            for (String statement : setup) {
                jdbc.execute(statement);
            }
            jdbc.execute("ALTER TABLE recon." + table + " DISABLE TRIGGER " + trigger);

            assertThatCode(() -> jdbc.execute(change)).doesNotThrowAnyException();
        });
    }

    private static void assertOwnerHoldsEveryPrivilege(Statement jdbc, String table) throws SQLException {
        try (ResultSet row = jdbc.executeQuery("SELECT has_table_privilege('recon." + table + "', 'UPDATE')"
                + " AND has_table_privilege('recon." + table + "', 'DELETE')"
                + " AND has_table_privilege('recon." + table + "', 'TRUNCATE')")) {
            row.next();
            assertThat(row.getBoolean(1)).as("recon_migrator holds UPDATE, DELETE and TRUNCATE on " + table).isTrue();
        }
    }
}
