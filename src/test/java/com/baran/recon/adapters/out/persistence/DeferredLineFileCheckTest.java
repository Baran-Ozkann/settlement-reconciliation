package com.baran.recon.adapters.out.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;

import com.baran.recon.support.ReconPostgres;

import static com.baran.recon.adapters.out.persistence.Row.text;
import static com.baran.recon.adapters.out.persistence.Rows.BANK_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.PSP_LINE;
import static com.baran.recon.adapters.out.persistence.Rows.STATEMENT_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V10, the deferred path of the line-to-file foreign keys. DatabaseMechanismTest proves each key in
 * its default, immediate form; this proves what deferring it changes and what it does not. A
 * transaction that defers the check may store lines before their file, and commits when the file
 * row follows. One that never stores the file row is refused at commit, by the foreign key by name,
 * and leaves nothing behind. The break proof removes the key and sees the same orphan commit.
 *
 * <p>Everything runs as recon_app, the role ingestion uses, so deferring is shown to need no
 * privilege it lacks. Each case uses ids of its own, since a committed row cannot be deleted by
 * recon_app.
 */
@DisplayName("V10: a deferred line-to-file check still refuses, at commit, a line whose file was never stored")
class DeferredLineFileCheckTest {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String DEFER = "SET CONSTRAINTS psp_lines_file_fk, bank_lines_file_fk DEFERRED";

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
    @CsvSource({"psp_lines,psp_lines_file_fk,01", "bank_lines,bank_lines_file_fk,02"})
    @DisplayName("deferred: lines stored, the file row never stored, the commit is refused by the foreign key")
    void orphanIsRefusedAtCommit(String table, String foreignKey, String suffix) throws SQLException {
        Row line = line(table, suffix);
        try (Connection app = database.connectAsApp()) {
            app.setAutoCommit(false);
            try (Statement jdbc = app.createStatement()) {
                jdbc.execute(DEFER);
                jdbc.execute(line.insert());
            }

            assertThatThrownBy(app::commit)
                    .isInstanceOfSatisfying(PSQLException.class, refused -> {
                        assertThat(refused.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
                        assertThat(refused.getServerErrorMessage().getConstraint()).isEqualTo(foreignKey);
                    });
        }
        assertThat(count(table, line.literal("id"))).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"psp_lines,03", "bank_lines,04"})
    @DisplayName("deferred: lines stored first and their file row last commit together")
    void linesBeforeTheirFileCommit(String table, String suffix) throws SQLException {
        Row file = file(suffix);
        Row line = line(table, suffix);
        try (Connection app = database.connectAsApp()) {
            app.setAutoCommit(false);
            try (Statement jdbc = app.createStatement()) {
                jdbc.execute(DEFER);
                jdbc.execute(line.insert());
                jdbc.execute(file.insert());
            }
            app.commit();
        }
        assertThat(count(table, line.literal("id"))).isOne();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"psp_lines,psp_lines_file_fk,05", "bank_lines,bank_lines_file_fk,06"})
    @DisplayName("not deferred: the same line is refused at its own statement, as before V10")
    void immediateUnlessDeferred(String table, String foreignKey, String suffix) throws SQLException {
        try (Connection app = database.connectAsApp()) {
            app.setAutoCommit(false);
            // A transaction that deferred the check does not leave it deferred for the next one.
            try (Statement jdbc = app.createStatement()) {
                jdbc.execute(DEFER);
            }
            app.rollback();
            try (Statement jdbc = app.createStatement()) {
                assertThatThrownBy(() -> jdbc.execute(line(table, suffix).insert()))
                        .isInstanceOfSatisfying(PSQLException.class, refused ->
                                assertThat(refused.getServerErrorMessage().getConstraint()).isEqualTo(foreignKey));
            }
            app.rollback();
        }
    }

    /** The break proof: the foreign key removed, the same deferred orphan commits. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"psp_lines,psp_lines_file_fk,07", "bank_lines,bank_lines_file_fk,08"})
    @DisplayName("break proof: without the foreign key the orphan line commits")
    void withoutTheForeignKeyTheOrphanCommits(String table, String foreignKey, String suffix) throws SQLException {
        Row line = line(table, suffix);
        try (ReconPostgres broken = ReconPostgres.throwaway()) {
            try (Connection owner = broken.connectAsMigrator(); Statement jdbc = owner.createStatement()) {
                jdbc.execute("ALTER TABLE recon." + table + " DROP CONSTRAINT " + foreignKey);
            }
            try (Connection app = broken.connectAsApp()) {
                app.setAutoCommit(false);
                try (Statement jdbc = app.createStatement()) {
                    jdbc.execute(line.insert());
                }
                app.commit();
            }
            try (Connection app = broken.connectAsApp(); Statement jdbc = app.createStatement();
                 ResultSet rows = jdbc.executeQuery("SELECT count(*) FROM recon." + table)) {
                rows.next();
                assertThat(rows.getLong(1)).isOne();
            }
        }
    }

    private static Row file(String suffix) {
        return STATEMENT_FILE
                .with("id", text("0a000000-0000-4000-8000-0000000000" + suffix))
                .with("statement_reference", text("STMT-DEFERRED-" + suffix))
                .with("sha256", text(suffix.repeat(32)));
    }

    /** A line pointing at {@link #file}'s id for the same suffix, whether or not that file exists. */
    private static Row line(String table, String suffix) {
        Row template = table.equals("psp_lines") ? PSP_LINE : BANK_LINE;
        String id = table.equals("psp_lines") ? "95000000-0000-4000-8000-0000000000" : "ba000000-0000-4000-8000-0000000000";
        return template
                .with("id", text(id + suffix))
                .with("file_id", file(suffix).literal("id"))
                .with("line_id", text("DEFERRED-" + suffix));
    }

    private static long count(String table, String idLiteral) throws SQLException {
        try (Connection app = database.connectAsApp(); Statement jdbc = app.createStatement();
             ResultSet rows = jdbc.executeQuery("SELECT count(*) FROM recon." + table + " WHERE id = " + idLiteral)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
