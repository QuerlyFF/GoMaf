#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd docker
need_cmd flock
need_cmd curl
validate_prod_config
runtime_prepare

exec 9>"$RUNTIME_DIR/deploy.lock"
flock -n 9 || die "Уже выполняется deploy, rollback или restore."

PREVIOUS_IMAGE="$(cat "$RUNTIME_DIR/previous_image" 2>/dev/null || true)"
CURRENT_IMAGE="$(cat "$RUNTIME_DIR/current_image" 2>/dev/null || true)"
PREVIOUS_SOURCE_HASH="$(cat "$RUNTIME_DIR/previous_source_hash" 2>/dev/null || true)"
CURRENT_SOURCE_HASH="$(cat "$RUNTIME_DIR/current_source_hash" 2>/dev/null || true)"
PREVIOUS_RELEASE="$(cat "$RUNTIME_DIR/previous_release" 2>/dev/null || true)"
CURRENT_RELEASE="$(active_release_dir)"
[[ -n "$PREVIOUS_IMAGE" && "$PREVIOUS_IMAGE" != "gomaf/app:current" ]] || die "Нет предыдущего image для rollback."
[[ -n "$CURRENT_IMAGE" ]] || die "Не найден current_image."
[[ -n "$PREVIOUS_SOURCE_HASH" && -n "$CURRENT_SOURCE_HASH" ]] || die "Не найдены source-hash metadata для безопасного rollback."
[[ -n "$PREVIOUS_RELEASE" && -f "$PREVIOUS_RELEASE/compose.prod.yaml" ]] || die "Нет предыдущего release snapshot для rollback."
docker image inspect "$PREVIOUS_IMAGE" >/dev/null 2>&1 || die "Image $PREVIOUS_IMAGE отсутствует локально."
PREVIOUS_IMAGE_HASH="$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$PREVIOUS_IMAGE" 2>/dev/null || true)"
[[ "$PREVIOUS_IMAGE_HASH" == "$PREVIOUS_SOURCE_HASH" ]] || die "Source hash предыдущего image не совпадает с runtime metadata."

log "Rollback меняет код/Compose/Caddy, но не откатывает пользовательские данные БД."
log "Останавливаю внешний трафик перед переключением версии."
compose_at "$CURRENT_RELEASE" stop caddy app >/dev/null 2>&1 || true

backup_output=""
if ! backup_output="$("$PROJECT_ROOT/ops/backup.sh" prerollback)"; then
  printf 'APP_IMAGE=%s\n' "$CURRENT_IMAGE" > "$RUNTIME_ENV"
  compose_at "$CURRENT_RELEASE" up -d --no-deps app || true
  wait_healthy_at "$CURRENT_RELEASE" app 180 || true
  compose_at "$CURRENT_RELEASE" up -d --no-deps --force-recreate caddy || true
  die "Backup перед rollback не удался; версия не переключена."
fi
ROLLBACK_BACKUP="$(printf '%s\n' "$backup_output" | tail -n 1)"
log "Backup перед rollback: $ROLLBACK_BACKUP"

return_current() {
  log "Предыдущая версия не запустилась; возвращаю текущую."
  disable_forward_compat_mode
  printf 'APP_IMAGE=%s\n' "$CURRENT_IMAGE" > "$RUNTIME_ENV"
  chmod 600 "$RUNTIME_ENV"
  compose_at "$CURRENT_RELEASE" up -d db || true
  wait_healthy_at "$CURRENT_RELEASE" db 150 || true
  compose_at "$CURRENT_RELEASE" run --rm --no-deps db-role-bootstrap || true
  compose_at "$CURRENT_RELEASE" run --rm --no-deps db-finalize || true
  compose_at "$CURRENT_RELEASE" up -d --no-deps app || true
  wait_healthy_at "$CURRENT_RELEASE" app 180 || true
  compose_at "$CURRENT_RELEASE" up -d --no-deps --force-recreate caddy || true
  wait_local_https_health "$(env_get DOMAIN)" 180 3 || true
}

printf 'APP_IMAGE=%s\n' "$PREVIOUS_IMAGE" > "$RUNTIME_ENV"
chmod 600 "$RUNTIME_ENV"
# The database may already contain migrations newer than the immutable previous
# release. Keep data in place and disable Flyway only for that previous app.
enable_forward_compat_mode "$PREVIOUS_RELEASE"
compose_at "$PREVIOUS_RELEASE" up -d db
wait_healthy_at "$PREVIOUS_RELEASE" db 150 || { return_current; die "PostgreSQL не healthy."; }
# Never execute previous immutable db-prepare: WP11-022 would restore runtime
# schema ownership/DDL. Reassert current hardening grants, then launch old code
# with Flyway disabled against the forward-compatible schema.
compose_at "$CURRENT_RELEASE" run --rm --no-deps db-role-bootstrap || { return_current; die "DB role bootstrap перед rollback не прошёл."; }
compose_at "$CURRENT_RELEASE" run --rm --no-deps db-finalize || { return_current; die "DB privilege finalization перед rollback не прошёл."; }
compose_at "$PREVIOUS_RELEASE" up -d --no-deps app || { return_current; die "Не удалось создать предыдущий app."; }
if ! wait_healthy_at "$PREVIOUS_RELEASE" app 240; then
  compose_at "$PREVIOUS_RELEASE" logs --tail=300 app || true
  return_current
  die "Предыдущий app несовместим с текущей БД; rollback отменён без изменения данных."
fi
compose_at "$PREVIOUS_RELEASE" up -d --no-deps --force-recreate caddy || { return_current; die "Не удалось запустить предыдущий Caddy."; }
if ! wait_local_https_health "$(env_get DOMAIN)" 180 3; then
  return_current
  die "Предыдущий Caddy/app не стали ready по HTTPS /healthz за 180 секунд."
fi
if ! "$PREVIOUS_RELEASE/ops/smoke-test.sh"; then
  return_current
  die "Smoke-test предыдущего release не прошёл; возвращена текущая версия."
fi

printf '%s\n' "$CURRENT_IMAGE" > "$RUNTIME_DIR/previous_image"
printf '%s\n' "$CURRENT_SOURCE_HASH" > "$RUNTIME_DIR/previous_source_hash"
printf '%s\n' "$CURRENT_RELEASE" > "$RUNTIME_DIR/previous_release"
printf '%s\n' "$PREVIOUS_IMAGE" > "$RUNTIME_DIR/current_image"
printf '%s\n' "$PREVIOUS_SOURCE_HASH" > "$RUNTIME_DIR/current_source_hash"
printf '%s\n' "$PREVIOUS_RELEASE" > "$ACTIVE_RELEASE_FILE"
printf '%s\n' "$ROLLBACK_BACKUP" > "$RUNTIME_DIR/last_predeploy_backup"
chmod 600 "$RUNTIME_DIR/previous_image" "$RUNTIME_DIR/previous_source_hash" "$RUNTIME_DIR/previous_release" \
  "$RUNTIME_DIR/current_image" "$RUNTIME_DIR/current_source_hash" "$ACTIVE_RELEASE_FILE" \
  "$RUNTIME_DIR/last_predeploy_backup"
log "Rollback PASS: $PREVIOUS_IMAGE"
