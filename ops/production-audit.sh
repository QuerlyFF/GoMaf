#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

need_cmd docker
need_cmd curl
need_cmd stat
need_cmd openssl
validate_prod_config
DOMAIN="$(env_get DOMAIN)"
APP_DB_USER="$(env_get APP_DB_USER)"
ACTIVE_RELEASE="$(active_release_dir)"
FAIL=0

check() {
  local label="$1"; shift
  if "$@"; then
    printf 'PASS %s\n' "$label"
  else
    printf 'FAIL %s\n' "$label" >&2
    FAIL=1
  fi
}

db_query() {
  local sql="$1"
  printf '%s\n' "$sql" | compose exec -T db sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" psql -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}

if [[ "$ACTIVE_RELEASE" == "$RELEASES_DIR/"* && -f "$ACTIVE_RELEASE/compose.prod.yaml" ]]; then
  printf 'PASS active runtime uses immutable release snapshot %s\n' "$ACTIVE_RELEASE"
else
  printf 'FAIL active runtime does not point to release snapshot: %s\n' "$ACTIVE_RELEASE" >&2
  FAIL=1
fi
[[ "$(stat -c '%a' "$RUNTIME_DIR" 2>/dev/null || true)" == 700 ]] \
  && printf 'PASS runtime metadata directory mode 0700\n' \
  || { printf 'FAIL runtime metadata directory permissions\n' >&2; FAIL=1; }
[[ "$(stat -c '%a' "$RELEASES_DIR" 2>/dev/null || true)" == 700 ]] \
  && printf 'PASS release directory mode 0700\n' \
  || { printf 'FAIL release directory permissions\n' >&2; FAIL=1; }
check "active release source audit" "$ACTIVE_RELEASE/ops/source-audit.sh"
if find "$ACTIVE_RELEASE" -xdev -perm /0222 -print -quit 2>/dev/null | grep -q .; then
  printf 'FAIL active release snapshot contains writable entries\n' >&2
  FAIL=1
else
  printf 'PASS active release snapshot is filesystem read-only\n'
fi
check "production env readable" test -r "$ENV_FILE"
mode="$(stat -c '%a' "$ENV_FILE")"
owner="$(stat -c '%U' "$ENV_FILE")"
group="$(stat -c '%G' "$ENV_FILE")"
[[ "$mode" == 600 ]] && printf 'PASS production env mode 0600\n' || { printf 'FAIL production env mode %s\n' "$mode" >&2; FAIL=1; }
[[ "$owner:$group" == root:root ]] && printf 'PASS production env owner root:root\n' || { printf 'FAIL production env owner %s:%s\n' "$owner" "$group" >&2; FAIL=1; }
dir_mode="$(stat -c '%a' /etc/gomaf 2>/dev/null || true)"
dir_owner="$(stat -c '%U:%G' /etc/gomaf 2>/dev/null || true)"
[[ "$dir_mode" == 700 && "$dir_owner" == root:root ]] && printf 'PASS /etc/gomaf root-only permissions\n' || { printf 'FAIL /etc/gomaf permissions %s %s\n' "$dir_mode" "$dir_owner" >&2; FAIL=1; }
if runuser -u mafia -- test -r "$ENV_FILE" 2>/dev/null; then printf 'FAIL mafia can read production.env\n' >&2; FAIL=1; else printf 'PASS mafia cannot read production.env\n'; fi
if id -nG mafia | tr ' ' '\n' | grep -Eq '^(docker|gomaf|sudo)$'; then printf 'FAIL mafia retains privileged supplementary group\n' >&2; FAIL=1; else printf 'PASS mafia has no docker/gomaf/sudo group\n'; fi
if runuser -u mafia -- test -r /var/run/docker.sock 2>/dev/null; then printf 'FAIL mafia can read Docker socket\n' >&2; FAIL=1; else printf 'PASS mafia cannot read Docker socket\n'; fi

ssh_effective="$(sshd -T 2>/dev/null || true)"
for expected in 'allowtcpforwarding no' 'allowstreamlocalforwarding no' 'permitopen none' 'permitlisten none'; do
  printf '%s\n' "$ssh_effective" | grep -Fxq "$expected" \
    && printf 'PASS effective SSH hardening %s\n' "$expected" \
    || { printf 'FAIL effective SSH hardening missing %s\n' "$expected" >&2; FAIL=1; }
done

backup_user="$(systemctl show -p User --value gomaf-backup.service 2>/dev/null || true)"
backup_group="$(systemctl show -p Group --value gomaf-backup.service 2>/dev/null || true)"
backup_umask="$(systemctl show -p UMask --value gomaf-backup.service 2>/dev/null || true)"
backup_supp="$(systemctl show -p SupplementaryGroups --value gomaf-backup.service 2>/dev/null || true)"
[[ "$backup_user" == root && "$backup_group" == root ]] \
  && printf 'PASS daily backup service runs as root-owned system job\n' \
  || { printf 'FAIL daily backup service identity %s:%s\n' "$backup_user" "$backup_group" >&2; FAIL=1; }
[[ "$backup_umask" == 0077 || "$backup_umask" == 77 ]] \
  && printf 'PASS daily backup service umask 0077\n' \
  || { printf 'FAIL daily backup service umask %s\n' "${backup_umask:-missing}" >&2; FAIL=1; }
[[ -z "$backup_supp" ]] \
  && printf 'PASS daily backup service has no supplementary groups\n' \
  || { printf 'FAIL daily backup service supplementary groups: %s\n' "$backup_supp" >&2; FAIL=1; }
systemctl is-enabled --quiet gomaf-backup.timer \
  && printf 'PASS daily backup timer enabled\n' \
  || { printf 'FAIL daily backup timer disabled\n' >&2; FAIL=1; }

check_dir_meta() {
  local path="$1" expected_mode="$2" expected_uid="$3" expected_gid="$4"
  local mode uid gid
  [[ -d "$path" ]] || { printf 'FAIL storage directory %s missing\n' "$path" >&2; FAIL=1; return; }
  mode="$(stat -c '%a' "$path")"
  uid="$(stat -c '%u' "$path")"
  gid="$(stat -c '%g' "$path")"
  if [[ "$mode" == "$expected_mode" && "$uid" == "$expected_uid" && "$gid" == "$expected_gid" ]]; then
    printf 'PASS storage directory %s permissions %s %s:%s\n' "$path" "$mode" "$uid" "$gid"
  else
    printf 'FAIL storage directory %s permissions %s %s:%s expected %s %s:%s\n' \
      "$path" "$mode" "$uid" "$gid" "$expected_mode" "$expected_uid" "$expected_gid" >&2
    FAIL=1
  fi
}

check_root_helper() {
  local path="$1" mode owner
  [[ -f "$path" && -x "$path" ]] || { printf 'FAIL helper %s missing/not executable\n' "$path" >&2; FAIL=1; return; }
  mode="$(stat -c '%a' "$path")"
  owner="$(stat -c '%U:%G' "$path")"
  if [[ "$mode" == 755 && "$owner" == root:root ]]; then
    printf 'PASS helper %s root-owned mode 0755\n' "$path"
  else
    printf 'FAIL helper %s metadata %s %s\n' "$path" "$mode" "$owner" >&2
    FAIL=1
  fi
}

check_tree_safety() {
  local path="$1"
  if find "$path" -xdev -type l -print -quit 2>/dev/null | grep -q .; then
    printf 'FAIL %s contains symlink\n' "$path" >&2
    FAIL=1
  else
    printf 'PASS %s contains no symlink\n' "$path"
  fi
  if find "$path" -xdev -type f -perm -0002 -print -quit 2>/dev/null | grep -q .; then
    printf 'FAIL %s contains world-writable file\n' "$path" >&2
    FAIL=1
  else
    printf 'PASS %s contains no world-writable file\n' "$path"
  fi
}

check_dir_meta /srv/gomaf-logs/app 750 10001 10001
check_dir_meta /srv/gomaf-logs/caddy 750 0 0
check_dir_meta /srv/gomaf-log-exports 700 0 0
check_dir_meta /srv/gomaf-backups 700 0 0
# app/caddy log directories intentionally deny host traversal to mafia. Their
# file-level safety is checked through the running containers below.
for dir in /srv/gomaf-log-exports /srv/gomaf-backups; do
  check_tree_safety "$dir"
done
check_root_helper /usr/local/sbin/gomaf-export-logs
check_root_helper /usr/local/sbin/gomaf-storage-maintenance
check_root_helper /usr/local/sbin/gomaf-v10-admin-switch
check_root_helper /usr/local/sbin/gomaf-set-admin
check_root_helper /usr/local/sbin/gomaf-aide-check
check_root_helper /usr/local/sbin/gomaf-test-memory-guard
check_root_helper /usr/local/sbin/gomaf-test-preflight
check_root_helper /usr/local/sbin/gomaf-safe-test-run
check_root_helper /usr/local/sbin/gomaf-safe-build
check_root_helper /usr/local/sbin/gomaf-ops-alert
check_root_helper /usr/local/sbin/gomaf-ops-health-check
check_root_helper /usr/local/sbin/gomaf-predeploy-release
check_root_helper /usr/local/sbin/gomaf-deploy-release
check_root_helper /usr/local/libexec/gomaf-release-common
if [[ ! -e /etc/gomaf/v10-v9-admin-username && ! -L /etc/gomaf/v10-v9-admin-username ]]; then
  printf 'PASS temporary v9 ADMIN transition state finalized/absent\n'
else
  printf 'FAIL temporary v9 ADMIN transition state still exists after v10 deploy\n' >&2
  FAIL=1
fi

if [[ -e /var/run/reboot-required ]]; then
  printf 'FAIL host reboot required before production acceptance
' >&2
  FAIL=1
else
  printf 'PASS host has no pending reboot requirement
'
fi

check "docker daemon" docker info >/dev/null
check "compose config" compose config --quiet

for svc in db app caddy; do
  if compose ps --status running --services | grep -qx "$svc"; then
    printf 'PASS service %s running\n' "$svc"
  else
    printf 'FAIL service %s not running\n' "$svc" >&2
    FAIL=1
  fi
done

for svc in db app; do
  id="$(compose ps -q "$svc" || true)"
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)"
  [[ "$health" == healthy ]] && printf 'PASS service %s healthy\n' "$svc" || { printf 'FAIL service %s health %s\n' "$svc" "${health:-missing}" >&2; FAIL=1; }
