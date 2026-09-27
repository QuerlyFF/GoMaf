#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

need_cmd docker
need_cmd openssl
need_cmd df
need_cmd date
need_cmd stat
need_cmd sha256sum
validate_prod_config 1
runtime_prepare

IMAGE="${1:-}"
CANDIDATE_RELEASE="${2:-}"
DUMP_PATH="${3:-}"
TEST_IMAGE="${4:-}"
[[ -n "$IMAGE" ]] || die "migration-rehearsal: укажи candidate image."
[[ -n "$CANDIDATE_RELEASE" \
   && -f "$CANDIDATE_RELEASE/docker/db-role-bootstrap.sh" \
   && -f "$CANDIDATE_RELEASE/docker/db-finalize-privileges.sh" ]] \
  || die "migration-rehearsal: не найден candidate release snapshot WP11-023."
docker image inspect "$IMAGE" >/dev/null 2>&1 || die "migration-rehearsal: image отсутствует: $IMAGE"
[[ -n "$TEST_IMAGE" ]] || die "migration-rehearsal: backend test image обязателен."
docker image inspect "$TEST_IMAGE" >/dev/null 2>&1 || die "migration-rehearsal: backend test image отсутствует: $TEST_IMAGE"
IMAGE_LOCK="$CANDIDATE_RELEASE/.wp11-023-security-tools.env"
[[ -r "$IMAGE_LOCK" ]] || die "migration-rehearsal: verified immutable image lock missing"
# shellcheck disable=SC1090
source "$IMAGE_LOCK"
[[ "${POSTGRES_IMAGE:-}" =~ @sha256:[0-9a-f]{64}$ ]] || die "migration-rehearsal: POSTGRES_IMAGE is not digest-pinned"

TEST_CGROUP_OPT=''
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet gomaf-testing.slice 2>/dev/null; then
  TEST_CGROUP_OPT='--cgroup-parent=gomaf-testing.slice'
fi
bounded_small() {
  docker run --memory=128m --memory-reservation=96m --memory-swap=256m --cpus=0.20 --pids-limit=128 $TEST_CGROUP_OPT "$@"
}
bounded_db() {
  docker run --memory=512m --memory-reservation=384m --memory-swap=768m --cpus=0.50 --pids-limit=256 $TEST_CGROUP_OPT "$@"
}
bounded_app() {
  docker run --memory=512m --memory-reservation=384m --memory-swap=768m --cpus=0.50 --pids-limit=256 $TEST_CGROUP_OPT "$@"
}
bounded_maven() {
  docker run --memory=768m --memory-reservation=576m --memory-swap=1024m --cpus=0.75 --pids-limit=512 $TEST_CGROUP_OPT "$@"
}

PREFLIGHT="$CANDIDATE_RELEASE/ops/root/gomaf-test-preflight"
[[ -x "$PREFLIGHT" ]] || die "migration-rehearsal: resource preflight helper missing"
wait_resources() {
  local label="$1" out attempt
  for attempt in $(seq 1 36); do
    if out="$("$PREFLIGHT" --resources-only 2>&1)"; then
      log "Migration rehearsal: resource gate PASS before $label: $out"
      return 0
    fi
    if (( attempt == 1 || attempt % 6 == 0 )); then
      log "Migration rehearsal: waiting for resource gate before $label: $out"
    fi
    sleep 5
  done
  "$PREFLIGHT" --resources-only >&2 || true
  die "migration-rehearsal: resource gate did not recover before $label"
}

