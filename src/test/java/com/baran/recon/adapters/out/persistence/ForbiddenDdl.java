package com.baran.recon.adapters.out.persistence;

/**
 * DDL the application role must be refused, each with the one privilege whose absence refuses it.
 * {@code ApplicationRoleCannotRunDdlTest} asserts every statement is refused; its break proof grants
 * the privilege and asserts the statement then succeeds. Keeping both halves in one place means a
 * statement cannot be added to the first without saying what the second has to grant.
 */
enum ForbiddenDdl {

    CREATE_TABLE_IN_RECON("CREATE TABLE recon.probe (id INT)",
            "GRANT CREATE ON SCHEMA recon TO recon_app"),
    CREATE_TABLE_IN_PUBLIC("CREATE TABLE public.probe (id INT)",
            "GRANT USAGE, CREATE ON SCHEMA public TO recon_app"),
    CREATE_TEMPORARY_TABLE("CREATE TEMPORARY TABLE probe (id INT)",
            "GRANT TEMPORARY ON DATABASE recon TO recon_app"),
    CREATE_SCHEMA("CREATE SCHEMA probe",
            "GRANT CREATE ON DATABASE recon TO recon_app"),
    CREATE_FUNCTION_IN_RECON("CREATE FUNCTION recon.probe() RETURNS INT LANGUAGE sql AS 'SELECT 1'",
            "GRANT CREATE ON SCHEMA recon TO recon_app"),
    ALTER_MIGRATOR_TABLE("ALTER TABLE recon.flyway_schema_history ADD COLUMN probe INT",
            "ALTER TABLE recon.flyway_schema_history OWNER TO recon_app"),
    DROP_MIGRATOR_TABLE("DROP TABLE recon.flyway_schema_history",
            "ALTER TABLE recon.flyway_schema_history OWNER TO recon_app"),
    TRUNCATE_MIGRATOR_TABLE("TRUNCATE recon.flyway_schema_history",
            "ALTER TABLE recon.flyway_schema_history OWNER TO recon_app"),
    DROP_SCHEMA("DROP SCHEMA recon CASCADE",
            "ALTER SCHEMA recon OWNER TO recon_app");

    static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final String statement;
    private final String withheldGrant;

    ForbiddenDdl(String statement, String withheldGrant) {
        this.statement = statement;
        this.withheldGrant = withheldGrant;
    }

    String statement() {
        return statement;
    }

    String withheldGrant() {
        return withheldGrant;
    }
}