done

APP_ID="$(compose ps -q app || true)"
DB_ID="$(compose ps -q db || true)"
CADDY_ID="$(compose ps -q caddy || true)"
EXPECTED_IMAGE="$(cat "$RUNTIME_DIR/current_image" 2>/dev/null || true)"
EXPECTED_SOURCE_HASH="$(cat "$RUNTIME_DIR/current_source_hash" 2>/dev/null || true)"
for pointer in "$ACTIVE_RELEASE_FILE" "$RUNTIME_DIR/current_image" "$RUNTIME_DIR/current_source_hash"; do
  [[ -f "$pointer" && "$(stat -c '%a' "$pointer")" == 600 ]] \
    && printf 'PASS runtime pointer %s mode 0600\n' "$pointer" \
    || { printf 'FAIL runtime pointer %s missing/wrong mode\n' "$pointer" >&2; FAIL=1; }
done

PREVIOUS_RELEASE="$(cat "$RUNTIME_DIR/previous_release" 2>/dev/null || true)"
if [[ -n "$PREVIOUS_RELEASE" ]]; then
  PREVIOUS_IMAGE="$(cat "$RUNTIME_DIR/previous_image" 2>/dev/null || true)"
  PREVIOUS_SOURCE_HASH="$(cat "$RUNTIME_DIR/previous_source_hash" 2>/dev/null || true)"
  for pointer in "$RUNTIME_DIR/previous_release" "$RUNTIME_DIR/previous_image" "$RUNTIME_DIR/previous_source_hash"; do
    [[ -f "$pointer" && "$(stat -c '%a' "$pointer")" == 600 ]] \
      && printf 'PASS rollback pointer %s mode 0600\n' "$pointer" \
      || { printf 'FAIL rollback pointer %s missing/wrong mode\n' "$pointer" >&2; FAIL=1; }
  done
  if [[ -f "$PREVIOUS_RELEASE/compose.prod.yaml" && -n "$PREVIOUS_IMAGE" && -n "$PREVIOUS_SOURCE_HASH" ]]; then
    previous_image_hash="$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$PREVIOUS_IMAGE" 2>/dev/null || true)"
    [[ "$previous_image_hash" == "$PREVIOUS_SOURCE_HASH" ]] \
      && printf 'PASS rollback image/release source metadata consistent\n' \
      || { printf 'FAIL rollback source metadata mismatch\n' >&2; FAIL=1; }
  else
    printf 'FAIL rollback metadata incomplete\n' >&2
    FAIL=1
  fi
