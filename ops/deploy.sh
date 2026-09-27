#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

need_cmd docker
need_cmd curl
need_cmd rsync
need_cmd sha256sum
need_cmd flock
LEGACY_ADMIN_UPGRADE=0
ADMIN_TRANSITION_STATE=/etc/gomaf/v10-v9-admin-username
if [[ -e "$ADMIN_TRANSITION_STATE" || -L "$ADMIN_TRANSITION_STATE" ]]; then
  [[ -f "$ADMIN_TRANSITION_STATE" && ! -L "$ADMIN_TRANSITION_STATE" && -r "$ADMIN_TRANSITION_STATE" ]] \
    || die "Небезопасный ADMIN transition state: $ADMIN_TRANSITION_STATE"
  [[ "$(env_get APP_ADMIN_USERNAME)" != "admin" ]] \
    || die "ADMIN transition state существует, но APP_ADMIN_USERNAME уже admin. Это признак незавершённого предыдущего перехода; автоматический deploy остановлен fail-closed."
  LEGACY_ADMIN_UPGRADE=1
fi
validate_prod_config "$LEGACY_ADMIN_UPGRADE"
runtime_prepare
if [[ "$LEGACY_ADMIN_UPGRADE" -eq 1 ]]; then
  need_cmd sudo
  [[ -x /usr/local/sbin/gomaf-v10-admin-switch ]]     || die "Не установлен gomaf-v10-admin-switch. Сначала выполни sudo bash ops/upgrade-server-v10.sh."
  sudo -n /usr/local/sbin/gomaf-v10-admin-switch v9 >/dev/null     || die "Не удалось подтвердить возможность безопасно восстановить v9 ADMIN config."
fi

for required_dir in /srv/gomaf-logs/app /srv/gomaf-logs/caddy /srv/gomaf-log-exports; do
  [[ -d "$required_dir" ]] || die "Не найден $required_dir. Выполни fresh setup или sudo bash ops/install-v10-host-support.sh (для v9->v10 сначала upgrade-server-v10.sh)."
done

exec 9>"$RUNTIME_DIR/deploy.lock"
flock -n 9 || die "Уже выполняется другой deploy."

DOMAIN="$(env_get DOMAIN)"
OLD_RELEASE="$(active_release_dir)"
OLD_RELEASE_IS_ACTIVE=0
[[ -r "$ACTIVE_RELEASE_FILE" && "$OLD_RELEASE" != "$PROJECT_ROOT" ]] && OLD_RELEASE_IS_ACTIVE=1
PREVIOUS_IMAGE="$(grep -m1 '^APP_IMAGE=' "$RUNTIME_ENV" 2>/dev/null | cut -d= -f2- || true)"
PREVIOUS_SOURCE_HASH="$(cat "$RUNTIME_DIR/current_source_hash" 2>/dev/null || true)"
if [[ -z "$PREVIOUS_SOURCE_HASH" && -n "$PREVIOUS_IMAGE" && "$PREVIOUS_IMAGE" != "gomaf/app:current" ]]; then
  PREVIOUS_SOURCE_HASH="$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$PREVIOUS_IMAGE" 2>/dev/null || true)"
fi
if [[ "$OLD_RELEASE_IS_ACTIVE" -eq 1 ]]; then
  [[ -n "$PREVIOUS_IMAGE" && "$PREVIOUS_IMAGE" != "gomaf/app:current" ]] \
    || die "Активный immutable release не имеет подтверждённого current image; безопасный rollback невозможен."
  [[ -n "$PREVIOUS_SOURCE_HASH" ]] \
    || die "Активный immutable release не имеет source-hash metadata; безопасный rollback невозможен."
  old_image_hash="$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$PREVIOUS_IMAGE" 2>/dev/null || true)"
  [[ "$old_image_hash" == "$PREVIOUS_SOURCE_HASH" ]] \
    || die "Source hash текущего production image не совпадает с runtime metadata; deploy остановлен."
fi
DB_VOLUME_EXISTS=0
if docker volume inspect gomaf_postgres_data >/dev/null 2>&1; then DB_VOLUME_EXISTS=1; fi
OLD_DB_RUNNING=0
OLD_APP_RUNNING=0
OLD_CADDY_RUNNING=0
if compose_at "$OLD_RELEASE" ps --status running --services 2>/dev/null | grep -qx db; then OLD_DB_RUNNING=1; fi
if compose_at "$OLD_RELEASE" ps --status running --services 2>/dev/null | grep -qx app; then OLD_APP_RUNNING=1; fi
if compose_at "$OLD_RELEASE" ps --status running --services 2>/dev/null | grep -qx caddy; then OLD_CADDY_RUNNING=1; fi