DB_NAME="$(env_get POSTGRES_DB)"
PROD_ROOT=""
prepare_dump_mode() {
  [[ -f "$DUMP_PATH" && ! -L "$DUMP_PATH" && -r "$DUMP_PATH" ]] \
    || die "migration-rehearsal: logical dump недоступен/небезопасен: $DUMP_PATH"
  DUMP_DIR="$(cd "$(dirname "$DUMP_PATH")" && pwd)"
  DUMP_BASE="$(basename "$DUMP_PATH")"
  bounded_small --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    -v "$DUMP_DIR:/input:ro" "$POSTGRES_IMAGE" pg_restore -l "/input/$DUMP_BASE" >/dev/null 2>&1 \
    || die "migration-rehearsal: logical dump не читается pg_restore из pinned PostgreSQL image."
  DB_BYTES="$(stat -c '%s' "$DUMP_PATH")"
  DUMP_SHA256="$(sha256sum "$DUMP_PATH" | awk '{print $1}')"
  [[ "$DUMP_SHA256" =~ ^[0-9a-f]{64}$ ]] || die "migration-rehearsal: не удалось вычислить SHA-256 logical dump."
  log "Migration rehearsal: backup mode; production PostgreSQL не запускается и не читается; dump_sha256=$DUMP_SHA256."
}

select_verified_frozen_dump() {
  local db_id finished_at finished_epoch dump_epoch owner mode
  PROD_ROOT="$(active_release_dir)"
  if compose_at "$PROD_ROOT" ps --status running --services | grep -q .; then
    die "migration-rehearsal: frozen dump fallback разрешён только когда все production services остановлены."
  fi
  db_id="$(compose_at "$PROD_ROOT" ps -a -q db | head -n1)"
  [[ -n "$db_id" ]] || die "migration-rehearsal: не найден stopped production DB container для проверки freeze chronology."
  finished_at="$(docker inspect -f '{{.State.FinishedAt}}' "$db_id" 2>/dev/null || true)"
  finished_epoch="$(date -u -d "$finished_at" +%s 2>/dev/null || true)"
  [[ "$finished_epoch" =~ ^[0-9]+$ ]] || die "migration-rehearsal: не удалось определить время последней остановки production DB."
  DUMP_PATH="$(find /srv/gomaf-backups -maxdepth 1 -type f -name 'gomaf-preaudit-*.dump' -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | awk 'NR==1 {sub(/^[^ ]+ /, ""); print; exit}')"
  [[ -n "$DUMP_PATH" ]] || die "migration-rehearsal: production DB stopped, verified frozen preaudit dump не найден."
  dump_epoch="$(stat -c '%Y' "$DUMP_PATH")"
  (( dump_epoch >= finished_epoch )) \
    || die "migration-rehearsal: frozen dump старее последней остановки production DB; использовать его нельзя."
  owner="$(stat -c '%U:%G' "$DUMP_PATH")"; mode="$(stat -c '%a' "$DUMP_PATH")"
  [[ "$owner" == root:root && "$mode" == 600 ]] || die "migration-rehearsal: frozen dump должен быть root:root mode 600."
  log "Migration rehearsal: freeze-safe root-owned preaudit dump selected after production DB stop."
}

if [[ -n "$DUMP_PATH" ]]; then
  prepare_dump_mode
else
  PROD_ROOT="$(active_release_dir)"
  if compose_at "$PROD_ROOT" ps --status running --services | grep -qx db; then
    wait_healthy_at "$PROD_ROOT" db 30 || die "migration-rehearsal: production PostgreSQL не healthy."
    DB_BYTES="$(compose_at "$PROD_ROOT" exec -T db sh -ec \
      'PGPASSWORD="$POSTGRES_PASSWORD" psql -At -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT pg_database_size(current_database());"')"
  else
    select_verified_frozen_dump
    prepare_dump_mode
  fi
fi
[[ "$DB_BYTES" =~ ^[0-9]+$ ]] || die "migration-rehearsal: не удалось определить размер источника БД."

DOCKER_ROOT="$(docker info --format '{{.DockerRootDir}}')"
[[ -d "$DOCKER_ROOT" ]] || die "migration-rehearsal: DockerRootDir не найден: $DOCKER_ROOT"
FREE_BYTES="$(df -PB1 "$DOCKER_ROOT" | awk 'NR==2 {print $4}')"
TARGET_FREE_MB="$(env_get_default BACKUP_TARGET_FREE_MB 3072)"
TARGET_FREE_BYTES=$((TARGET_FREE_MB * 1024 * 1024))
REQUIRED_FREE_BYTES=$((TARGET_FREE_BYTES + DB_BYTES * 2))
(( FREE_BYTES >= REQUIRED_FREE_BYTES )) || die \
  "migration-rehearsal: недостаточно безопасного места для временного clone (free=$FREE_BYTES required=$REQUIRED_FREE_BYTES)."

