#!/bin/sh
# Creates the two login roles and the database. Everything after this - the recon schema and what
# each role may do in it - is a Flyway migration.
#
# Both roles are created here rather than in a migration because each needs a password, and a
# migration could only receive one as a placeholder spliced into its SQL text, which Flyway prints
# back in full when a statement fails. Here the passwords travel as psql variables and are quoted
# by psql (:'name'), never spliced into the SQL text by the shell, and nothing echoes them.
#
# recon_migrator owns the database and runs the migrations. It is not a superuser and cannot create
# roles or databases. recon_app is what the application connects as: it owns nothing, and
# V1__baseline.sql grants it no CREATE anywhere, so it cannot run DDL.
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v migrator_password="$RECON_DB_MIGRATOR_PASSWORD" \
     -v app_password="$RECON_DB_APP_PASSWORD" <<'SQL'
CREATE ROLE recon_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
    PASSWORD :'migrator_password';
CREATE ROLE recon_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
    PASSWORD :'app_password';
CREATE DATABASE recon OWNER recon_migrator;
REVOKE ALL ON DATABASE recon FROM PUBLIC;
SQL
