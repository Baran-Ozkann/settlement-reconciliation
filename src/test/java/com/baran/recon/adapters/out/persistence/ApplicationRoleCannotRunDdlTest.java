package com.baran.recon.adapters.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baran.recon.support.ReconPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application's own DataSource, the one every repository will use, is refused every kind of
 * DDL (CLAUDE.md 3.2, least privilege). Each statement is one a bug or an injected string could try:
 * creating an object anywhere, or altering or dropping one the migrator owns.
 *
 * <p>Only SQLSTATE 42501, insufficient privilege, counts as a refusal. A statement that failed for
 * any other reason - a typo, a missing table - would prove nothing about the role.
 * {@code ApplicationRoleDdlBreakProofTest} shows that each refusal is the work of the privilege
 * {@link ForbiddenDdl} names, and of nothing else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("Least privilege: the application role cannot run DDL")
class ApplicationRoleCannotRunDdlTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    /** Without this, every refusal below could be the refusal of some other, weaker role. */
    @Test
    @DisplayName("the application connects as recon_app, not as the migrator or a superuser")
    void applicationConnectsAsTheAppRole() {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(ReconPostgres.APP_ROLE);
        assertThat(jdbc.sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("Flyway migrated the recon schema as recon_migrator, which owns it")
    void migrationsRanAsTheMigrator() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'recon'")
                .query(String.class).single()).isEqualTo(ReconPostgres.MIGRATOR_ROLE);
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single()).isEqualTo("recon");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ForbiddenDdl.class)
    @DisplayName("DDL through the application's DataSource is refused for lack of privilege")
    void ddlIsRefused(ForbiddenDdl ddl) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement jdbc = connection.createStatement()) {
            assertThatThrownBy(() -> jdbc.execute(ddl.statement()))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .as("SQLSTATE of: %s", ddl.statement())
                    .isEqualTo(ForbiddenDdl.INSUFFICIENT_PRIVILEGE);
        }
    }
}