TOKEN="$(date -u +%Y%m%dT%H%M%SZ)-$$"
NETWORK="gomaf-rehearsal-$TOKEN"
VOLUME="gomaf-rehearsal-$TOKEN"
DB_CONTAINER="gomaf-rehearsal-db-$TOKEN"
MIG_CONTAINER="gomaf-rehearsal-migrate-$TOKEN"
APP_CONTAINER="gomaf-rehearsal-app-$TOKEN"
ROLLBACK_CONTAINER="gomaf-rehearsal-rollback-$TOKEN"
CONCURRENCY_CONTAINER="gomaf-rehearsal-concurrency-$TOKEN"
ROLE_BOOTSTRAP_CONTAINER="gomaf-rehearsal-role-bootstrap-$TOKEN"
FINALIZE_CONTAINER="gomaf-rehearsal-finalize-$TOKEN"
PSQL_CONTAINER="gomaf-rehearsal-psql-$TOKEN"
ROOT_PASSWORD="$(openssl rand -hex 32)"
APP_PASSWORD="$(openssl rand -hex 32)"
MIGRATION_PASSWORD="$(openssl rand -hex 32)"
ADMIN_PASSWORD="$(openssl rand -hex 24)"
IP_SECRET="$(openssl rand -hex 32)"
LEGACY_ROLLBACK_MFA_KEY="$(openssl rand -base64 32 | tr -d '\n')"
APP_USER="rehearsal_app"
MIGRATION_USER="rehearsal_migrator"

