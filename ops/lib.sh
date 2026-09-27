#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${GOMAF_ENV_FILE:-/etc/gomaf/production.env}"
RUNTIME_DIR="${GOMAF_RUNTIME_DIR:-$PROJECT_ROOT/.deploy}"
RUNTIME_ENV="$RUNTIME_DIR/runtime.env"
RELEASES_DIR="$RUNTIME_DIR/releases"
ACTIVE_RELEASE_FILE="$RUNTIME_DIR/active_release"
FORWARD_COMPAT_RELEASE_FILE="$RUNTIME_DIR/forward_compat_release"
FORWARD_COMPAT_OVERRIDE="$RUNTIME_DIR/forward-compat.override.yaml"

log() { printf '[gomaf] %s\n' "$*"; }
die() { printf '[gomaf] ERROR: %s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Не найдена команда: $1"
}

env_get() {
  local key="$1" line value
  [[ -r "$ENV_FILE" ]] || die "Не найден конфиг $ENV_FILE"
  line="$(grep -m1 -E "^${key}=" "$ENV_FILE" || true)"
  [[ -n "$line" ]] || die "В $ENV_FILE отсутствует $key"
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

env_get_default() {
  local key="$1" default="$2" line value
  [[ -r "$ENV_FILE" ]] || die "Не найден конфиг $ENV_FILE"
  line="$(grep -m1 -E "^${key}=" "$ENV_FILE" || true)"
  if [[ -z "$line" ]]; then
    printf '%s' "$default"
    return 0
  fi
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

runtime_prepare() {
  mkdir -p "$RUNTIME_DIR" "$RELEASES_DIR"
  chmod 700 "$RUNTIME_DIR" "$RELEASES_DIR"
  if [[ ! -f "$RUNTIME_ENV" ]]; then
    printf 'APP_IMAGE=gomaf/app:current\n' > "$RUNTIME_ENV"
    chmod 600 "$RUNTIME_ENV"
  fi
}

active_release_dir() {
  local dir=""
  if [[ -r "$ACTIVE_RELEASE_FILE" ]]; then
    dir="$(cat "$ACTIVE_RELEASE_FILE")"
    if [[ -n "$dir" && -f "$dir/compose.prod.yaml" ]]; then
      printf '%s' "$dir"
      return 0
    fi
  fi
  printf '%s' "$PROJECT_ROOT"
}

compose_at() {
  local root="$1"; shift
  [[ -f "$root/compose.prod.yaml" ]] || die "Не найден $root/compose.prod.yaml"
  runtime_prepare
  local -a extra_files=()
  if [[ -r "$FORWARD_COMPAT_RELEASE_FILE" && -f "$FORWARD_COMPAT_OVERRIDE" ]]; then
    local compat_root
    compat_root="$(cat "$FORWARD_COMPAT_RELEASE_FILE" 2>/dev/null || true)"
    if [[ -n "$compat_root" && "$compat_root" == "$root" ]]; then
      extra_files=(-f "$FORWARD_COMPAT_OVERRIDE")
    fi
  fi
  docker compose \
    --project-name gomaf \
    --project-directory "$root" \
    --env-file "$ENV_FILE" \
    --env-file "$RUNTIME_ENV" \
    -f "$root/compose.prod.yaml" "${extra_files[@]}" "$@"
}

enable_forward_compat_mode() {
  local root="$1"
  [[ -f "$root/compose.prod.yaml" ]] || die "Нельзя включить rollback compatibility: release отсутствует: $root"
  runtime_prepare
  cat > "$FORWARD_COMPAT_OVERRIDE" <<'YAML'
services:
  app:
    environment:
      # Older immutable releases do not contain the newer Flyway migration file.
      # The v10 schema is intentionally backward-compatible at the Hibernate level,
      # so emergency code rollback starts the previous app with Flyway disabled.
      SPRING_FLYWAY_ENABLED: "false"
YAML
  printf '%s\n' "$root" > "$FORWARD_COMPAT_RELEASE_FILE"
  chmod 600 "$FORWARD_COMPAT_OVERRIDE" "$FORWARD_COMPAT_RELEASE_FILE"
}

disable_forward_compat_mode() {
  rm -f "$FORWARD_COMPAT_OVERRIDE" "$FORWARD_COMPAT_RELEASE_FILE"
}

compose() {
  compose_at "$(active_release_dir)" "$@"
}


db_query_at() {
  local root="$1" sql="$2"
  compose_at "$root" exec -T db sh -ec '
    PGPASSWORD="$POSTGRES_PASSWORD" exec psql -v ON_ERROR_STOP=1 -At \
      -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"
  ' sh "$sql"
}

db_query_active() {
  db_query_at "$(active_release_dir)" "$1"
}

wait_healthy_at() {
  local root="$1" service="$2" timeout="${3:-180}" id status started now
  id="$(compose_at "$root" ps -q "$service")"
  [[ -n "$id" ]] || return 1
  started="$(date +%s)"
  while true; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)"
    case "$status" in
      healthy|running) return 0 ;;
      unhealthy|exited|dead) return 1 ;;
    esac
    now="$(date +%s)"
    (( now - started < timeout )) || return 1
    sleep 2
  done
}

