-- The baseline: where the application may connect and what it may do there. Flyway created the
-- recon schema before running this (spring.flyway.schemas), connected as recon_migrator, so the
-- migrator owns the schema and every object that is ever created in it.
--
-- recon_app, created with its password by ops/postgres/init, is given no CREATE anywhere and owns
-- nothing. Creating an object needs CREATE on its schema, and altering or dropping one needs
-- ownership, so it cannot run DDL at all. Nothing is granted to it on tables by default either:
-- each later migration grants exactly the verbs the code issues on the table it adds, and the
-- append-only audit tables will get SELECT and INSERT alone (TDD 8.4).

-- The bootstrap revoked every database privilege from PUBLIC, TEMPORARY included, so a temporary
-- table is DDL recon_app cannot run either. CONNECT is all it gets back.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO recon_app', current_database());
    -- Unqualified names resolve to recon for every role, without each connection string saying so.
    EXECUTE format('ALTER DATABASE %I SET search_path = recon', current_database());
END
$$;

-- public stays empty and closed. PostgreSQL 15 already withholds CREATE on it from PUBLIC; USAGE
-- goes as well, so recon is the only schema anything here can resolve names in.
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- USAGE lets recon_app name objects in recon. It is deliberately not CREATE.
GRANT USAGE ON SCHEMA recon TO recon_app;