cleanup() {
  docker rm -f "$ROLLBACK_CONTAINER" "$APP_CONTAINER" "$MIG_CONTAINER" "$CONCURRENCY_CONTAINER" "$ROLE_BOOTSTRAP_CONTAINER" "$FINALIZE_CONTAINER" "$PSQL_CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

log "Migration rehearsal: создаю изолированный PostgreSQL clone без host ports."
docker network create --internal "$NETWORK" >/dev/null
docker volume create "$VOLUME" >/dev/null
bounded_db -d \
  --name "$DB_CONTAINER" \
  --network "$NETWORK" \
  --network-alias db \
  --security-opt no-new-privileges:true \
  -e POSTGRES_DB="$DB_NAME" \
  -e POSTGRES_USER=rehearsal_root \
  -e POSTGRES_PASSWORD="$ROOT_PASSWORD" \
  -v "$VOLUME:/var/lib/postgresql/data" \
  "$POSTGRES_IMAGE" >/dev/null

DB_READY=0
for _ in $(seq 1 60); do
  if docker exec "$DB_CONTAINER" pg_isready -U rehearsal_root -d "$DB_NAME" >/dev/null 2>&1; then DB_READY=1; break; fi
  if ! docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs --tail=150 "$DB_CONTAINER" >&2 || true
    die "migration-rehearsal: временный PostgreSQL завершился."
  fi
  sleep 1
done
[[ "$DB_READY" -eq 1 ]] || { docker logs --tail=150 "$DB_CONTAINER" >&2 || true; die "migration-rehearsal: временный PostgreSQL не стал ready."; }

wait_resources "PostgreSQL concurrency gate"
log "Migration rehearsal: запускаю обязательный PostgreSQL concurrency gate на отдельной test DB."
CONCURRENCY_DB="gomaf_concurrency"
docker exec -e PGPASSWORD="$ROOT_PASSWORD" "$DB_CONTAINER"   createdb -h 127.0.0.1 -U rehearsal_root "$CONCURRENCY_DB"
if ! bounded_maven --rm --name "$CONCURRENCY_CONTAINER" --network "$NETWORK" -w /workspace/backend --entrypoint mvn "$TEST_IMAGE"   -o -B -q -Dtest=PostgresParticipationConcurrencyIntegrationTests   -Dgomaf.postgres.concurrency=true   -Dspring.datasource.url="jdbc:postgresql://db:5432/$CONCURRENCY_DB"   -Dspring.datasource.driver-class-name=org.postgresql.Driver   -Dspring.datasource.username=rehearsal_root   -Dspring.datasource.password="$ROOT_PASSWORD"   -Dspring.jpa.hibernate.ddl-auto=create-drop -Dspring.flyway.enabled=false test; then
  die "migration-rehearsal: PostgreSQL concurrency gate FAIL."
fi
docker exec -e PGPASSWORD="$ROOT_PASSWORD" "$DB_CONTAINER"   dropdb -h 127.0.0.1 -U rehearsal_root "$CONCURRENCY_DB"
log "Migration rehearsal: PostgreSQL concurrency gate PASS."
wait_resources "production snapshot copy"

if [[ -n "$DUMP_PATH" ]]; then
  log "Migration rehearsal: восстанавливаю verified logical dump в изолированный clone."
  docker exec -i -e PGPASSWORD="$ROOT_PASSWORD" "$DB_CONTAINER" sh -ec \
    'pg_restore -h 127.0.0.1 -U rehearsal_root -d "$POSTGRES_DB" --no-owner --no-acl --exit-on-error' < "$DUMP_PATH"
else
  log "Migration rehearsal: потоково копирую консистентный snapshot текущей production БД."
  compose_at "$PROD_ROOT" exec -T db sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl' \
  | docker exec -i -e PGPASSWORD="$ROOT_PASSWORD" "$DB_CONTAINER" sh -ec \
    'pg_restore -h 127.0.0.1 -U rehearsal_root -d "$POSTGRES_DB" --no-owner --no-acl --exit-on-error'
fi

query_clone() {
  local sql="$1"
  docker exec -e PGPASSWORD="$ROOT_PASSWORD" "$DB_CONTAINER" \
    psql -At -h 127.0.0.1 -U rehearsal_root -d "$DB_NAME" -c "$sql"
}

HTTP_VIDEO_COUNT="$(query_clone "SELECT count(*) FROM game_videos WHERE lower(url) NOT LIKE 'https://%';")"
[[ "$HTTP_VIDEO_COUNT" == 0 ]] || die \
  "migration-rehearsal: production snapshot содержит $HTTP_VIDEO_COUNT HTTP/non-HTTPS video URL; WP11-023 должен остаться BLOCKED до отдельной data compatibility strategy."

BASELINE_FLYWAY_VERSION="$(query_clone "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;")"
[[ "$BASELINE_FLYWAY_VERSION" =~ ^[0-9]+$ ]] || die "migration-rehearsal: некорректная исходная Flyway version: $BASELINE_FLYWAY_VERSION"

ADMIN_SECURITY_BEFORE="$(query_clone "SELECT credential_version, authorization_version FROM users WHERE account_role='ADMIN';")"
[[ "$ADMIN_SECURITY_BEFORE" =~ ^[0-9]+\|[0-9]+$ ]] || die "migration-rehearsal: ожидается ровно один ADMIN security-version state, получено: $ADMIN_SECURITY_BEFORE"
IFS='|' read -r ADMIN_CREDENTIAL_BEFORE ADMIN_AUTHORIZATION_BEFORE <<< "$ADMIN_SECURITY_BEFORE"

log "Migration rehearsal: разделяю migration/runtime DB roles на clone."
bounded_small --rm --name "$ROLE_BOOTSTRAP_CONTAINER" \
  --network "$NETWORK" --cap-drop ALL --security-opt no-new-privileges:true \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER=rehearsal_root -e POSTGRES_PASSWORD="$ROOT_PASSWORD" \
  -e APP_DB_USER="$APP_USER" -e APP_DB_PASSWORD="$APP_PASSWORD" \
  -e MIGRATION_DB_USER="$MIGRATION_USER" -e MIGRATION_DB_PASSWORD="$MIGRATION_PASSWORD" \
  -v "$CANDIDATE_RELEASE/docker/db-role-bootstrap.sh:/usr/local/bin/db-role-bootstrap.sh:ro" \
  --entrypoint /bin/sh "$POSTGRES_IMAGE" /usr/local/bin/db-role-bootstrap.sh >/dev/null

wait_resources "Flyway candidate migration"
log "Migration rehearsal: запускаю Flyway V10 только под migration role."
if ! bounded_app --name "$MIG_CONTAINER" \
  --network "$NETWORK" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=96m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e SPRING_MAIN_LAZY_INITIALIZATION=true \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://db:5432/$DB_NAME" \
  -e SPRING_DATASOURCE_USERNAME="$MIGRATION_USER" -e SPRING_DATASOURCE_PASSWORD="$MIGRATION_PASSWORD" \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=validate -e SPRING_FLYWAY_ENABLED=true \
  -e APP_MODE=migration -e APP_ENV=local -e APP_LOG_DIR=/tmp/gomaf-logs \
  -e APP_SECURITY_IP_HASH_SECRET="$IP_SECRET" \
  "$IMAGE"; then
  docker logs --tail=250 "$MIG_CONTAINER" >&2 || true
  die "migration-rehearsal: Flyway candidate container завершился ошибкой."
fi

log "Migration rehearsal: применяю post-migration least-privilege grants."
bounded_small --rm --name "$FINALIZE_CONTAINER" \
  --network "$NETWORK" --cap-drop ALL --security-opt no-new-privileges:true \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER=rehearsal_root -e POSTGRES_PASSWORD="$ROOT_PASSWORD" \
  -e APP_DB_USER="$APP_USER" -e MIGRATION_DB_USER="$MIGRATION_USER" \
  -v "$CANDIDATE_RELEASE/docker/db-finalize-privileges.sh:/usr/local/bin/db-finalize-privileges.sh:ro" \
  --entrypoint /bin/sh "$POSTGRES_IMAGE" /usr/local/bin/db-finalize-privileges.sh >/dev/null

FLYWAY_VERSION="$(query_clone "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;")"
[[ "$FLYWAY_VERSION" == 10 ]] || die "migration-rehearsal: ожидалась Flyway v10, получено: $FLYWAY_VERSION"

MFA_RETIREMENT_STATE="$(query_clone "SELECT (SELECT count(*) FROM admin_mfa_credentials), (SELECT count(*) FROM admin_mfa_recovery_codes), (SELECT count(*) FROM security_rate_limit_buckets WHERE bucket_key LIKE 'mfa-ip:%' OR bucket_key LIKE 'mfa-pair:%');")"
[[ "$MFA_RETIREMENT_STATE" == '0|0|0' ]] || die "migration-rehearsal: legacy MFA state снова появился: $MFA_RETIREMENT_STATE"
MFA_COMPAT_SCHEMA="$(query_clone "SELECT to_regclass('public.admin_mfa_credentials') IS NOT NULL, to_regclass('public.admin_mfa_recovery_codes') IS NOT NULL, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='admin_mfa_credentials' AND column_name='last_totp_counter');")"
[[ "$MFA_COMPAT_SCHEMA" == 't|t|t' ]] || die "migration-rehearsal: legacy MFA compatibility tables unexpectedly changed: $MFA_COMPAT_SCHEMA"
V8_INDEX_STATE="$(query_clone "SELECT to_regclass('public.idx_users_public_rating_username') IS NOT NULL, to_regclass('public.idx_games_public_date_id') IS NOT NULL, to_regclass('public.idx_games_tournament_date_id') IS NOT NULL, to_regclass('public.idx_news_created_at_desc') IS NOT NULL, to_regclass('public.idx_tournaments_created_at_desc') IS NOT NULL;")"
[[ "$V8_INDEX_STATE" == 't|t|t|t|t' ]] || die "migration-rehearsal: V8 query-plan indexes отсутствуют или неполны: $V8_INDEX_STATE"
PERSISTENT_LOGIN_SCHEMA="$(query_clone "SELECT to_regclass('public.persistent_login_tokens') IS NOT NULL, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='persistent_login_tokens' AND column_name='validator_hash'), EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='persistent_login_tokens' AND column_name='credential_version'), EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='persistent_login_tokens' AND column_name='authorization_version');")"
[[ "$PERSISTENT_LOGIN_SCHEMA" == 't|t|t|t' ]] || die "migration-rehearsal: V10 persistent-login schema отсутствует или неполна: $PERSISTENT_LOGIN_SCHEMA"