else
  printf 'INFO no previous immutable release is registered; rollback readiness check skipped\n'
fi
if [[ -n "$APP_ID" ]]; then
  app_ports="$(docker inspect -f '{{json .HostConfig.PortBindings}}' "$APP_ID")"
  [[ "$app_ports" == "{}" || "$app_ports" == "null" ]] && printf 'PASS app has no published host ports\n' || { printf 'FAIL app exposes host ports: %s\n' "$app_ports" >&2; FAIL=1; }
  actual_image="$(docker inspect -f '{{.Config.Image}}' "$APP_ID")"
  [[ -n "$EXPECTED_IMAGE" && "$actual_image" == "$EXPECTED_IMAGE" ]] && printf 'PASS running app image matches runtime pointer\n' || { printf 'FAIL running app image %s expected %s\n' "$actual_image" "${EXPECTED_IMAGE:-missing}" >&2; FAIL=1; }
  actual_source_hash="$(docker inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$APP_ID")"
  [[ -n "$EXPECTED_SOURCE_HASH" && "$actual_source_hash" == "$EXPECTED_SOURCE_HASH" ]] && printf 'PASS running app source hash matches runtime pointer\n' || { printf 'FAIL running app source hash %s expected %s\n' "$actual_source_hash" "${EXPECTED_SOURCE_HASH:-missing}" >&2; FAIL=1; }
  app_label="$(docker inspect -f '{{index .Config.Labels "com.gomaf.application"}}' "$APP_ID")"
  [[ "$app_label" == classic-mafia ]] && printf 'PASS running app image has GoMaf application label\n' || { printf 'FAIL running app image label: %s\n' "${app_label:-missing}" >&2; FAIL=1; }
  [[ "$(docker inspect -f '{{.Config.User}}' "$APP_ID")" == 10001 ]] && printf 'PASS app runs as UID 10001\n' || { printf 'FAIL app user is not UID 10001\n' >&2; FAIL=1; }
  [[ "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$APP_ID")" == true ]] && printf 'PASS app rootfs read-only\n' || { printf 'FAIL app rootfs writable\n' >&2; FAIL=1; }
  docker inspect -f '{{json .HostConfig.CapDrop}}' "$APP_ID" | grep -q 'ALL' && printf 'PASS app drops ALL capabilities\n' || { printf 'FAIL app does not drop ALL capabilities\n' >&2; FAIL=1; }
  docker inspect -f '{{json .HostConfig.SecurityOpt}}' "$APP_ID" | grep -q 'no-new-privileges:true' && printf 'PASS app no-new-privileges\n' || { printf 'FAIL app missing no-new-privileges\n' >&2; FAIL=1; }
  app_env="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$APP_ID")"
  printf '%s\n' "$app_env" | grep -qx 'APP_ENV=production' && printf 'PASS app runtime environment production\n' || { printf 'FAIL app runtime APP_ENV is not production\n' >&2; FAIL=1; }
  if printf '%s\n' "$app_env" | grep -q '^APP_SPA_ENABLED='; then printf 'FAIL retired APP_SPA_ENABLED toggle still present\n' >&2; FAIL=1; else printf 'PASS retired SPA toggle absent from runtime\n'; fi
  printf '%s\n' "$app_env" | grep -qx 'SERVER_FORWARD_HEADERS_STRATEGY=NATIVE' && printf 'PASS app runtime forwarded-header mode NATIVE\n' || { printf 'FAIL app runtime forwarded-header mode\n' >&2; FAIL=1; }
  printf '%s\n' "$app_env" | grep -qx 'SESSION_COOKIE_SECURE=true' && printf 'PASS app runtime secure session cookie\n' || { printf 'FAIL app runtime secure session cookie disabled\n' >&2; FAIL=1; }