wait_healthy() {
  wait_healthy_at "$(active_release_dir)" "$1" "${2:-180}"
}

wait_local_https_health() {
  local domain="${1:-$(env_get DOMAIN)}" timeout="${2:-180}" interval="${3:-3}" started now
  started="$(date +%s)"
  while true; do
    if curl --resolve "$domain:443:127.0.0.1" --fail --silent --show-error --max-time 8 \
        "https://$domain/healthz" >/dev/null 2>&1; then
      return 0
    fi
    now="$(date +%s)"
    (( now - started < timeout )) || return 1
    sleep "$interval"
  done
}

validate_prod_config() {
  local allow_legacy_admin="${1:-0}"
  local domain admin pass dbpass appdbpass migrationdbpass hash appmem dbmem caddymem backup_dir retention pass_bytes weak
  domain="$(env_get DOMAIN)"
  admin="$(env_get APP_ADMIN_USERNAME)"
  pass="$(env_get APP_ADMIN_PASSWORD)"
  dbpass="$(env_get POSTGRES_PASSWORD)"
  appdbpass="$(env_get APP_DB_PASSWORD)"
  migrationdbpass="$(env_get MIGRATION_DB_PASSWORD)"
  hash="$(env_get APP_SECURITY_IP_HASH_SECRET)"
  appmem="$(env_get APP_MEMORY_LIMIT)"
  dbmem="$(env_get DB_MEMORY_LIMIT)"
  caddymem="$(env_get CADDY_MEMORY_LIMIT)"
  backup_dir="$(env_get BACKUP_DIR)"
  retention="$(env_get BACKUP_RETENTION_DAYS)"

  [[ "$domain" == "gomaf.online" ]] || die "DOMAIN должен быть gomaf.online, сейчас: $domain"
  if [[ "$admin" != "admin" ]]; then
    local state_file="/etc/gomaf/v10-v9-admin-username" state_admin=""
    if [[ "$allow_legacy_admin" == 1 && -r "$state_file" && ! -L "$state_file" ]]; then
      state_admin="$(cat "$state_file")"
    fi
    [[ -n "$state_admin" && "$admin" == "$state_admin" ]] \
      || die "APP_ADMIN_USERNAME должен быть строго admin (legacy v9 разрешён только во время подтверждённого v10 upgrade)."
  fi

  pass_bytes="$(printf '%s' "$pass" | wc -c | tr -d ' ')"
  [[ "$pass" != CHANGE_ME* && ${#pass} -ge 12 && ${#pass} -le 64 && "$pass_bytes" -le 72 ]] \
    || die "APP_ADMIN_PASSWORD: 12-64 символа и максимум 72 UTF-8 байта."
  weak="${pass,,}"
  case "$weak" in
    test|admin|password|12345678|qwerty123) die "APP_ADMIN_PASSWORD является тестовым/слабым." ;;
  esac

  [[ "$dbpass" != *'__GENERATED_'* && ${#dbpass} -ge 32 ]] || die "POSTGRES_PASSWORD не сгенерирован или слишком короткий."
  [[ "$appdbpass" != *'__GENERATED_'* && ${#appdbpass} -ge 32 ]] || die "APP_DB_PASSWORD не сгенерирован или слишком короткий."
  [[ "$migrationdbpass" != *'__GENERATED_'* && ${#migrationdbpass} -ge 32 ]] || die "MIGRATION_DB_PASSWORD не сгенерирован или слишком короткий."
  [[ "$hash" != *'__GENERATED_'* && ${#hash} -ge 32 ]] || die "APP_SECURITY_IP_HASH_SECRET не сгенерирован или слишком короткий."
  [[ "$(env_get POSTGRES_DB)" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "Некорректный POSTGRES_DB."
  [[ "$(env_get POSTGRES_USER)" =~ ^[a-z_][a-z0-9_]*$ ]] || die "Некорректный POSTGRES_USER."
  [[ "$(env_get APP_DB_USER)" =~ ^[a-z_][a-z0-9_]*$ ]] || die "Некорректный APP_DB_USER."
  [[ "$(env_get MIGRATION_DB_USER)" =~ ^[a-z_][a-z0-9_]*$ ]] || die "Некорректный MIGRATION_DB_USER."
  [[ "$(env_get MIGRATION_DB_USER)" != "$(env_get APP_DB_USER)" ]] || die "MIGRATION_DB_USER и APP_DB_USER должны различаться."
  [[ "$appmem" =~ ^[0-9]+[mMgG]$ ]] || die "Некорректный APP_MEMORY_LIMIT."
  [[ "$dbmem" =~ ^[0-9]+[mMgG]$ ]] || die "Некорректный DB_MEMORY_LIMIT."
  [[ "$caddymem" =~ ^[0-9]+[mMgG]$ ]] || die "Некорректный CADDY_MEMORY_LIMIT."
  [[ "$backup_dir" == "/srv/gomaf-backups" ]] || die "BACKUP_DIR должен быть строго /srv/gomaf-backups в production."
  [[ "$retention" =~ ^[0-9]+$ && "$retention" -ge 1 && "$retention" -le 3650 ]] || die "BACKUP_RETENTION_DAYS должен быть числом 1-3650."
  local min_keep min_free target_free log_retention
  min_keep="$(env_get_default BACKUP_MIN_KEEP 3)"
  min_free="$(env_get_default BACKUP_MIN_FREE_MB 2048)"
  target_free="$(env_get_default BACKUP_TARGET_FREE_MB 3072)"
  log_retention="$(env_get_default LOG_EXPORT_RETENTION_DAYS 14)"
  [[ "$min_keep" =~ ^[0-9]+$ && "$min_keep" -ge 2 && "$min_keep" -le 30 ]] || die "BACKUP_MIN_KEEP должен быть 2-30."
  [[ "$min_free" =~ ^[0-9]+$ && "$min_free" -ge 512 ]] || die "BACKUP_MIN_FREE_MB должен быть >= 512."
  [[ "$target_free" =~ ^[0-9]+$ && "$target_free" -ge "$min_free" ]] || die "BACKUP_TARGET_FREE_MB должен быть >= BACKUP_MIN_FREE_MB."
  [[ "$log_retention" =~ ^[0-9]+$ && "$log_retention" -ge 1 && "$log_retention" -le 365 ]] || die "LOG_EXPORT_RETENTION_DAYS должен быть 1-365."

  if [[ "$ENV_FILE" == "/etc/gomaf/production.env" ]]; then
    local mode owner group
    mode="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || true)"
    owner="$(stat -c '%U' "$ENV_FILE" 2>/dev/null || true)"
    group="$(stat -c '%G' "$ENV_FILE" 2>/dev/null || true)"
    [[ "$owner" == root && "$group" == root && "$mode" == 600 ]] \
      || die "$ENV_FILE должен иметь владельца root:root и права 0600."
  fi
}

validate_backup_dump_at() {
  local root="$1" backup="$2" db_id container_dump host_sha container_sha
  [[ -s "$backup" ]] || die "Backup отсутствует или пуст: $backup"
  db_id="$(compose_at "$root" ps -q db)"
  [[ -n "$db_id" ]] || die "Не найден container id PostgreSQL для проверки backup."
  container_dump="/var/lib/postgresql/data/.gomaf-validate-$(date -u +%s)-$$.dump"
  # Direct docker exec without a TTY is binary-safe and writes as the postgres
  # container user. docker cp cannot target the hardened container /tmp tmpfs.
  if ! docker exec -i "$db_id" sh -ec '
      umask 077
      cat > "$1"
      test -s "$1"
    ' sh "$container_dump" < "$backup"; then
    docker exec "$db_id" rm -f -- "$container_dump" >/dev/null 2>&1 || true
    return 1
  fi
  if ! container_sha="$(docker exec "$db_id" sh -ec '
      test -s "$1"
      pg_restore --list "$1" >/dev/null
      sha256sum "$1" | cut -d" " -f1
    ' sh "$container_dump")"; then
    docker exec "$db_id" rm -f -- "$container_dump" >/dev/null 2>&1 || true
    return 1
  fi
  host_sha="$(sha256sum "$backup" | awk '{print $1}')"
  docker exec "$db_id" rm -f -- "$container_dump" >/dev/null 2>&1 || true
  [[ "$container_sha" =~ ^[0-9a-f]{64}$ && "$host_sha" == "$container_sha" ]]
}

restore_database_from_dump_at() {
  local root="$1" backup="$2" db_id container_dump prod_db suffix restore_db previous_db
  [[ -s "$backup" ]] || die "Backup отсутствует или пуст: $backup"
  validate_backup_dump_at "$root" "$backup"
  db_id="$(compose_at "$root" ps -q db)"
  [[ -n "$db_id" ]] || die "Не найден container id PostgreSQL для restore."
  prod_db="$(env_get POSTGRES_DB)"
  [[ "$prod_db" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "Некорректный POSTGRES_DB для restore."
  suffix="$(date -u +%Y%m%d%H%M%S)_$$"
  restore_db="gomaf_restore_${suffix}"
  previous_db="gomaf_previous_${suffix}"
  container_dump="/var/lib/postgresql/data/.gomaf-restore-${suffix}.dump"

  if ! docker exec -i "$db_id" sh -ec '
      umask 077
      cat > "$1"
      test -s "$1"
    ' sh "$container_dump" < "$backup"; then
    docker exec "$db_id" rm -f -- "$container_dump" >/dev/null 2>&1 || true
    return 1
  fi

  # Restore into a completely separate database first. This is intentionally
  # not pg_restore --clean over the live database: a newer schema can contain
  # dependencies that an older dump does not know how to remove safely.
  if ! docker exec "$db_id" sh -ec '
      set -eu
      export PGPASSWORD="$POSTGRES_PASSWORD"
      restore_db="$1"
      dump="$2"
      dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$restore_db" >/dev/null 2>&1 || true
      createdb -h 127.0.0.1 -U "$POSTGRES_USER" -T template0 "$restore_db"
      if ! pg_restore -h 127.0.0.1 -U "$POSTGRES_USER" -d "$restore_db" \
          --no-owner --no-acl --single-transaction "$dump"; then
        dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$restore_db" >/dev/null 2>&1 || true
        exit 1
      fi
      q="$(printf "\\047")"
      sql="SELECT CASE WHEN to_regclass(${q}public.users${q}) IS NOT NULL AND to_regclass(${q}public.flyway_schema_history${q}) IS NOT NULL THEN 1 ELSE 0 END;"
      psql -v ON_ERROR_STOP=1 -At -h 127.0.0.1 -U "$POSTGRES_USER" -d "$restore_db" -c "$sql" | grep -qx 1
    ' sh "$restore_db" "$container_dump"; then
    docker exec "$db_id" sh -ec 'export PGPASSWORD="$POSTGRES_PASSWORD"; dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$1" >/dev/null 2>&1 || true; rm -f -- "$2"' sh "$restore_db" "$container_dump" >/dev/null 2>&1 || true
    return 1
  fi

  # The restored database is complete. Only now swap database names. The old
  # production database remains available under a temporary name until
  # the current trusted DB-role hardening scripts have succeeded on the restored database.
  if ! docker exec "$db_id" sh -ec '
      set -eu
      export PGPASSWORD="$POSTGRES_PASSWORD"
      prod="$1"; restored="$2"; previous="$3"
      dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$previous" >/dev/null 2>&1 || true
      q="$(printf "\\047")"
      sql="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN (${q}${prod}${q},${q}${restored}${q}) AND pid <> pg_backend_pid();"
      psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -c "$sql" >/dev/null
      psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres \
        -c "ALTER DATABASE \"$prod\" RENAME TO \"$previous\";"
      if ! psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres \
          -c "ALTER DATABASE \"$restored\" RENAME TO \"$prod\";"; then
        psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres \
          -c "ALTER DATABASE \"$previous\" RENAME TO \"$prod\";" >/dev/null 2>&1 || true
        exit 1
      fi
    ' sh "$prod_db" "$restore_db" "$previous_db"; then
    docker exec "$db_id" sh -ec 'export PGPASSWORD="$POSTGRES_PASSWORD"; dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$1" >/dev/null 2>&1 || true; rm -f -- "$2"' sh "$restore_db" "$container_dump" >/dev/null 2>&1 || true
    return 1
  fi

  # Never invoke db-prepare from an immutable previous release here: WP11-022
  # would restore runtime schema ownership and DDL. The lib containing this
  # function belongs to the trusted current hardening release, so PROJECT_ROOT
  # is the only allowed privilege-finalization source.
  if ! compose_at "$PROJECT_ROOT" run --rm --no-deps db-role-bootstrap \
      || ! compose_at "$PROJECT_ROOT" run --rm --no-deps db-finalize; then
    # Privilege hardening failed on the restored DB. Swap the untouched previous DB back.
    docker exec "$db_id" sh -ec '
      set -eu
      export PGPASSWORD="$POSTGRES_PASSWORD"
      prod="$1"; previous="$2"; failed="gomaf_failed_$3"
      dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$failed" >/dev/null 2>&1 || true
      q="$(printf "\\047")"
      sql="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN (${q}${prod}${q},${q}${previous}${q}) AND pid <> pg_backend_pid();"
      psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -c "$sql" >/dev/null
      psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE \"$prod\" RENAME TO \"$failed\";"
      psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE \"$previous\" RENAME TO \"$prod\";"
      dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$failed" >/dev/null 2>&1 || true
    ' sh "$prod_db" "$previous_db" "$suffix" >/dev/null 2>&1 || true
    docker exec "$db_id" rm -f -- "$container_dump" >/dev/null 2>&1 || true
    return 1
  fi

  # Role bootstrap/finalization succeeded, so the restored DB is now production. Remove
  # the previous database copy; the verified filesystem backup still remains.
  docker exec "$db_id" sh -ec '
    export PGPASSWORD="$POSTGRES_PASSWORD"
    dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$1"
    rm -f -- "$2"
  ' sh "$previous_db" "$container_dump" >/dev/null 2>&1 || true
}
