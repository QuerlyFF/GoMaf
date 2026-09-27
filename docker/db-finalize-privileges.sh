#!/bin/sh
set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${APP_DB_USER:?APP_DB_USER is required}"
: "${MIGRATION_DB_USER:?MIGRATION_DB_USER is required}"

valid_ident() {
  case "$1" in ''|[0-9]*|*[!a-z0-9_]*) return 1 ;; esac
  [ "${#1}" -le 63 ]
}
valid_ident "$POSTGRES_USER" || { echo "Invalid POSTGRES_USER" >&2; exit 1; }
valid_ident "$APP_DB_USER" || { echo "Invalid APP_DB_USER" >&2; exit 1; }
valid_ident "$MIGRATION_DB_USER" || { echo "Invalid MIGRATION_DB_USER" >&2; exit 1; }
[ "$APP_DB_USER" != "$MIGRATION_DB_USER" ] || { echo "Runtime and migration DB roles must differ" >&2; exit 1; }
case "$POSTGRES_DB" in ''|[0-9]*|*[!A-Za-z0-9_]*) echo "Invalid POSTGRES_DB" >&2; exit 1 ;; esac

export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL="psql -v ON_ERROR_STOP=1 -h db -U $POSTGRES_USER -d $POSTGRES_DB"

# Enforce ownership and runtime least privilege after every migration. This is
# intentionally idempotent and is also safe to run before a code rollback.
$PSQL -c "ALTER ROLE $APP_DB_USER WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;"
$PSQL -c "ALTER ROLE $MIGRATION_DB_USER WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;"
$PSQL -c 'REVOKE CREATE ON SCHEMA public FROM PUBLIC;'
$PSQL -c "ALTER SCHEMA public OWNER TO $MIGRATION_DB_USER;"
$PSQL -c "REVOKE ALL PRIVILEGES ON SCHEMA public FROM $APP_DB_USER;"
$PSQL -c "GRANT USAGE ON SCHEMA public TO $APP_DB_USER;"

# All application relations must be migration-owned. Flyway history is included
# in ownership but deliberately excluded from runtime visibility below.
$PSQL -At -c "SELECT format('ALTER TABLE %I.%I OWNER TO $MIGRATION_DB_USER;', schemaname, tablename) FROM pg_tables WHERE schemaname='public' AND tableowner <> '$MIGRATION_DB_USER';" | $PSQL
$PSQL -At -c "SELECT format('ALTER SEQUENCE %I.%I OWNER TO $MIGRATION_DB_USER;', sequence_schema, sequence_name) FROM information_schema.sequences WHERE sequence_schema='public' AND sequence_name IN (SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='public' AND c.relkind='S' AND r.rolname <> '$MIGRATION_DB_USER');" | $PSQL

$PSQL -c "REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM $APP_DB_USER;"
$PSQL -c "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $APP_DB_USER;"
$PSQL -c "REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM $APP_DB_USER;"
$PSQL -c "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $APP_DB_USER;"
$PSQL -c "REVOKE ALL PRIVILEGES ON TABLE flyway_schema_history FROM $APP_DB_USER;"

$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public REVOKE ALL ON TABLES FROM $APP_DB_USER;"
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $APP_DB_USER;"
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public REVOKE ALL ON SEQUENCES FROM $APP_DB_USER;"
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO $APP_DB_USER;"

# Fail closed if the runtime role still owns public objects or has schema CREATE.
owner_count="$($PSQL -At -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m') AND r.rolname='$APP_DB_USER';")"
[ "$owner_count" = "0" ] || { echo "Runtime role still owns $owner_count public objects" >&2; exit 1; }
can_create="$($PSQL -At -c "SELECT has_schema_privilege('$APP_DB_USER','public','CREATE');")"
[ "$can_create" = "f" ] || { echo "Runtime role still has CREATE on public schema" >&2; exit 1; }
flyway_select="$($PSQL -At -c "SELECT has_table_privilege('$APP_DB_USER','public.flyway_schema_history','SELECT');")"
[ "$flyway_select" = "f" ] || { echo "Runtime role can still read flyway_schema_history" >&2; exit 1; }