# Prove the runtime role can read/write application data but cannot perform DDL
# or inspect Flyway history.
runtime_psql() {
  local sql="$1"
  bounded_small --rm --name "$PSQL_CONTAINER" --network "$NETWORK" -e PGPASSWORD="$APP_PASSWORD" "$POSTGRES_IMAGE" \
    psql -At -v ON_ERROR_STOP=1 -h db -U "$APP_USER" -d "$DB_NAME" -c "$sql"
}
runtime_psql 'SELECT count(*) FROM users;' >/dev/null \
  || die "migration-rehearsal: runtime role не может SELECT application data."
runtime_psql 'SELECT count(*) FROM persistent_login_tokens;' >/dev/null \
  || die "migration-rehearsal: runtime role не может читать persistent-login state."
if runtime_psql 'CREATE TABLE wp11023_runtime_ddl_must_fail(id integer);' >/dev/null 2>&1; then
  die "migration-rehearsal: runtime role неожиданно может CREATE TABLE."
fi
if runtime_psql 'SELECT count(*) FROM flyway_schema_history;' >/dev/null 2>&1; then
  die "migration-rehearsal: runtime role неожиданно может читать flyway_schema_history."
fi

wait_resources "candidate runtime"
log "Migration rehearsal: запускаю candidate runtime с Flyway disabled и DML-only role."
bounded_app -d \
  --name "$APP_CONTAINER" --network "$NETWORK" --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m,mode=1777 --cap-drop ALL --security-opt no-new-privileges:true \
  \
  -e SERVER_PORT=8787 -e SPRING_DATASOURCE_URL="jdbc:postgresql://db:5432/$DB_NAME" \
  -e SPRING_DATASOURCE_USERNAME="$APP_USER" -e SPRING_DATASOURCE_PASSWORD="$APP_PASSWORD" \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=validate -e SPRING_FLYWAY_ENABLED=false -e APP_MODE=runtime \
  -e APP_ADMIN_USERNAME=admin -e APP_ADMIN_PASSWORD="$ADMIN_PASSWORD" -e APP_ENV=production \
  -e APP_SECURITY_IP_HASH_SECRET="$IP_SECRET" \
  -e APP_TRUST_PROXY_HEADERS=true -e SERVER_FORWARD_HEADERS_STRATEGY=NATIVE \
  -e APP_LOG_DIR=/tmp/gomaf-logs -e SESSION_COOKIE_SECURE=true \
  "$IMAGE" >/dev/null

