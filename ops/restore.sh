#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd docker
need_cmd flock
need_cmd sha256sum
need_cmd curl
validate_prod_config
runtime_prepare

exec 9>"$RUNTIME_DIR/deploy.lock"
flock -n 9 || die "Уже выполняется deploy, rollback или restore."

BACKUP="${1:-}"
[[ -n "$BACKUP" && -f "$BACKUP" ]] || die "Использование: ./ops/restore.sh /путь/backup.dump"
if [[ -f "$BACKUP.sha256" ]]; then
  (cd "$(dirname "$BACKUP")" && sha256sum -c "$(basename "$BACKUP").sha256") \
    || die "SHA-256 backup не совпадает; restore остановлен."
else
  log "Для backup нет .sha256 sidecar; целостность по хэшу автоматически не подтверждена."
fi

ACTIVE="$(active_release_dir)"
compose up -d db
wait_healthy db 150 || die "PostgreSQL не стал healthy."
log "Проверяю, что файл является читаемым pg_restore dump."
validate_backup_dump_at "$ACTIVE" "$BACKUP" || die "pg_restore не смог прочитать backup."

printf 'Будет полностью заменена production БД из %s. Введи RESTORE: ' "$BACKUP"
read -r answer
[[ "$answer" == RESTORE ]] || die "Отменено."

APP_WAS_RUNNING=0
CADDY_WAS_RUNNING=0
if compose ps --status running --services | grep -qx app; then APP_WAS_RUNNING=1; fi
if compose ps --status running --services | grep -qx caddy; then CADDY_WAS_RUNNING=1; fi

log "Останавливаю внешний трафик и приложение перед страховочным backup."
compose stop caddy app >/dev/null 2>&1 || true

safety_output=""
if ! safety_output="$("$PROJECT_ROOT/ops/backup.sh" prerestore)"; then
  [[ "$APP_WAS_RUNNING" -eq 1 ]] && compose up -d --no-deps app || true
  [[ "$APP_WAS_RUNNING" -eq 1 ]] && wait_healthy app 180 || true
  [[ "$CADDY_WAS_RUNNING" -eq 1 ]] && compose up -d --no-deps --force-recreate caddy || true
  die "Не удалось создать страховочный backup; restore не начат."
fi
SAFETY_BACKUP="$(printf '%s\n' "$safety_output" | tail -n 1)"
[[ -s "$SAFETY_BACKUP" ]] || die "Страховочный backup пуст или отсутствует."
log "Страховочный backup: $SAFETY_BACKUP"

recover_current() {
  log "Пытаюсь автоматически вернуть БД из страховочного backup."
  compose up -d db || true
  if wait_healthy db 150 && restore_database_from_dump_at "$ACTIVE" "$SAFETY_BACKUP"; then
    if [[ "$APP_WAS_RUNNING" -eq 1 ]]; then
      compose up -d --no-deps app || true
      wait_healthy app 180 || true
    fi
    if [[ "$CADDY_WAS_RUNNING" -eq 1 ]]; then
      compose up -d --no-deps --force-recreate caddy || return 1
      wait_local_https_health "$(env_get DOMAIN)" 180 3 || return 1
    fi
    log "Страховочная БД восстановлена и runtime стал ready."
    return 0
  fi
  log "КРИТИЧЕСКАЯ ОШИБКА: авто-восстановление не удалось. Не удаляй $SAFETY_BACKUP."
  return 1
}

log "Восстанавливаю выбранный backup через отдельную временную БД и безопасную смену database names."
if ! restore_database_from_dump_at "$ACTIVE" "$BACKUP"; then
  recover_current || true
  die "Restore выбранного backup завершился ошибкой."
fi

compose up -d --no-deps app
if ! wait_healthy app 240; then
  compose logs --tail=300 app || true
  compose stop app >/dev/null 2>&1 || true
  recover_current || true
  die "Приложение после restore не стало healthy; возвращена страховочная БД, если это было возможно."
fi
compose up -d --no-deps --force-recreate caddy
DOMAIN="$(env_get DOMAIN)"
log "Жду готовность HTTPS после restore; проверка выполняется с retry до таймаута, а не одиночным curl."
if ! wait_local_https_health "$DOMAIN" 180 3; then
  compose logs --tail=200 caddy || true
  compose stop caddy app >/dev/null 2>&1 || true
  recover_current || true
  die "HTTPS /healthz после restore не стал готов в течение 180 секунд; выполнена попытка вернуть страховочную БД."
fi
# Restore is version-agnostic. In particular, a deliberate recovery to the old v9
# release must not be judged by v10-only forwarded-redirect/probe requirements.
# Version-specific security smoke tests belong to deploy/production-audit.
log "Restore PASS: $BACKUP"