printf '%s\n' "$app_env" | grep -qx 'SPRING_FLYWAY_ENABLED=false' && printf 'PASS runtime app Flyway disabled\n' || { printf 'FAIL runtime app Flyway not disabled\n' >&2; FAIL=1; }
printf '%s\n' "$app_env" | grep -qx "SPRING_DATASOURCE_USERNAME=$APP_DB_USER" && printf 'PASS runtime app uses DML DB role\n' || { printf 'FAIL runtime app DB role mismatch\n' >&2; FAIL=1; }
  app_log_mount="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/log/gomaf"}}{{.Source}}|{{.RW}}{{end}}{{end}}' "$APP_ID")"
  [[ "$app_log_mount" == '/srv/gomaf-logs/app|true' ]] && printf 'PASS app log mount is dedicated writable volume\n' || { printf 'FAIL app log mount: %s\n' "${app_log_mount:-missing}" >&2; FAIL=1; }
fi
if [[ -n "$DB_ID" ]]; then
  db_ports="$(docker inspect -f '{{json .HostConfig.PortBindings}}' "$DB_ID")"
  [[ "$db_ports" == "{}" || "$db_ports" == "null" ]] && printf 'PASS db has no published host ports\n' || { printf 'FAIL db exposes host ports: %s\n' "$db_ports" >&2; FAIL=1; }
fi
if [[ -n "$CADDY_ID" ]]; then
  caddy_ports="$(docker inspect -f '{{json .HostConfig.PortBindings}}' "$CADDY_ID")"
  printf '%s' "$caddy_ports" | grep -q '80/tcp' && printf '%s' "$caddy_ports" | grep -q '443/tcp' \
    && printf 'PASS Caddy publishes 80/443\n' || { printf 'FAIL Caddy 80/443 bindings missing\n' >&2; FAIL=1; }
  caddy_log_mount="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/log/caddy"}}{{.Source}}|{{.RW}}{{end}}{{end}}' "$CADDY_ID")"
  [[ "$caddy_log_mount" == '/srv/gomaf-logs/caddy|true' ]] && printf 'PASS Caddy access-log mount is dedicated writable volume\n' || { printf 'FAIL Caddy log mount: %s\n' "${caddy_log_mount:-missing}" >&2; FAIL=1; }
  caddy_config_mount="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/etc/caddy/Caddyfile"}}{{.Source}}|{{.RW}}{{end}}{{end}}' "$CADDY_ID")"
  [[ "$caddy_config_mount" == "$ACTIVE_RELEASE/caddy/Caddyfile|false" ]] && printf 'PASS Caddy uses active release configuration read-only\n' || { printf 'FAIL Caddy config mount: %s\n' "${caddy_config_mount:-missing}" >&2; FAIL=1; }
  if docker exec "$CADDY_ID" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
    printf 'PASS running Caddy configuration validates\n'
  else
    printf 'FAIL running Caddy configuration invalid\n' >&2
    FAIL=1
  fi
fi