APP_OK=0
for _ in $(seq 1 90); do
  if docker exec "$APP_CONTAINER" curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8787/healthz >/dev/null 2>&1; then APP_OK=1; break; fi
  if ! docker inspect -f '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs --tail=250 "$APP_CONTAINER" >&2 || true
    die "migration-rehearsal: runtime candidate завершился на clone БД."
  fi
  sleep 2
done
[[ "$APP_OK" -eq 1 ]] || { docker logs --tail=250 "$APP_CONTAINER" >&2 || true; die "migration-rehearsal: runtime candidate не прошёл healthcheck на clone БД."; }

ROLLBACK_IMAGE="$(cat "$RUNTIME_DIR/current_image" 2>/dev/null || true)"
ROLLBACK_SOURCE_HASH="$(cat "$RUNTIME_DIR/current_source_hash" 2>/dev/null || true)"
[[ -n "$ROLLBACK_IMAGE" && -n "$ROLLBACK_SOURCE_HASH" ]] || die "migration-rehearsal: rollback target metadata отсутствует."
docker image inspect "$ROLLBACK_IMAGE" >/dev/null 2>&1 || die "migration-rehearsal: rollback target image отсутствует: $ROLLBACK_IMAGE"
ROLLBACK_IMAGE_HASH="$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$ROLLBACK_IMAGE" 2>/dev/null || true)"
[[ "$ROLLBACK_IMAGE_HASH" == "$ROLLBACK_SOURCE_HASH" ]] || die "migration-rehearsal: rollback image/source hash mismatch."

