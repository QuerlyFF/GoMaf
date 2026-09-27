#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Запусти: sudo bash ops/upgrade-server-v10.sh" >&2; exit 1; }
SOURCE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV=/etc/gomaf/production.env
STATE=/etc/gomaf/v10-v9-admin-username

. /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == "24.04" ]] || {
  echo "Ожидалась Ubuntu 24.04 LTS; обнаружено ${PRETTY_NAME:-unknown}." >&2
  exit 1
}
id mafia >/dev/null 2>&1 || { echo "Не найден deployment user mafia." >&2; exit 1; }
getent group gomaf >/dev/null 2>&1 || { echo "Не найдена группа gomaf." >&2; exit 1; }
[[ -f "$ENV" && ! -L "$ENV" ]] || { echo "Не найден безопасный обычный файл $ENV" >&2; exit 1; }
[[ ! -e "$STATE" && ! -L "$STATE" ]] || { echo "Обнаружен незавершённый/небезопасный v10 transition state: $STATE" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || { echo "Не найден Docker; невозможно проверить текущую v9 БД." >&2; exit 1; }

CURRENT_ADMIN="$(grep -m1 '^APP_ADMIN_USERNAME=' "$ENV" | cut -d= -f2- | tr -d '\r' || true)"
CURRENT_ADMIN="${CURRENT_ADMIN#\"}"; CURRENT_ADMIN="${CURRENT_ADMIN%\"}"
CURRENT_ADMIN="${CURRENT_ADMIN#\'}"; CURRENT_ADMIN="${CURRENT_ADMIN%\'}"
[[ -n "$CURRENT_ADMIN" ]] || { echo "В production.env отсутствует APP_ADMIN_USERNAME." >&2; exit 1; }

DB_ID="$(docker ps \
  --filter label=com.docker.compose.project=gomaf \
  --filter label=com.docker.compose.service=db \
  --format '{{.ID}}' | head -n 1)"
[[ -n "$DB_ID" ]] || { echo "Не найден запущенный production PostgreSQL контейнер gomaf/db." >&2; exit 1; }

ADMIN_STATE="$(docker exec "$DB_ID" sh -ec '
  PGPASSWORD="$POSTGRES_PASSWORD" psql -At -F "|" -v ON_ERROR_STOP=1 \
    -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -c "SELECT
      (SELECT count(*) FROM users WHERE role='"'"'ADMIN'"'"'),
      COALESCE((SELECT username FROM users WHERE role='"'"'ADMIN'"'"' ORDER BY id LIMIT 1), '"'"''"'"'),
      COALESCE((SELECT username_key FROM users WHERE role='"'"'ADMIN'"'"' ORDER BY id LIMIT 1), '"'"''"'"'),
      (SELECT count(*) FROM users WHERE username_key='"'"'admin'"'"' AND role <> '"'"'ADMIN'"'"'),
      (SELECT count(*) FROM flyway_schema_history WHERE version='"'"'3'"'"' AND success);"
')"
IFS='|' read -r ADMIN_COUNT DB_ADMIN_USERNAME DB_ADMIN_KEY ADMIN_NAME_BLOCKERS V10_ALREADY_MIGRATED <<< "$ADMIN_STATE"

[[ "$V10_ALREADY_MIGRATED" == 0 ]] || {
  echo "V10 HOST UPGRADE REFUSED: Flyway V3 уже применена; повторный v9->v10 host-upgrade запрещён." >&2
  exit 1
}
[[ "$ADMIN_COUNT" == 1 ]] || {
  echo "V10 HOST UPGRADE REFUSED: в текущей v9 БД должен существовать ровно один ADMIN; найдено: ${ADMIN_COUNT:-unknown}." >&2
  exit 1
}
[[ "$ADMIN_NAME_BLOCKERS" == 0 ]] || {
  echo "V10 HOST UPGRADE REFUSED: username admin уже принадлежит не-ADMIN аккаунту." >&2
  exit 1
}

CONFIG_MATCH="$(python3 - "$CURRENT_ADMIN" "$DB_ADMIN_KEY" <<'PYADMIN'
import sys
configured=sys.argv[1].strip().lower()
database_key=sys.argv[2].strip()
print('yes' if configured == database_key else 'no')
PYADMIN
)"
[[ "$CONFIG_MATCH" == yes ]] || {
  echo "V10 HOST UPGRADE REFUSED: APP_ADMIN_USERNAME не соответствует фактическому единственному ADMIN в v9 БД." >&2
  exit 1
}