STAGING="$RELEASES_DIR/.staging-$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$STAGING"
release_committed=0
MAINTENANCE_STARTED=0
DB_MAY_HAVE_CHANGED=0
ROLE_SPLIT_STARTED=0
PUBLIC_CANDIDATE_STARTED=0
RECOVERY_ATTEMPTED=0
cleanup() {
  local rc=$?
  if [[ "$release_committed" -eq 0 && "$RECOVERY_ATTEMPTED" -eq 0 && "$MAINTENANCE_STARTED" -eq 1 ]]; then
    RECOVERY_ATTEMPTED=1
    if [[ "$DB_MAY_HAVE_CHANGED" -eq 1 ]]; then
      if [[ "$PUBLIC_CANDIDATE_STARTED" -eq 1 ]]; then
        log "Неожиданный EXIT после публичного запуска candidate; выполняю post-public recovery."
        rollback_after_public || log "КРИТИЧЕСКАЯ ОШИБКА: автоматический post-public recovery не завершён."
      else
        log "Неожиданный EXIT после возможного изменения БД; выполняю pre-public recovery."
        rollback_pre_public || log "КРИТИЧЕСКАЯ ОШИБКА: автоматический pre-public recovery не завершён."
      fi
    else
      log "Неожиданный EXIT после остановки старого runtime до изменения БД; возвращаю v9 runtime."
      restore_v9_admin_config || true
      restart_old_runtime || log "КРИТИЧЕСКАЯ ОШИБКА: старый runtime не удалось автоматически вернуть."
    fi
  fi
  if [[ "$release_committed" -eq 0 ]]; then
    # Named release snapshots are intentionally read-only. A failed candidate
    # is thawed only immediately before deletion.
    if [[ -d "$STAGING" ]]; then
      chmod -R u+w -- "$STAGING" 2>/dev/null || true
      rm -rf -- "$STAGING" 2>/dev/null || true
    fi
    rm -f "$RUNTIME_DIR/candidate.env" 2>/dev/null || true
    if [[ -n "${NEW_IMAGE:-}" ]]; then
      docker image rm "$NEW_IMAGE" >/dev/null 2>&1 || true
    fi
    if [[ -n "${DEPLOY_TEST_IMAGE:-}" ]]; then
      docker image rm "$DEPLOY_TEST_IMAGE" >/dev/null 2>&1 || true
    fi
  fi
  return "$rc"
}
trap cleanup EXIT

log "Создаю неизменяемый release snapshot текущих исходников."
RSYNC_FILTERS=(
  --exclude '.deploy/'
  --exclude '.audit/'
  --exclude 'backend/target/'
  --exclude '.git/'
  --exclude '*.zip'
  --exclude 'production.env'
  --exclude '.env'
  --exclude '.env.*'
)
SNAPSHOT_STABLE=0
for attempt in 1 2 3; do
  rsync -a --delete --omit-dir-times "${RSYNC_FILTERS[@]}" "$PROJECT_ROOT/" "$STAGING/"
  # A checksum dry-run verifies that no source file changed while the snapshot
  # was being copied. If an editor is saving files concurrently, retry without
  # touching the running production stack.
  changes="$(rsync -a --delete --omit-dir-times --checksum --dry-run --itemize-changes     "${RSYNC_FILTERS[@]}" "$PROJECT_ROOT/" "$STAGING/" || true)"
  if [[ -z "$changes" ]]; then
    SNAPSHOT_STABLE=1
    break
  fi
  log "Исходники изменились во время snapshot (попытка $attempt/3); повторяю."
  sleep 1
done
[[ "$SNAPSHOT_STABLE" -eq 1 ]] || die "Исходники продолжают изменяться. Заверши сохранение файлов и повтори deploy."

