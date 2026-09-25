package com.baran.recon.adapters.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The break proof for {@code ApplicationRoleCannotRunDdlTest}, kept as a test so it runs on every
 * build (TDD 9.1). For each statement the application role is refused, it grants the one privilege
 * {@link ForbiddenDdl} says is withheld and shows the statement then succeeds: the absent grant is
 * what stops it, not some other failure the refusal test would also have accepted.
 *
 * <p>Nothing is committed. Each case runs in one transaction on a superuser connection that acts
 * as recon_app through {@code SET ROLE}, and is rolled back, grant included, so the cases stay
 * independent. The database is a throwaway instance, never the one the application tests share.
 */
@DisplayName("Break proof: each withheld privilege is what refuses the application role's DDL")
class ApplicationRoleDdlBreakProofTest {

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
    @EnumSource(ForbiddenDdl.class)
    void grantingTheWithheldPrivilegeLetsTheStatementThrough(ForbiddenDdl ddl) throws SQLException {
        try (Connection connection = database.connectAsSuperuser();
             Statement jdbc = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                jdbc.execute("SET ROLE " + ReconPostgres.APP_ROLE);
                Savepoint beforeRefusal = connection.setSavepoint();
                assertThatThrownBy(() -> jdbc.execute(ddl.statement()))
                        .as("refused before the grant: %s", ddl.statement())
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo(ForbiddenDdl.INSUFFICIENT_PRIVILEGE);
                connection.rollback(beforeRefusal);

                jdbc.execute("RESET ROLE");
                jdbc.execute(ddl.withheldGrant());
                jdbc.execute("SET ROLE " + ReconPostgres.APP_ROLE);

                assertThatCode(() -> jdbc.execute(ddl.statement()))
                        .as("allowed after %s", ddl.withheldGrant())
                        .doesNotThrowAnyException();
            } finally {
                connection.rollback();
            }
        }
    }
}
