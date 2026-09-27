#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd docker
need_cmd sha256sum
validate_prod_config
runtime_prepare

REASON="${1:-daily}"
case "$REASON" in
  daily|manual|predeploy|prerollback|prerestore) ;;
  *) die "Неизвестный тип backup: $REASON" ;;
esac

BACKUP_DIR="$(env_get BACKUP_DIR)"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if ! compose ps --status running --services | grep -qx db; then
  die "PostgreSQL не запущен; backup не выполнен."
fi

DB_BYTES="$(compose exec -T db sh -ec 'PGPASSWORD="$POSTGRES_PASSWORD" psql -At -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT pg_database_size(current_database());"')"
[[ "$DB_BYTES" =~ ^[0-9]+$ ]] || die "Не удалось определить размер production БД."
# Use the logical DB size as a deliberately conservative upper bound for the
# additional disk reserve required before starting a compressed custom dump.
if ! "$PROJECT_ROOT/ops/backup-maintenance.sh" "$DB_BYTES"; then
  die "Недостаточно безопасного свободного места для нового backup; pg_dump не запускался."
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/gomaf-$REASON-$STAMP.dump"
TMP="$OUT.tmp"
DB_ID="$(compose ps -q db)"
[[ -n "$DB_ID" ]] || die "Не найден container id PostgreSQL."
CONTAINER_TMP="/var/lib/postgresql/data/.gomaf-backup-${STAMP}-$$.dump"

cleanup() {
  rm -f -- "$TMP"
  docker exec "$DB_ID" rm -f -- "$CONTAINER_TMP" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "Создаю backup $OUT"
# Keep the binary custom-format archive inside the PostgreSQL data volume until
# pg_restore has validated it. The DB rootfs is read-only and /tmp is tmpfs; the
# Docker archive API used by `docker cp` cannot read that tmpfs mount on this
# hardened container. A hidden file in PGDATA remains private to postgres and is
# removed by the EXIT trap immediately after the verified host copy.
CONTAINER_SHA="$(docker exec "$DB_ID" sh -ec '
  umask 077
  PGPASSWORD="$POSTGRES_PASSWORD" pg_dump \
    -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -Fc --no-owner --no-acl -f "$1"
  test -s "$1"
  pg_restore --list "$1" >/dev/null
  sha256sum "$1" | cut -d" " -f1
' sh "$CONTAINER_TMP")"
[[ "$CONTAINER_SHA" =~ ^[0-9a-f]{64}$ ]] || die "Не удалось получить SHA-256 проверенного dump внутри PostgreSQL container."

docker cp "$DB_ID:$CONTAINER_TMP" "$TMP" >/dev/null
[[ -s "$TMP" ]] || die "docker cp создал пустой backup на host."
HOST_SHA="$(sha256sum "$TMP" | awk '{print $1}')"
[[ "$HOST_SHA" == "$CONTAINER_SHA" ]] || die "SHA-256 backup после docker cp не совпадает с проверенным container dump."

mv "$TMP" "$OUT"
chmod 600 "$OUT"
printf '%s  %s\n' "$HOST_SHA" "$(basename "$OUT")" > "$OUT.sha256"
chmod 600 "$OUT.sha256"
printf '%s\n' "$OUT" > "$RUNTIME_DIR/last_successful_backup"
chmod 600 "$RUNTIME_DIR/last_successful_backup"
if [[ "$REASON" == predeploy ]]; then
  printf '%s\n' "$OUT" > "$RUNTIME_DIR/last_predeploy_backup"
  chmod 600 "$RUNTIME_DIR/last_predeploy_backup"
fi
"$PROJECT_ROOT/ops/backup-maintenance.sh" 0 >/dev/null || true
log "Backup готов: $OUT"
printf '%s\n' "$OUT"