SOURCE_HASH="$(bash "$STAGING/ops/source-hash.sh" "$STAGING")"
[[ "$SOURCE_HASH" =~ ^[0-9a-f]{12}$ ]] || die "Invalid release source hash: $SOURCE_HASH"
APPROVED_IMAGE="$(printenv GOMAF_APPROVED_IMAGE 2>/dev/null || true)"
APPROVED_IMAGE_ID="$(printenv GOMAF_APPROVED_IMAGE_ID 2>/dev/null || true)"
APPROVED_SOURCE_HASH="$(printenv GOMAF_APPROVED_SOURCE_HASH 2>/dev/null || true)"
[[ "$APPROVED_SOURCE_HASH" == "$SOURCE_HASH" ]] || die "Exact PREDEPLOY source hash missing or mismatched."
[[ "$APPROVED_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || die "Exact PREDEPLOY image id missing or invalid."
[[ "$APPROVED_IMAGE" == gomaf/wp11-023-approved:$SOURCE_HASH-* ]] || die "Exact PREDEPLOY image tag missing or unsafe."
actual_approved_id="$(docker image inspect -f '{{.Id}}' "$APPROVED_IMAGE" 2>/dev/null || true)"
[[ "$actual_approved_id" == "$APPROVED_IMAGE_ID" ]] || die "Exact PREDEPLOY image missing or image id changed."
[[ "$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$APPROVED_IMAGE")" == "$SOURCE_HASH" ]] || die "Exact PREDEPLOY image source label mismatch."
[[ "$(docker image inspect -f '{{index .Config.Labels "com.gomaf.application"}}' "$APPROVED_IMAGE")" == classic-mafia ]] || die "Exact PREDEPLOY image application label mismatch."
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RELEASE_ID="${STAMP}-${SOURCE_HASH}"
RELEASE="$RELEASES_DIR/$RELEASE_ID"
mv "$STAGING" "$RELEASE"
STAGING="$RELEASE"
# A release snapshot is read-only after it is named. This prevents accidental
# edits of active Compose/Caddy/source files after their audit. The parent
# releases directory remains writable so old snapshots can still be removed.
chmod -R a-w "$RELEASE"
NEW_IMAGE="gomaf/app:${RELEASE_ID}"
DEPLOY_TEST_IMAGE="gomaf/deploy-backend-test:${RELEASE_ID}"


log "Запускаю source/security audit release snapshot до сборки."
bash "$RELEASE/ops/source-audit.sh"

log "Проверяю production Compose-конфигурацию из release snapshot."
printf 'APP_IMAGE=%s\n' "$NEW_IMAGE" > "$RUNTIME_DIR/candidate.env"
chmod 600 "$RUNTIME_DIR/candidate.env"
APP_IMAGE="$NEW_IMAGE" docker compose \
  --project-name gomaf \
  --project-directory "$RELEASE" \
  --env-file "$ENV_FILE" \
  --env-file "$RUNTIME_DIR/candidate.env" \
  -f "$RELEASE/compose.prod.yaml" config --quiet

IMAGE_LOCK="$RELEASE/.wp11-023-security-tools.env"
[[ -r "$IMAGE_LOCK" ]] || die "Signed image lock missing: $IMAGE_LOCK"
# shellcheck disable=SC1090
source "$IMAGE_LOCK"
for v in NODE_IMAGE MAVEN_IMAGE RUNTIME_BASE_IMAGE POSTGRES_IMAGE CADDY_IMAGE; do
  ref="${!v:-}"
  [[ "$ref" =~ @sha256:[0-9a-f]{64}$ ]] || die "$v is not digest-pinned"
done
log "Получаю только immutable digest-pinned production images."
docker pull "$POSTGRES_IMAGE" >/dev/null
docker pull "$CADDY_IMAGE" >/dev/null
docker pull "$RUNTIME_BASE_IMAGE" >/dev/null
docker pull "$MAVEN_IMAGE" >/dev/null
docker pull "$NODE_IMAGE" >/dev/null

log "Проверяю Caddyfile digest-pinned Caddy image."
[[ -r /etc/gomaf-owner-mcp/caddy.env ]] \
  || die "Не найден /etc/gomaf-owner-mcp/caddy.env для MCP Caddy validation."
[[ -r /etc/gomaf-owner-mcp/mtls/openai-connectors-mtls-ca.pem ]] \
  || die "Не найден OpenAI Connectors mTLS CA для Caddy validation."

"$RELEASE/ops/root/gomaf-safe-test-run" "gomaf-deploy-caddy-validate-$SOURCE_HASH" 128 0.20 --rm   --env-file /etc/gomaf-owner-mcp/caddy.env   -e DOMAIN="$DOMAIN"   -v "$RELEASE/caddy/Caddyfile:/etc/caddy/Caddyfile:ro"   -v /etc/gomaf-owner-mcp/mtls:/etc/caddy/openai-mtls:ro   "$CADDY_IMAGE"   caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null

if [[ "$DB_VOLUME_EXISTS" -eq 1 ]]; then
  if [[ "$OLD_DB_RUNNING" -eq 1 ]]; then
    video_bad="$(db_query_at "$OLD_RELEASE" "SELECT count(*) FROM game_videos WHERE lower(url) NOT LIKE 'https://%';")" \
      || die "Не удалось выполнить read-only HTTPS video compatibility preflight."
    [[ "$video_bad" == 0 ]] \
      || die "WP11-023 BLOCKED: production содержит $video_bad HTTP/non-HTTPS video URL; production не изменён."
  else
    log "Production DB offline: HTTPS video compatibility будет fail-closed проверена обязательным migration rehearsal на verified frozen dump; production DB не запускается."
  fi
fi

log "Собираю backend test image для обязательного PostgreSQL concurrency rehearsal."
"$RELEASE/ops/root/gomaf-safe-build" "gomaf-deploy-backend-build-$RELEASE_ID" 1024 0.75 --pull --target backend-build -t "$DEPLOY_TEST_IMAGE" "$RELEASE"

log "Использую exact PREDEPLOY-tested production image $APPROVED_IMAGE_ID; повторная production-сборка запрещена."
docker image tag "$APPROVED_IMAGE_ID" "$NEW_IMAGE"
[[ "$(docker image inspect -f '{{.Id}}' "$NEW_IMAGE")" == "$APPROVED_IMAGE_ID" ]] || die "Production tag does not point to exact PREDEPLOY image id."
[[ "$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$NEW_IMAGE")" == "$SOURCE_HASH" ]] || die "Production tag source label mismatch."
[[ "$(docker image inspect -f '{{index .Config.Labels "com.gomaf.application"}}' "$NEW_IMAGE")" == classic-mafia ]] || die "Production tag application label mismatch."

if [[ "$DB_VOLUME_EXISTS" -eq 1 ]]; then
  log "Репетирую migration на изолированном clone production snapshot; состояние production runtime не изменяется."
  GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/migration-rehearsal.sh" "$NEW_IMAGE" "$RELEASE" "" "$DEPLOY_TEST_IMAGE"
  docker image rm "$DEPLOY_TEST_IMAGE" >/dev/null 2>&1 || true
fi

# From this point a failed update must restore the previous runtime.  The public
# proxy is stopped before the database backup so no web request can write data
# between the backup and a pre-public rollback.
PREDEPLOY_BACKUP=""
ADMIN_CONFIG_SWITCHED=0

restore_v9_admin_config() {
  if [[ "$LEGACY_ADMIN_UPGRADE" -eq 1 && "$ADMIN_CONFIG_SWITCHED" -eq 1 ]]; then
    sudo -n /usr/local/sbin/gomaf-v10-admin-switch v9 >/dev/null       || { log "КРИТИЧЕСКАЯ ОШИБКА: не удалось вернуть v9 APP_ADMIN_USERNAME."; return 1; }
    ADMIN_CONFIG_SWITCHED=0
  fi
}

restart_old_runtime() {
  if [[ "$OLD_RELEASE_IS_ACTIVE" -ne 1 || -z "$PREVIOUS_IMAGE" || "$PREVIOUS_IMAGE" == "gomaf/app:current" ]]; then
    return 0
  fi
  printf 'APP_IMAGE=%s\n' "$PREVIOUS_IMAGE" > "$RUNTIME_ENV"
  chmod 600 "$RUNTIME_ENV"
  if [[ "$OLD_DB_RUNNING" -eq 1 || "$OLD_APP_RUNNING" -eq 1 || "$OLD_CADDY_RUNNING" -eq 1 || "$ROLE_SPLIT_STARTED" -eq 1 ]]; then
    compose_at "$OLD_RELEASE" up -d db || return 1
    wait_healthy_at "$OLD_RELEASE" db 150 || return 1
    if [[ "$ROLE_SPLIT_STARTED" -eq 1 ]]; then
      # Never execute the immutable WP11-022 db-prepare here: it would restore
      # runtime schema ownership/DDL. Re-establish the new role model with the
      # trusted candidate scripts, then run old code with Flyway disabled.
      compose_at "$RELEASE" run --rm --no-deps db-role-bootstrap || return 1
      compose_at "$RELEASE" run --rm --no-deps db-finalize || return 1
      enable_forward_compat_mode "$OLD_RELEASE"
    fi
    if [[ "$OLD_APP_RUNNING" -eq 1 ]]; then
      compose_at "$OLD_RELEASE" up -d --no-deps app || return 1
      wait_healthy_at "$OLD_RELEASE" app 180 || return 1
    fi
    if [[ "$OLD_CADDY_RUNNING" -eq 1 ]]; then
      compose_at "$OLD_RELEASE" up -d --no-deps --force-recreate caddy || return 1
    fi
  fi
  if [[ "$OLD_DB_RUNNING" -ne 1 ]]; then
    compose_at "$OLD_RELEASE" stop db >/dev/null 2>&1 || return 1
  fi
}

rollback_pre_public() {
  RECOVERY_ATTEMPTED=1
  log "Выполняю аварийный rollback до открытия новой версии в Internet."
  compose_at "$RELEASE" stop caddy app >/dev/null 2>&1 || true
  if [[ "$DB_MAY_HAVE_CHANGED" -eq 1 && -n "$PREDEPLOY_BACKUP" && -s "$PREDEPLOY_BACKUP" ]]; then
    log "Возвращаю PostgreSQL к pre-deploy backup: $PREDEPLOY_BACKUP"
    compose_at "$RELEASE" up -d db || true
    if wait_healthy_at "$RELEASE" db 150; then
      if ! restore_database_from_dump_at "$OLD_RELEASE" "$PREDEPLOY_BACKUP"; then
        log "КРИТИЧЕСКАЯ ОШИБКА: автоматическое восстановление БД не удалось. Backup сохранён: $PREDEPLOY_BACKUP"
        return 1
      fi
    else
      log "КРИТИЧЕСКАЯ ОШИБКА: PostgreSQL не healthy, восстановление БД невозможно."
      return 1
    fi
  fi
  if [[ "$OLD_RELEASE_IS_ACTIVE" -eq 1 ]]; then
    restore_v9_admin_config || return 1
    restart_old_runtime
  else
    printf 'APP_IMAGE=gomaf/app:current\n' > "$RUNTIME_ENV"
    chmod 600 "$RUNTIME_ENV"
  fi
}

rollback_after_public() {
  RECOVERY_ATTEMPTED=1
  # Once the candidate proxy has accepted public traffic, restoring an old DB dump
  # could erase writes made by real users in that short window. The v10 schema keeps
  # the v9 entity model compatible, but the immutable v9 release does not contain V3
  # and would reject Flyway history validation. Start only that previous release with
  # Flyway disabled; Hibernate schema validation remains enabled.
  log "Останавливаю публичную candidate-версию и возвращаю предыдущий release без отката данных."
  compose_at "$RELEASE" stop caddy app >/dev/null 2>&1 || true
  if [[ "$OLD_RELEASE_IS_ACTIVE" -eq 1 ]]; then
    enable_forward_compat_mode "$OLD_RELEASE"
    if restart_old_runtime; then
      # Public v10 traffic means the database stays on V3. If this was a
      # legacy-admin upgrade, the canonical admin identity is now permanent
      # even while the previous v9 code is serving in forward-compatible mode.
      # Finalize the transition state so the next deploy does not fail on a
      # stale legacy marker.
      if [[ "$LEGACY_ADMIN_UPGRADE" -eq 1 ]]; then
        if sudo -n /usr/local/sbin/gomaf-v10-admin-switch finalize >/dev/null; then
          ADMIN_CONFIG_SWITCHED=0
        else
          log "КРИТИЧЕСКАЯ ОШИБКА: v9 forward-compatible rollback работает, но ADMIN transition state не удалось финализировать."
          return 1
        fi
      fi
      return 0
    fi
    log "Предыдущий release не запустился даже в forward-compatible режиме; возвращаю candidate."
    disable_forward_compat_mode
    printf 'APP_IMAGE=%s\n' "$NEW_IMAGE" > "$RUNTIME_ENV"
    chmod 600 "$RUNTIME_ENV"
    compose_at "$RELEASE" up -d db || true
    wait_healthy_at "$RELEASE" db 150 || true
    compose_at "$RELEASE" run --rm --no-deps db-role-bootstrap || true
    compose_at "$RELEASE" run --rm --no-deps db-finalize || true
    compose_at "$RELEASE" up -d --no-deps app || true
    wait_healthy_at "$RELEASE" app 180 || true
    compose_at "$RELEASE" up -d --no-deps --force-recreate caddy || true
    return 1
  fi
  return 1
}

if [[ "$DB_VOLUME_EXISTS" -eq 1 ]]; then
  log "Обнаружен существующий PostgreSQL volume. Включаю короткое окно обслуживания перед backup."
  MAINTENANCE_STARTED=1
  compose_at "$OLD_RELEASE" stop caddy app >/dev/null 2>&1 || true
  if ! compose_at "$OLD_RELEASE" up -d db || ! wait_healthy_at "$OLD_RELEASE" db 150; then
    restart_old_runtime || true
    die "Существующая PostgreSQL БД не стала healthy; deploy остановлен до изменения данных."
  fi
  if [[ "$LEGACY_ADMIN_UPGRADE" -eq 1 ]]; then
    log "Переключаю только host APP_ADMIN_USERNAME на admin после остановки v9 traffic."
    if ! sudo -n /usr/local/sbin/gomaf-v10-admin-switch v10 >/dev/null; then
      sudo -n /usr/local/sbin/gomaf-v10-admin-switch v9 >/dev/null 2>&1 || true
      restart_old_runtime || true
      die "Не удалось безопасно переключить ADMIN config на v10."
    fi
    ADMIN_CONFIG_SWITCHED=1
    validate_prod_config
  fi
  backup_output=""
  if ! backup_output="$(GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/backup.sh" predeploy)"; then
    log "Pre-deploy backup не удался; возвращаю предыдущую версию."
    restore_v9_admin_config || true
    restart_old_runtime || true
    die "Deploy остановлен до изменения БД."
  fi
  PREDEPLOY_BACKUP="$(printf '%s\n' "$backup_output" | tail -n 1)"
  [[ -s "$PREDEPLOY_BACKUP" ]] || { restore_v9_admin_config || true; restart_old_runtime || true; die "Backup создан некорректно: $PREDEPLOY_BACKUP"; }
  log "Pre-deploy backup: $PREDEPLOY_BACKUP"
else
  log "Существующей production БД нет, pre-deploy backup не требуется."
fi

printf 'APP_IMAGE=%s\n' "$NEW_IMAGE" > "$RUNTIME_ENV"
chmod 600 "$RUNTIME_ENV"

log "Запускаю PostgreSQL."
if ! compose_at "$RELEASE" up -d db || ! wait_healthy_at "$RELEASE" db 150; then
  compose_at "$RELEASE" logs --tail=200 db || true
  rollback_pre_public || true
  die "PostgreSQL не стал healthy."
fi

log "Разделяю migration/runtime DB roles."
ROLE_SPLIT_STARTED=1
if ! compose_at "$RELEASE" run --rm --no-deps db-role-bootstrap; then
  rollback_pre_public || true
  die "db-role-bootstrap завершился ошибкой."
fi

log "Применяю Flyway только отдельной migration role."
DB_MAY_HAVE_CHANGED=1
if ! compose_at "$RELEASE" run --rm --no-deps db-migrate; then
  rollback_pre_public || true
  die "db-migrate завершился ошибкой; предыдущая версия восстановлена."
fi

log "Фиксирую DML-only privileges runtime role."
if ! compose_at "$RELEASE" run --rm --no-deps db-finalize; then
  rollback_pre_public || true
  die "db-finalize завершился ошибкой; предыдущая версия восстановлена."
fi

log "Запускаю candidate runtime с Flyway disabled."
if ! compose_at "$RELEASE" up -d --no-deps app; then
  rollback_pre_public || true
  die "Не удалось создать app container."
fi
if ! wait_healthy_at "$RELEASE" app 240; then
  compose_at "$RELEASE" logs --tail=300 app || true
  rollback_pre_public || true
  die "Candidate-приложение не прошло healthcheck; предыдущая версия восстановлена."
fi

log "Проверяю Flyway V10, persistent login, MFA retirement и security schema/privilege invariants."
schema_sql="SELECT (SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1), to_regclass('public.security_rate_limit_buckets') IS NOT NULL, to_regclass('public.admin_mfa_credentials') IS NOT NULL, to_regclass('public.admin_mfa_recovery_codes') IS NOT NULL, to_regclass('public.security_sessions') IS NOT NULL, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='admin_mfa_credentials' AND column_name='last_totp_counter'), NOT has_schema_privilege('$(env_get APP_DB_USER)','public','CREATE'), NOT has_table_privilege('$(env_get APP_DB_USER)','public.flyway_schema_history','SELECT'), (SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m') AND r.rolname='$(env_get APP_DB_USER)'), (SELECT count(*)=0 FROM admin_mfa_credentials), (SELECT count(*)=0 FROM admin_mfa_recovery_codes), (SELECT count(*)=0 FROM security_rate_limit_buckets WHERE bucket_key LIKE 'mfa-ip:%' OR bucket_key LIKE 'mfa-pair:%'), to_regclass('public.persistent_login_tokens') IS NOT NULL, (has_table_privilege('$(env_get APP_DB_USER)','public.persistent_login_tokens','SELECT') AND has_table_privilege('$(env_get APP_DB_USER)','public.persistent_login_tokens','INSERT') AND has_table_privilege('$(env_get APP_DB_USER)','public.persistent_login_tokens','UPDATE') AND has_table_privilege('$(env_get APP_DB_USER)','public.persistent_login_tokens','DELETE'));"
if ! schema_state="$(db_query_at "$RELEASE" "$schema_sql")"; then
  rollback_pre_public || true
  die "SQL-проверка Flyway V10/persistent-login/security privileges/MFA retirement завершилась ошибкой."
fi
expected_schema_state="10$(printf '|t%.0s' {1..13})"
[[ "$schema_state" == "$expected_schema_state" ]] || { rollback_pre_public || true; die "Flyway V10/persistent-login/security/MFA retirement check failed: $schema_state"; }
compat_sql="SELECT (SELECT count(*) FROM users WHERE NOT ((account_role='ADMIN' AND role='ADMIN') OR (account_role IN ('PLAYER','MODERATOR') AND role='PLAYER'))), (SELECT count(*) FROM moderator_scope_defaults WHERE scope NOT IN ('NEWS_MANAGE','RULES_MANAGE','GAMES_MANAGE','SCHEDULE_MANAGE','PLAYER_RATING_MANAGE','PLAYER_USERNAME_MANAGE','PLAYER_AVATAR_RESET','PLAYER_PASSWORD_RESET','PLAYER_DELETE')), (SELECT count(*) FROM moderator_scope_overrides WHERE scope NOT IN ('NEWS_MANAGE','RULES_MANAGE','GAMES_MANAGE','SCHEDULE_MANAGE','PLAYER_RATING_MANAGE','PLAYER_USERNAME_MANAGE','PLAYER_AVATAR_RESET','PLAYER_PASSWORD_RESET','PLAYER_DELETE'));"
if ! compat_bad="$(db_query_at "$RELEASE" "$compat_sql")"; then
  rollback_pre_public || true
  die "SQL-проверка rollback compatibility завершилась ошибкой."
fi
[[ "$compat_bad" == "0|0|0" ]] || { rollback_pre_public || true; die "Нарушена rollback-совместимость users или moderator scopes: $compat_bad"; }
index_state="$(db_query_at "$RELEASE" "SELECT to_regclass('public.idx_users_public_rating_username') IS NOT NULL, to_regclass('public.idx_games_public_date_id') IS NOT NULL, to_regclass('public.idx_games_tournament_date_id') IS NOT NULL, to_regclass('public.idx_news_created_at_desc') IS NOT NULL, to_regclass('public.idx_tournaments_created_at_desc') IS NOT NULL;")"
[[ "$index_state" == "t|t|t|t|t" ]] || { rollback_pre_public || true; die "V8 query-plan indexes отсутствуют или неполны: $index_state"; }

log "Выполняю внутренний smoke-test candidate до открытия 80/443."
for path in / /news /rules /players /schedule /games /tournaments /login /register /healthz; do
  if ! compose_at "$RELEASE" exec -T app curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:8787$path" >/dev/null; then
    rollback_pre_public || true
    die "Внутренний smoke-test не прошёл для $path."
  fi
done
if ! internal_spa_html="$(compose_at "$RELEASE" exec -T app curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8787/news)"; then
  rollback_pre_public || true
  die "Не удалось получить embedded SPA index."
fi
internal_asset="$(printf '%s\n' "$internal_spa_html" | grep -oE '/assets/[^"'"'"' ]+[.]js' | head -n1 || true)"
[[ -n "$internal_asset" ]] || { rollback_pre_public || true; die "Embedded SPA index не содержит fingerprinted JS asset."; }
if ! compose_at "$RELEASE" exec -T app curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:8787$internal_asset" >/dev/null; then
  rollback_pre_public || true
  die "Embedded SPA JS asset недоступен: $internal_asset"
fi
if ! api_session_headers="$(compose_at "$RELEASE" exec -T app curl --silent --show-error -D - -o /tmp/gomaf-api-session.json --max-time 10 http://127.0.0.1:8787/api/v1/session)"; then
  rollback_pre_public || true
  die "Internal API session smoke request failed."
fi
printf '%s\n' "$api_session_headers" | grep -qi '^Content-Type: application/json' || { rollback_pre_public || true; die "Internal API session is not JSON."; }
if ! internal_headers="$(compose_at "$RELEASE" exec -T app curl --silent --show-error -D - -o /dev/null --max-time 10 http://127.0.0.1:8787/login)"; then
  rollback_pre_public || true
  die "Не удалось получить internal security headers."
fi
printf '%s\n' "$internal_headers" | grep -qi '^Content-Security-Policy:' || { rollback_pre_public || true; die "Во внутреннем ответе отсутствует CSP."; }
printf '%s\n' "$internal_headers" | grep -qi "Content-Security-Policy:.*script-src 'self'" || { rollback_pre_public || true; die "CSP не разрешает только self-hosted scripts."; }
printf '%s\n' "$internal_headers" | grep -Eqi 'unsafe-inline|unsafe-eval' && { rollback_pre_public || true; die "CSP содержит unsafe-inline/unsafe-eval."; }
printf '%s\n' "$internal_headers" | grep -qi '^X-Frame-Options: DENY' || { rollback_pre_public || true; die "Во внутреннем ответе отсутствует X-Frame-Options: DENY."; }
printf '%s\n' "$internal_headers" | grep -qi '^X-Content-Type-Options: nosniff' || { rollback_pre_public || true; die "Во внутреннем ответе отсутствует nosniff."; }
if ! csrf_code="$(compose_at "$RELEASE" exec -T app curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
  -H 'Content-Type: application/json' \
  --data '{"username":"csrf_probe","password":"strong-password","passwordConfirm":"strong-password","captcha":"ABCDE"}' \
  http://127.0.0.1:8787/api/v1/auth/register)"; then
  rollback_pre_public || true
  die "CSRF smoke request не выполнился."
fi
[[ "$csrf_code" == 403 ]] || { rollback_pre_public || true; die "CSRF smoke-test ожидал 403, получил $csrf_code."; }

if ! forwarded_headers="$(compose_at "$RELEASE" exec -T app curl --silent --show-error -D - -o /dev/null --max-time 10 \
  -H "Host: $DOMAIN" -H "X-Forwarded-Proto: https" -H "X-Forwarded-Host: $DOMAIN" \
  http://127.0.0.1:8787/profile)"; then
  rollback_pre_public || true
  die "Forwarded HTTPS smoke request не выполнился."
fi
forwarded_location="$(printf '%s\n' "$forwarded_headers" | awk 'BEGIN{IGNORECASE=1} /^Location:/ {sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit}')"
[[ "$forwarded_location" == https://$DOMAIN/* ]] || { rollback_pre_public || true; die "Forwarded HTTPS redirect некорректен: ${forwarded_location:-missing}"; }

# Force recreation is intentional: Caddy re-resolves the replaced app service.
log "Открываю candidate через Caddy и Automatic HTTPS."
if ! compose_at "$RELEASE" up -d --no-deps --force-recreate caddy; then
  rollback_pre_public || true
  die "Не удалось запустить Caddy."
fi
PUBLIC_CANDIDATE_STARTED=1

log "Жду HTTPS https://$DOMAIN/healthz с retry до таймаута."
if ! wait_local_https_health "$DOMAIN" 180 4; then
  compose_at "$RELEASE" logs --tail=250 caddy || true
  rollback_after_public || true
  die "Caddy/app не стали ready по HTTPS /healthz за 180 секунд. Проверь DNS A/AAAA и доступность 80/443."
fi

if ! "$RELEASE/ops/smoke-test.sh"; then
  rollback_after_public || true
  die "Production HTTPS smoke-test новой версии не прошёл."
fi

# Commit runtime pointers only after build, DB migration, app health, internal smoke,
# TLS and external smoke tests are all green.
if [[ "$OLD_RELEASE_IS_ACTIVE" -eq 1 ]]; then
  printf '%s\n' "$OLD_RELEASE" > "$RUNTIME_DIR/previous_release"
else
  : > "$RUNTIME_DIR/previous_release"
fi
printf '%s\n' "$RELEASE" > "$ACTIVE_RELEASE_FILE"
printf '%s\n' "$PREVIOUS_IMAGE" > "$RUNTIME_DIR/previous_image"
printf '%s\n' "$PREVIOUS_SOURCE_HASH" > "$RUNTIME_DIR/previous_source_hash"
printf '%s\n' "$NEW_IMAGE" > "$RUNTIME_DIR/current_image"
printf '%s\n' "$SOURCE_HASH" > "$RUNTIME_DIR/current_source_hash"
printf '%s\n' "$PREDEPLOY_BACKUP" > "$RUNTIME_DIR/last_predeploy_backup"
chmod 600 "$ACTIVE_RELEASE_FILE" "$RUNTIME_DIR/previous_release" "$RUNTIME_DIR/previous_image" \
  "$RUNTIME_DIR/previous_source_hash" "$RUNTIME_DIR/current_image" "$RUNTIME_DIR/current_source_hash" \
  "$RUNTIME_DIR/last_predeploy_backup"
disable_forward_compat_mode
rm -f "$RUNTIME_DIR/candidate.env"
release_committed=1
trap - EXIT
if [[ "$LEGACY_ADMIN_UPGRADE" -eq 1 ]]; then
  if ! sudo -n /usr/local/sbin/gomaf-v10-admin-switch finalize >/dev/null; then
    log "КРИТИЧЕСКАЯ ОШИБКА: v10 уже прошла smoke tests и зафиксирована как active, но временный v9 ADMIN transition state не удалён. Production остаётся на рабочей v10; выполни production-audit после исправления helper/state."
    exit 1
  fi
  ADMIN_CONFIG_SWITCHED=0
fi
GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/cleanup-releases.sh" \
  || log "Предупреждение: cleanup старых release не завершился; production не затронут."

log "DEPLOY PASS: $NEW_IMAGE"
log "Release snapshot: $RELEASE"
log "Сайт: https://$DOMAIN"
