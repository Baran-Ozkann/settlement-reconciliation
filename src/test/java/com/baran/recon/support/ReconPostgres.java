package com.baran.recon.support;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * A PostgreSQL bootstrapped the way docker compose bootstraps the local one: the same init script
 * creates the same two roles and the database, and Flyway then migrates it as recon_migrator. A
 * test therefore exercises the real role split, not a superuser that happens to make every
 * statement succeed.
 *
 * <p>Application contexts under test share one instance per test JVM, started on first use by
 * {@link #register}. A test that has to change the database itself - a break proof granting a
 * privilege, say - takes a {@link #throwaway()} instance, so the shared one is never altered.
 * Passwords are generated per instance, so no credential for either exists outside this JVM.
 */
public final class ReconPostgres implements AutoCloseable {

    public static final String APP_ROLE = "recon_app";
    public static final String MIGRATOR_ROLE = "recon_migrator";
    public static final String SCHEMA = "recon";

    private static final String DATABASE = "recon";
    private static final int POSTGRES_PORT = 5432;
    private static final Path INIT_SCRIPT = Path.of("ops", "postgres", "init", "01-create-roles.sh");
    private static final int EXECUTABLE = 0755;

    private static final ReconPostgres SHARED = new ReconPostgres();

    private final String migratorPassword = randomPassword();
    private final String appPassword = randomPassword();
    private final PostgreSQLContainer<?> container = LoopbackContainers.postgres()
            .withEnv("RECON_DB_MIGRATOR_PASSWORD", migratorPassword)
            .withEnv("RECON_DB_APP_PASSWORD", appPassword)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(INIT_SCRIPT, EXECUTABLE),
                    "/docker-entrypoint-initdb.d/" + INIT_SCRIPT.getFileName());

    private ReconPostgres() {
    }

    /** For {@code @DynamicPropertySource}: points the datasource and Flyway at the shared instance. */
    public static void register(DynamicPropertyRegistry registry) {
        SHARED.start();
        registry.add("spring.datasource.url", SHARED::jdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> SHARED.appPassword);
        registry.add("spring.flyway.user", () -> MIGRATOR_ROLE);
        registry.add("spring.flyway.password", () -> SHARED.migratorPassword);
    }

    /**
     * A started instance of its own, migrated as the application would migrate it. The caller
     * closes it. Flyway is configured here as application.yml configures it for the application.
     */
    public static ReconPostgres throwaway() {
        ReconPostgres instance = new ReconPostgres();
        instance.start();
        Flyway.configure()
                .dataSource(instance.jdbcUrl(), MIGRATOR_ROLE, instance.migratorPassword)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .load()
                .migrate();
        return instance;
    }

    public Connection connectAsApp() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP_ROLE, appPassword);
    }

    /** The image's superuser: for a test that has to change what the roles may do. */
    public Connection connectAsSuperuser() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    public void close() {
        container.stop();
    }

    private synchronized void start() {
        if (!container.isRunning()) {
            container.start();
        }
    }

    /** The container's own URL names the image's default database; the application's is recon. */
    private String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(POSTGRES_PORT)
                + "/" + DATABASE;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
