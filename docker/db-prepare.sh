#!/bin/sh
set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${APP_DB_USER:?APP_DB_USER is required}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"

case "$POSTGRES_DB" in
  ''|[0-9]*|*[!A-Za-z0-9_]*) echo "Invalid POSTGRES_DB" >&2; exit 1 ;;
esac
case "$POSTGRES_USER" in
  ''|[0-9]*|*[!a-z0-9_]*) echo "Invalid POSTGRES_USER" >&2; exit 1 ;;
esac

case "$APP_DB_PASSWORD" in
  *[!0-9A-Fa-f]*|'')
    echo "APP_DB_PASSWORD must be a non-empty hexadecimal string" >&2
    exit 1
    ;;
esac

if [ "${#APP_DB_PASSWORD}" -lt 32 ]; then
  echo "APP_DB_PASSWORD must contain at least 32 hexadecimal characters" >&2
  exit 1
fi

case "$APP_DB_USER" in
  ''|[0-9]*|*[!a-z0-9_]*)
    echo "APP_DB_USER must use lowercase letters, digits and underscore, and must not start with a digit" >&2
    exit 1
    ;;
esac

if [ "${#APP_DB_USER}" -gt 63 ]; then
  echo "APP_DB_USER is too long" >&2
  exit 1
fi

export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL="psql -v ON_ERROR_STOP=1 -h db -U $POSTGRES_USER -d $POSTGRES_DB"

# The cluster/bootstrap user stays inside the database services. The web app
# uses a dedicated non-superuser role, including when upgrading an old volume.
$PSQL -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_DB_USER') THEN CREATE ROLE $APP_DB_USER LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END \$\$;"
$PSQL -c "ALTER ROLE $APP_DB_USER WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$APP_DB_PASSWORD';"
$PSQL -c 'REVOKE CREATE ON SCHEMA public FROM PUBLIC;'
$PSQL -c "ALTER SCHEMA public OWNER TO $APP_DB_USER;"

# Existing Hibernate-created objects from v6/v7 may still belong to the old
# bootstrap role. Transfer only application objects from the public schema.
$PSQL -At -c "SELECT format('ALTER TABLE %I.%I OWNER TO $APP_DB_USER;', schemaname, tablename) FROM pg_tables WHERE schemaname = 'public';" | $PSQL
$PSQL -At -c "SELECT format('ALTER SEQUENCE %I.%I OWNER TO $APP_DB_USER;', sequence_schema, sequence_name) FROM information_schema.sequences WHERE sequence_schema = 'public';" | $PSQL

$PSQL -c "GRANT USAGE, CREATE ON SCHEMA public TO $APP_DB_USER;"
$PSQL -c "GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA public TO $APP_DB_USER;"
$PSQL -c "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO $APP_DB_USER;"