MIGRATION_DB_USER="$(env_get MIGRATION_DB_USER)"
role_flags="$(db_query "SELECT concat_ws(',', rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls) FROM pg_roles WHERE rolname='$APP_DB_USER';")"
[[ "$role_flags" == "f,f,f,f,f" ]] && printf 'PASS application DB role is non-privileged\n' || { printf 'FAIL application DB role flags: %s\n' "$role_flags" >&2; FAIL=1; }
migration_flags="$(db_query "SELECT concat_ws(',', rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls) FROM pg_roles WHERE rolname='$MIGRATION_DB_USER';")"
[[ "$migration_flags" == "f,f,f,f,f" ]] && printf 'PASS migration DB role is non-privileged\n' || { printf 'FAIL migration DB role flags: %s\n' "$migration_flags" >&2; FAIL=1; }
schema_owner="$(db_query "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='public';")"
[[ "$schema_owner" == "$MIGRATION_DB_USER" ]] && printf 'PASS public schema owned by migration role\n' || { printf 'FAIL public schema owner: %s\n' "$schema_owner" >&2; FAIL=1; }
runtime_create="$(db_query "SELECT has_schema_privilege('$APP_DB_USER','public','CREATE');")"
[[ "$runtime_create" == f ]] && printf 'PASS runtime DB role has no schema CREATE\n' || { printf 'FAIL runtime DB role still has schema CREATE\n' >&2; FAIL=1; }
flyway_table="$(db_query "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;")"
[[ "$flyway_table" == t ]] && printf 'PASS Flyway schema history present\n' || { printf 'FAIL Flyway schema history missing\n' >&2; FAIL=1; }
flyway_read="$(db_query "SELECT has_table_privilege('$APP_DB_USER','public.flyway_schema_history','SELECT');")"
[[ "$flyway_read" == f ]] && printf 'PASS runtime DB role cannot read Flyway history\n' || { printf 'FAIL runtime DB role can read Flyway history\n' >&2; FAIL=1; }
flyway_v="$(db_query "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;")"
[[ "$flyway_v" == 10 ]] && printf 'PASS Flyway schema at v10\n' || { printf 'FAIL Flyway expected v10, got %s\n' "$flyway_v" >&2; FAIL=1; }
io_timing="$(db_query "SHOW track_io_timing;")"
[[ "$io_timing" == on ]] && printf 'PASS PostgreSQL track_io_timing enabled\n' || { printf 'FAIL PostgreSQL track_io_timing is %s\n' "${io_timing:-missing}" >&2; FAIL=1; }
security_schema="$(db_query "SELECT to_regclass('public.security_rate_limit_buckets') IS NOT NULL, to_regclass('public.admin_mfa_credentials') IS NOT NULL, to_regclass('public.admin_mfa_recovery_codes') IS NOT NULL, to_regclass('public.security_sessions') IS NOT NULL, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='admin_mfa_credentials' AND column_name='last_totp_counter');")"
[[ "$security_schema" == 't|t|t|t|t' ]] && printf 'PASS security persistence and rollback-compatibility schema present\n' || { printf 'FAIL security compatibility schema: %s\n' "$security_schema" >&2; FAIL=1; }
persistent_login_schema="$(db_query "SELECT to_regclass('public.persistent_login_tokens') IS NOT NULL, has_table_privilege('$APP_DB_USER','public.persistent_login_tokens','SELECT'), has_table_privilege('$APP_DB_USER','public.persistent_login_tokens','INSERT'), has_table_privilege('$APP_DB_USER','public.persistent_login_tokens','UPDATE'), has_table_privilege('$APP_DB_USER','public.persistent_login_tokens','DELETE');")"
[[ "$persistent_login_schema" == 't|t|t|t|t' ]] && printf 'PASS persistent-login schema and runtime DML privileges\n' || { printf 'FAIL persistent-login schema/privileges: %s\n' "$persistent_login_schema" >&2; FAIL=1; }
mfa_retired="$(db_query "SELECT (SELECT count(*) FROM admin_mfa_credentials), (SELECT count(*) FROM admin_mfa_recovery_codes), (SELECT count(*) FROM security_rate_limit_buckets WHERE bucket_key LIKE 'mfa-ip:%' OR bucket_key LIKE 'mfa-pair:%');")"
[[ "$mfa_retired" == '0|0|0' ]] && printf 'PASS MFA runtime state and secrets retired\n' || { printf 'FAIL legacy MFA state remains: %s\n' "$mfa_retired" >&2; FAIL=1; }
v8_indexes="$(db_query "SELECT to_regclass('public.idx_users_public_rating_username') IS NOT NULL, to_regclass('public.idx_games_public_date_id') IS NOT NULL, to_regclass('public.idx_games_tournament_date_id') IS NOT NULL, to_regclass('public.idx_news_created_at_desc') IS NOT NULL, to_regclass('public.idx_tournaments_created_at_desc') IS NOT NULL;")"
[[ "$v8_indexes" == 't|t|t|t|t' ]] && printf 'PASS V8 query-plan indexes present\n' || { printf 'FAIL V8 query-plan indexes: %s\n' "$v8_indexes" >&2; FAIL=1; }

