package com.baran.recon.adapters.out.persistence;

import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each {@link WithheldPrivilege} is refused to recon_app for lack of privilege, SQLSTATE 42501 and
 * nothing else; and once the one withheld grant is made, the statement gets past the privilege
 * check. The second half is the break proof. Each case runs in one rolled-back transaction on a
 * superuser connection acting as recon_app through SET ROLE, on a throwaway database.
 */
@DisplayName("Least privilege: recon_app is refused each withheld verb, and the missing grant is why")
class WithheldPrivilegeTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(WithheldPrivilege.class)
    @DisplayName("refused for lack of privilege")
    void refusedForLackOfPrivilege(WithheldPrivilege withheld) throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            prepare(jdbc, withheld);
            asApplication(jdbc);

            assertRefused(jdbc, withheld.statement(), INSUFFICIENT_PRIVILEGE);
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WithheldPrivilege.class)
    @DisplayName("break proof: with the withheld grant made, the privilege check no longer stops it")
    void theMissingGrantIsWhatRefusesIt(WithheldPrivilege withheld) throws SQLException {
        RolledBackTransaction.run(database, jdbc -> {
            prepare(jdbc, withheld);
            jdbc.execute(withheld.grant());
            asApplication(jdbc);

            switch (withheld.afterGrant()) {
                case SUCCEEDS -> assertThatCode(() -> jdbc.execute(withheld.statement()))
                        .as("after %s", withheld.grant()).doesNotThrowAnyException();
                case REFUSED_BY_TRIGGER -> assertRefused(jdbc, withheld.statement(), TRIGGER_REFUSAL);
            }
        });
    }

    private static void prepare(Statement jdbc, WithheldPrivilege withheld) throws SQLException {
        for (String statement : withheld.setup()) {
            jdbc.execute(statement);
        }
    }

    private static void asApplication(Statement jdbc) throws SQLException {
        jdbc.execute("SET ROLE " + ReconPostgres.APP_ROLE);
    }

    private static void assertRefused(Statement jdbc, String statement, String sqlState) {
        assertThatThrownBy(() -> jdbc.execute(statement))
                .isInstanceOf(PSQLException.class)
                .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).as("SQLSTATE of " + statement)
                        .isEqualTo(sqlState));
    }
}
