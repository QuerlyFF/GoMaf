#!/bin/sh
set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${APP_DB_USER:?APP_DB_USER is required}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"
: "${MIGRATION_DB_USER:?MIGRATION_DB_USER is required}"
: "${MIGRATION_DB_PASSWORD:?MIGRATION_DB_PASSWORD is required}"

valid_ident() {
  case "$1" in
    ''|[0-9]*|*[!a-z0-9_]*) return 1 ;;
  esac
  [ "${#1}" -le 63 ]
}
valid_secret() {
  case "$1" in ''|*[!0-9A-Fa-f]*) return 1 ;; esac
  [ "${#1}" -ge 32 ]
}

valid_ident "$POSTGRES_USER" || { echo "Invalid POSTGRES_USER" >&2; exit 1; }
valid_ident "$APP_DB_USER" || { echo "Invalid APP_DB_USER" >&2; exit 1; }
valid_ident "$MIGRATION_DB_USER" || { echo "Invalid MIGRATION_DB_USER" >&2; exit 1; }
[ "$APP_DB_USER" != "$MIGRATION_DB_USER" ] || { echo "Runtime and migration DB roles must differ" >&2; exit 1; }
valid_secret "$APP_DB_PASSWORD" || { echo "APP_DB_PASSWORD must be >=32 hex chars" >&2; exit 1; }
valid_secret "$MIGRATION_DB_PASSWORD" || { echo "MIGRATION_DB_PASSWORD must be >=32 hex chars" >&2; exit 1; }
case "$POSTGRES_DB" in ''|[0-9]*|*[!A-Za-z0-9_]*) echo "Invalid POSTGRES_DB" >&2; exit 1 ;; esac

export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL="psql -v ON_ERROR_STOP=1 -h db -U $POSTGRES_USER -d $POSTGRES_DB"

# Only the bootstrap role can create/alter login roles. Both application roles
# remain non-superusers without role/database creation or RLS bypass rights.
$PSQL -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_DB_USER') THEN CREATE ROLE $APP_DB_USER LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END \$\$;"
$PSQL -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$MIGRATION_DB_USER') THEN CREATE ROLE $MIGRATION_DB_USER LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END \$\$;"
$PSQL -c "ALTER ROLE $APP_DB_USER WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$APP_DB_PASSWORD';"
$PSQL -c "ALTER ROLE $MIGRATION_DB_USER WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$MIGRATION_DB_PASSWORD';"

# Upgrade path from WP11-022: the old runtime role owned the public schema and
# application objects. Transfer that ownership to the migration role before
# Flyway, then strip runtime DDL privileges.
$PSQL -c "REASSIGN OWNED BY $APP_DB_USER TO $MIGRATION_DB_USER;"
# pg_restore --no-owner and some historical installs can leave public objects
# owned by the bootstrap role. Flyway must own existing application relations
# before it can validate history or ALTER them. Restrict the transfer to public.
$PSQL -At -c "SELECT format('ALTER TABLE %I.%I OWNER TO $MIGRATION_DB_USER;', schemaname, tablename) FROM pg_tables WHERE schemaname='public' AND tableowner <> '$MIGRATION_DB_USER';" | $PSQL
$PSQL -At -c "SELECT format('ALTER SEQUENCE %I.%I OWNER TO $MIGRATION_DB_USER;', sequence_schema, sequence_name) FROM information_schema.sequences WHERE sequence_schema='public' AND sequence_name IN (SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='public' AND c.relkind='S' AND r.rolname <> '$MIGRATION_DB_USER');" | $PSQL
$PSQL -c 'REVOKE CREATE ON SCHEMA public FROM PUBLIC;'
$PSQL -c "ALTER SCHEMA public OWNER TO $MIGRATION_DB_USER;"
$PSQL -c "REVOKE ALL PRIVILEGES ON SCHEMA public FROM $APP_DB_USER;"
$PSQL -c "GRANT USAGE ON SCHEMA public TO $APP_DB_USER;"
$PSQL -c "GRANT USAGE, CREATE ON SCHEMA public TO $MIGRATION_DB_USER;"

# Future Flyway-created objects inherit only the runtime rights actually needed.
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public REVOKE ALL ON TABLES FROM $APP_DB_USER;"
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $APP_DB_USER;"
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public REVOKE ALL ON SEQUENCES FROM $APP_DB_USER;"
$PSQL -c "ALTER DEFAULT PRIVILEGES FOR ROLE $MIGRATION_DB_USER IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO $APP_DB_USER;"
