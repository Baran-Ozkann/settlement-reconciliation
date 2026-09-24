#!/bin/sh
# Creates the one role Flyway cannot create for itself: the role it migrates as. Everything after
# this, the recon schema and the application role included, is a migration.
#
# recon_migrator owns the database and may create roles, but it is not a superuser: the application
# role it creates is therefore something it can grant to and revoke from, and nothing more.
#
# The password travels as a psql variable and is quoted by psql (:'name'), never spliced into the
# SQL text by the shell.
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v migrator_password="$RECON_DB_MIGRATOR_PASSWORD" <<'SQL'
CREATE ROLE recon_migrator LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT
    PASSWORD :'migrator_password';
CREATE DATABASE recon OWNER recon_migrator;
REVOKE ALL ON DATABASE recon FROM PUBLIC;
SQL