feature_schema="$(db_query "SELECT to_regclass('public.tournaments') IS NOT NULL, to_regclass('public.tournament_dates') IS NOT NULL, to_regclass('public.tournament_participants') IS NOT NULL, to_regclass('public.schedule_participants') IS NOT NULL, to_regclass('public.game_videos') IS NOT NULL, to_regclass('public.game_visibility_users') IS NOT NULL, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='users' AND column_name='about_me'), EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='games' AND column_name='hidden_from_public');")"
[[ "$feature_schema" == t\|t\|t\|t\|t\|t\|t\|t ]] && printf 'PASS v10.2 schedule/tournament/profile schema present\n' || { printf 'FAIL v10.2 feature schema: %s\n' "$feature_schema" >&2; FAIL=1; }
auth_schema="$(db_query "SELECT to_regclass('public.moderator_scope_defaults') IS NOT NULL, to_regclass('public.moderator_scope_overrides') IS NOT NULL, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='users' AND column_name='account_role'), EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='users' AND column_name='authorization_version');")"
[[ "$auth_schema" == t\|t\|t\|t ]] && printf 'PASS v10 authorization schema present\n' || { printf 'FAIL v10 authorization schema: %s\n' "$auth_schema" >&2; FAIL=1; }
admin_state="$(db_query "SELECT count(*), min(username), min(username_key) FROM users WHERE account_role='ADMIN';")"
[[ "$admin_state" == 1\|admin\|admin ]] && printf 'PASS exactly one hidden ADMIN identity admin\n' || { printf 'FAIL ADMIN invariant: %s\n' "$admin_state" >&2; FAIL=1; }
compat_bad="$(db_query "SELECT count(*) FROM users WHERE NOT ((account_role='ADMIN' AND role='ADMIN') OR (account_role IN ('PLAYER','MODERATOR') AND role='PLAYER'));")"
[[ "$compat_bad" == 0 ]] && printf 'PASS v9 rollback role compatibility preserved\n' || { printf 'FAIL rollback role compatibility rows: %s\n' "$compat_bad" >&2; FAIL=1; }
scope_count="$(db_query "SELECT count(*) FROM moderator_scope_defaults;")"
[[ "$scope_count" == 9 ]] && printf 'PASS all 9 MODERATOR default scopes exist\n' || { printf 'FAIL moderator default scope count: %s\n' "$scope_count" >&2; FAIL=1; }

scope_names="$(db_query "SELECT string_agg(scope, '|' ORDER BY scope) FROM moderator_scope_defaults;")"
expected_scopes='GAMES_MANAGE|NEWS_MANAGE|PLAYER_AVATAR_RESET|PLAYER_DELETE|PLAYER_PASSWORD_RESET|PLAYER_RATING_MANAGE|PLAYER_USERNAME_MANAGE|RULES_MANAGE|SCHEDULE_MANAGE'
[[ "$scope_names" == "$expected_scopes" ]] && printf 'PASS MODERATOR scope set exactly matches v10\n' || { printf 'FAIL moderator scope set: %s\n' "$scope_names" >&2; FAIL=1; }

bad_override_owner="$(db_query "SELECT count(*) FROM moderator_scope_overrides o JOIN users u ON u.id=o.user_id WHERE u.account_role <> 'MODERATOR';")"
[[ "$bad_override_owner" == 0 ]] && printf 'PASS scope overrides belong only to MODERATOR accounts\n' || { printf 'FAIL scope overrides attached to non-moderators: %s\n' "$bad_override_owner" >&2; FAIL=1; }

incomplete_moderators="$(db_query "SELECT count(*) FROM users u WHERE u.account_role='MODERATOR' AND (SELECT count(*) FROM moderator_scope_overrides o WHERE o.user_id=u.id) <> 9;")"
[[ "$incomplete_moderators" == 0 ]] && printf 'PASS each MODERATOR has complete override matrix\n' || { printf 'FAIL moderators with incomplete override matrix: %s\n' "$incomplete_moderators" >&2; FAIL=1; }

