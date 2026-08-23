#!/bin/bash
# ADR-0008: Keycloak keeps its realm in a database inside this same Postgres instance — one
# container to run, patch and pin, and one artifact to back up. Postgres' own entrypoint creates
# POSTGRES_DB and nothing else, so the second database is created here.
#
# THIS RUNS ONCE, on an empty data directory, and never again. Adding a third database later means
# creating it by hand as well as adding it here; a file in this directory is not a migration.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
	CREATE DATABASE "$KEYCLOAK_DB" OWNER "$POSTGRES_USER";
SQL

echo "Created the Keycloak database '$KEYCLOAK_DB' owned by '$POSTGRES_USER'."