# Keep the v9 ADMIN configuration unchanged while production v9 is still the
# active rollback target. deploy.sh switches only APP_ADMIN_USERNAME at the
# maintenance boundary, after v9 traffic is stopped, and restores it if a
# pre-public rollback restores the v9 database.
ENV_BACKUP="$ENV.v9-before-v10-$(date -u +%Y%m%dT%H%M%SZ).bak"
cp -a "$ENV" "$ENV_BACKUP"
if [[ "$CURRENT_ADMIN" != "admin" ]]; then
  STATE_TMP="$(mktemp /etc/gomaf/.v10-v9-admin-username.XXXXXX)"
  printf '%s\n' "$CURRENT_ADMIN" > "$STATE_TMP"
  chown root:gomaf "$STATE_TMP"
  chmod 0640 "$STATE_TMP"
  sync -f "$STATE_TMP"
  mv -T "$STATE_TMP" "$STATE"
  sync -d /etc/gomaf 2>/dev/null || true
  printf 'ADMIN transition: legacy username %s будет заменён на admin только в maintenance window.\n' "$CURRENT_ADMIN"
else
  # A canonical v9 ADMIN does not need a transition marker. Leaving one here
  # would make the completed v10 deployment look unfinished to production-audit.
  printf 'ADMIN transition: не требуется; v9 уже использует канонический username admin.\n'
fi
restore_env_on_failure() {
  local status=$?
  if [[ "$status" -ne 0 && -f "$ENV_BACKUP" ]]; then
    restore_tmp="$ENV.restore.$$"
    cp -a "$ENV_BACKUP" "$restore_tmp" 2>/dev/null || true
    chown root:gomaf "$restore_tmp" 2>/dev/null || true
    chmod 0640 "$restore_tmp" 2>/dev/null || true
    mv -fT "$restore_tmp" "$ENV" 2>/dev/null || true
    rm -f "$STATE" 2>/dev/null || true
    echo "V10 host upgrade failed; production.env restored from $ENV_BACKUP" >&2
  fi
  exit "$status"
}
trap restore_env_on_failure EXIT
printf 'V9 ADMIN preflight: id/name=%s, reserved admin blockers=0\n' "$DB_ADMIN_USERNAME"
printf 'production.env backup: %s\n' "$ENV_BACKUP"
python3 - "$ENV" <<'PY'
from pathlib import Path
import os
import sys
import tempfile
p=Path(sys.argv[1])
st=p.stat()
lines=p.read_text(encoding='utf-8').splitlines()
values={
    'BACKUP_MIN_KEEP':'3',
    'BACKUP_MIN_FREE_MB':'2048',
    'BACKUP_TARGET_FREE_MB':'3072',
    'LOG_EXPORT_RETENTION_DAYS':'14',
}
seen=set()
out=[]
for line in lines:
    if '=' in line and not line.lstrip().startswith('#'):
        key=line.split('=',1)[0]
        if key in values:
            out.append(f'{key}={values[key]}')
            seen.add(key)
            continue
    out.append(line)
for key,value in values.items():
    if key not in seen:
        out.append(f'{key}={value}')
fd,tmp=tempfile.mkstemp(prefix='.production.env.', dir=str(p.parent), text=True)
try:
    with os.fdopen(fd,'w',encoding='utf-8',newline='\n') as h:
        h.write('\n'.join(out)+'\n')
        h.flush(); os.fsync(h.fileno())
    os.chown(tmp, st.st_uid, st.st_gid)
    os.chmod(tmp, 0o640)
    os.replace(tmp, p)
    dfd=os.open(p.parent, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(dfd)
    finally: os.close(dfd)
finally:
    try: os.unlink(tmp)
    except FileNotFoundError: pass
PY

# Install/update the common v10 host support only after the live-v9 preflight
# and atomic production.env update above have succeeded.
bash "$SOURCE_ROOT/ops/install-v10-host-support.sh"
trap - EXIT
printf 'V10 HOST UPGRADE PASS: v9 ADMIN config сохранён до maintenance boundary deploy.sh\n'
printf 'APP logs: /srv/gomaf-logs/app\nCaddy logs: /srv/gomaf-logs/caddy\nExports: /srv/gomaf-log-exports\n'