security_bad="$(db_query "SELECT count(*) FROM users WHERE username_key <> translate(btrim(username), 'ABCDEFGHIJKLMNOPQRSTUVWXYZАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'abcdefghijklmnopqrstuvwxyzабвгдеёжзийклмнопрстуфхцчшщъыьэюя') OR credential_version < 0 OR authorization_version < 0;")"
[[ "$security_bad" == 0 ]] && printf 'PASS user security invariants hold for all rows\n' || { printf 'FAIL invalid user security rows: %s\n' "$security_bad" >&2; FAIL=1; }

enabled_scopes="$(db_query "SELECT count(*) FROM moderator_scope_defaults WHERE enabled;")"
printf 'INFO MODERATOR global scopes currently enabled=%s/9\n' "$enabled_scopes"

if command -v systemctl >/dev/null 2>&1; then
  systemctl is-enabled --quiet gomaf-backup.timer && printf 'PASS backup timer enabled\n' || { printf 'FAIL backup timer not enabled\n' >&2; FAIL=1; }
  systemctl is-active --quiet gomaf-backup.timer && printf 'PASS backup timer active\n' || { printf 'FAIL backup timer not active\n' >&2; FAIL=1; }
  systemctl is-enabled --quiet gomaf-storage-maintenance.timer && printf 'PASS storage maintenance timer enabled\n' || { printf 'FAIL storage maintenance timer not enabled\n' >&2; FAIL=1; }
  systemctl is-active --quiet gomaf-storage-maintenance.timer && printf 'PASS storage maintenance timer active\n' || { printf 'FAIL storage maintenance timer not active\n' >&2; FAIL=1; }
  for timer in gomaf-aide-check.timer gomaf-security-check.timer gomaf-ops-health-check.timer; do
    systemctl is-enabled --quiet "$timer" && printf 'PASS %s enabled\n' "$timer" || { printf 'FAIL %s not enabled\n' "$timer" >&2; FAIL=1; }
    systemctl is-active --quiet "$timer" && printf 'PASS %s active\n' "$timer" || { printf 'FAIL %s not active\n' "$timer" >&2; FAIL=1; }
  done
  systemctl is-enabled --quiet gomaf-test-memory-guard.service && printf 'PASS test memory guard enabled\n' || { printf 'FAIL test memory guard not enabled\n' >&2; FAIL=1; }
  systemctl is-active --quiet gomaf-test-memory-guard.service && printf 'PASS test memory guard active\n' || { printf 'FAIL test memory guard inactive\n' >&2; FAIL=1; }
  if [[ -r /var/lib/gomaf-ops-health/last-health ]] && grep -q 'status=PASS' /var/lib/gomaf-ops-health/last-health; then
    printf 'PASS operational health monitor last result PASS\n'
  else
    printf 'FAIL operational health monitor has no PASS state\n' >&2
    FAIL=1
  fi
  systemctl is-active --quiet auditd && printf 'PASS auditd active\n' || { printf 'FAIL auditd inactive\n' >&2; FAIL=1; }
  audit_lost="$(auditctl -s 2>/dev/null | awk '$1=="lost"{print $2}')"
  if [[ "$audit_lost" == 0 ]]; then
    printf 'PASS auditd lost=0\n'
  else
    printf 'FAIL auditd lost=%s\n' "$audit_lost" >&2
    FAIL=1
  fi
  systemctl is-active --quiet fail2ban && printf 'PASS fail2ban active\n' || { printf 'FAIL fail2ban inactive\n' >&2; FAIL=1; }
  fail2ban-client status recidive >/dev/null 2>&1 && printf 'PASS fail2ban recidive jail active\n' || { printf 'FAIL fail2ban recidive jail inactive\n' >&2; FAIL=1; }
  # UFW applies persistent firewall rules and does not need to remain a
  # long-running daemon. Audit its enablement/configuration instead of treating
  # an inactive systemd service state as equivalent to a disabled firewall.
  if systemctl is-enabled --quiet ufw 2>/dev/null \
      && [[ -r /etc/ufw/ufw.conf ]] \
      && grep -Eq '^ENABLED=yes$' /etc/ufw/ufw.conf; then
    printf 'PASS UFW enabled and configured for boot\n'
  else
    printf 'FAIL UFW is not enabled/configured for boot\n' >&2
    FAIL=1
  fi
fi

if [[ -r /etc/ssh/sshd_config.d/00-gomaf-hardening.conf ]]; then
  ssh_hardening="$(cat /etc/ssh/sshd_config.d/00-gomaf-hardening.conf)"
  for directive in 'PermitRootLogin no' 'PasswordAuthentication no' 'KbdInteractiveAuthentication no' 'PubkeyAuthentication yes' 'AuthenticationMethods publickey'; do
    printf '%s\n' "$ssh_hardening" | grep -Fxq "$directive" || { printf 'FAIL SSH hardening file missing: %s\n' "$directive" >&2; FAIL=1; continue; }
    printf 'PASS SSH hardening file %s\n' "$directive"
  done
else
  printf 'FAIL SSH hardening config missing\n' >&2
  FAIL=1
fi
if command -v sshd >/dev/null 2>&1 && sshd -t >/dev/null 2>&1; then
  ssh_effective="$(sshd -T)"
  for directive in 'permitrootlogin no' 'passwordauthentication no' 'kbdinteractiveauthentication no' 'pubkeyauthentication yes' 'authenticationmethods publickey'; do
    if printf '%s\n' "$ssh_effective" | grep -Fxq "$directive"; then
      printf 'PASS effective SSH hardening %s\n' "$directive"
    else
      printf 'FAIL effective SSH hardening mismatch: %s\n' "$directive" >&2
      FAIL=1
    fi
  done
else
  printf 'FAIL sshd effective configuration cannot be validated\n' >&2
  FAIL=1
fi

if [[ -r /etc/fail2ban/jail.d/gomaf-sshd.local ]] && grep -Eq '^enabled[[:space:]]*=[[:space:]]*true$' /etc/fail2ban/jail.d/gomaf-sshd.local; then
  printf 'PASS fail2ban sshd jail enabled\n'
else
  printf 'FAIL fail2ban sshd jail config missing/disabled\n' >&2
  FAIL=1
fi

if getent ahostsv4 "$DOMAIN" >/dev/null 2>&1; then
  printf 'PASS DNS A/IPv4 resolves for %s\n' "$DOMAIN"
else
  printf 'FAIL DNS A/IPv4 does not resolve for %s\n' "$DOMAIN" >&2
  FAIL=1
fi

if echo | openssl s_client -servername "$DOMAIN" -connect "$DOMAIN:443" 2>/dev/null | openssl x509 -noout -checkend 604800 >/dev/null 2>&1; then
  printf 'PASS TLS certificate valid for more than 7 days\n'
else
  printf 'FAIL TLS certificate missing or expires within 7 days\n' >&2
  FAIL=1
fi

if "$ACTIVE_RELEASE/ops/smoke-test.sh"; then
  printf 'PASS HTTPS smoke test\n'
else
  printf 'FAIL HTTPS smoke test\n' >&2
  FAIL=1
fi

check_container_tree_safety() {
  local container_id="$1" path="$2" label="$3"
  if [[ -z "$container_id" ]]; then
    printf 'FAIL %s container missing for log-tree safety\n' "$label" >&2
    FAIL=1
    return
  fi
  if docker exec "$container_id" sh -ec 'test ! -n "$(find "$1" -xdev -type l -print -quit 2>/dev/null)"' sh "$path" >/dev/null 2>&1; then
    printf 'PASS %s log mount contains no symlink\n' "$label"
  else
    printf 'FAIL %s log mount contains symlink or is not inspectable\n' "$label" >&2
    FAIL=1
  fi
  if docker exec "$container_id" sh -ec 'test ! -n "$(find "$1" -xdev -type f -perm -0002 -print -quit 2>/dev/null)"' sh "$path" >/dev/null 2>&1; then
    printf 'PASS %s log mount contains no world-writable file\n' "$label"
  else
    printf 'FAIL %s log mount contains world-writable file or is not inspectable\n' "$label" >&2
    FAIL=1
  fi
}

check_container_log_file() {
  local container_id="$1" path="$2" kind="$3" mode
  if [[ -z "$container_id" ]]; then
    printf 'FAIL log container missing for %s\n' "$path" >&2
    FAIL=1
    return
  fi
  if ! docker exec "$container_id" sh -ec 'test -f "$1" && test ! -L "$1"' sh "$path" >/dev/null 2>&1; then
    printf 'FAIL required log file %s missing or symlink inside mounted container path\n' "$path" >&2
    FAIL=1
    return
  fi
  mode="$(docker exec "$container_id" stat -c '%a' "$path" 2>/dev/null || true)"
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] || { printf 'FAIL cannot read mode for log file %s\n' "$path" >&2; FAIL=1; return; }
  if [[ "$kind" == app ]]; then
    if (( (8#$mode & 0007) == 0 )); then
      printf 'PASS app/audit log file %s exists via container mount and has no permissions for others (mode=%s)\n' "$path" "$mode"
    else
      printf 'FAIL app/audit log file %s exposes permissions to others mode=%s\n' "$path" "$mode" >&2
      FAIL=1
    fi
  elif (( (8#$mode & 0002) == 0 )); then
    printf 'PASS log file %s exists via container mount and is not world-writable (mode=%s)\n' "$path" "$mode"
  else
    printf 'FAIL log file %s is world-writable mode=%s\n' "$path" "$mode" >&2
    FAIL=1
  fi
}

# The host directories are intentionally not traversable by the deployment
# account. Inspect the actual bind-mounted trees/files from the containers that
# own those mounts instead of misclassifying EACCES on the host as "missing".
check_container_tree_safety "$APP_ID" /var/log/gomaf app
check_container_tree_safety "$CADDY_ID" /var/log/caddy caddy
check_container_log_file "$APP_ID" /var/log/gomaf/application.log app
check_container_log_file "$APP_ID" /var/log/gomaf/audit.log app

# A real HTTPS smoke request above should generate an access-log entry. Caddy
# opens the configured file itself; wait briefly to avoid filesystem flush races.
caddy_log_ready=0
for _ in $(seq 1 20); do
  if docker exec "$CADDY_ID" sh -ec 'test -f /var/log/caddy/access.log && test ! -L /var/log/caddy/access.log' >/dev/null 2>&1; then
    caddy_log_ready=1
    break
  fi
  sleep 1
done
if [[ "$caddy_log_ready" -eq 1 ]]; then
  check_container_log_file "$CADDY_ID" /var/log/caddy/access.log caddy
else
  printf 'FAIL required Caddy access log did not appear after HTTPS smoke test\n' >&2
  FAIL=1
fi

if [[ "$FAIL" -eq 0 ]]; then
  printf 'PRODUCTION AUDIT PASS\n'
else
  printf 'PRODUCTION AUDIT FAIL\n' >&2
  exit 1
fi