wait_resources "rollback runtime"
log "Migration rehearsal: запускаю фактический rollback target на V10-compatible clone с Flyway disabled."
docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
bounded_app -d \
  --name "$ROLLBACK_CONTAINER" --network "$NETWORK" --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m,mode=1777 --cap-drop ALL --security-opt no-new-privileges:true \
  \
  -e SERVER_PORT=8787 -e SPRING_DATASOURCE_URL="jdbc:postgresql://db:5432/$DB_NAME" \
  -e SPRING_DATASOURCE_USERNAME="$APP_USER" -e SPRING_DATASOURCE_PASSWORD="$APP_PASSWORD" \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=validate -e SPRING_FLYWAY_ENABLED=false -e APP_MODE=runtime \
  -e APP_ADMIN_USERNAME=admin -e APP_ADMIN_PASSWORD="$ADMIN_PASSWORD" -e APP_ENV=production \
  -e APP_SECURITY_IP_HASH_SECRET="$IP_SECRET" -e APP_SECURITY_MFA_ENCRYPTION_KEY="$LEGACY_ROLLBACK_MFA_KEY" \
  -e APP_TRUST_PROXY_HEADERS=true -e SERVER_FORWARD_HEADERS_STRATEGY=NATIVE \
  -e APP_LOG_DIR=/tmp/gomaf-logs -e SESSION_COOKIE_SECURE=true \
  "$ROLLBACK_IMAGE" >/dev/null

ROLLBACK_OK=0
for _ in $(seq 1 90); do
  if docker exec "$ROLLBACK_CONTAINER" curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8787/healthz >/dev/null 2>&1; then ROLLBACK_OK=1; break; fi
  if ! docker inspect -f '{{.State.Running}}' "$ROLLBACK_CONTAINER" 2>/dev/null | grep -qx true; then
    docker logs --tail=250 "$ROLLBACK_CONTAINER" >&2 || true
    die "migration-rehearsal: rollback target завершился на V10-compatible clone БД."
  fi
  sleep 2
done
[[ "$ROLLBACK_OK" -eq 1 ]] || { docker logs --tail=250 "$ROLLBACK_CONTAINER" >&2 || true; die "migration-rehearsal: rollback target не прошёл healthcheck на V10-compatible clone БД."; }

ADMIN_STATE="$(query_clone "SELECT count(*), min(username), min(username_key) FROM users WHERE account_role='ADMIN';")"
[[ "$ADMIN_STATE" == '1|admin|admin' ]] || die "migration-rehearsal: нарушен ADMIN invariant: $ADMIN_STATE"

COMPAT_BAD="$(query_clone "SELECT (SELECT count(*) FROM users WHERE NOT ((account_role='ADMIN' AND role='ADMIN') OR (account_role IN ('PLAYER','MODERATOR') AND role='PLAYER'))), (SELECT count(*) FROM moderator_scope_defaults WHERE scope NOT IN ('NEWS_MANAGE','RULES_MANAGE','GAMES_MANAGE','SCHEDULE_MANAGE','PLAYER_RATING_MANAGE','PLAYER_USERNAME_MANAGE','PLAYER_AVATAR_RESET','PLAYER_PASSWORD_RESET','PLAYER_DELETE')), (SELECT count(*) FROM moderator_scope_overrides WHERE scope NOT IN ('NEWS_MANAGE','RULES_MANAGE','GAMES_MANAGE','SCHEDULE_MANAGE','PLAYER_RATING_MANAGE','PLAYER_USERNAME_MANAGE','PLAYER_AVATAR_RESET','PLAYER_PASSWORD_RESET','PLAYER_DELETE')); ")"
[[ "$COMPAT_BAD" == '0|0|0' ]] || die "migration-rehearsal: rollback compatibility violated: $COMPAT_BAD"

log "MIGRATION REHEARSAL PASS: production не изменялся; clone мигрирован до V10, persistent-login schema проверена, legacy MFA secrets/state очищены, V8 query-plan indexes сохранены, candidate и фактический rollback target healthy, runtime DDL/Flyway access запрещён."
