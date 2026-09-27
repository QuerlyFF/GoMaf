#!/usr/bin/env bash
set -Eeuo pipefail

BOOTSTRAP_BASENAME='@@BOOTSTRAP_BASENAME@@'
BOOTSTRAP_ARCHIVE="${BOOTSTRAP_BASENAME}.zip"
BOOTSTRAP_SHA_FILE="${BOOTSTRAP_ARCHIVE}.sha256"
MAFIA_SSH="${GOMAF_MAFIA_SSH:-mafia}"
OPSADMIN_SSH="${GOMAF_OPSADMIN_SSH:-opsadmin}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/gomaf"
MAFIA_KEY="$STATE_DIR/mafia-deploy-ed25519"
RUN_ID="wp11-023-$(date -u +%Y%m%dT%H%M%SZ)-$$"
REMOTE="/tmp/gomaf-$RUN_ID"
LOCAL_TMP="$(mktemp -d)"
RESULT_DIR="$SCRIPT_DIR/WP11-023-RESULT-$(date -u +%Y%m%dT%H%M%SZ)"
HARDENED=0

say(){ printf '[gomaf-client] %s\n' "$*"; }
fail(){ printf '[gomaf-client] ERROR: %s\n' "$*" >&2; exit 1; }
need(){ command -v "$1" >/dev/null 2>&1 || fail "Не найдена команда: $1"; }
ssh_opts=(-o BatchMode=yes -o ControlMaster=no -o ConnectTimeout=7 -o ConnectionAttempts=1 -o ServerAliveInterval=10 -o ServerAliveCountMax=6)
ssh_exec(){ local h="$1"; shift; ssh "${ssh_opts[@]}" "$h" "$@" </dev/null; }
wait_ssh(){
  local h="$1" limit="${2:-180}" start="$(date +%s)"
  while ! ssh_exec "$h" true >/dev/null 2>&1; do
    (( $(date +%s)-start < limit )) || return 1
    sleep 5
  done
}
upload_alias(){
  local h="$1" src="$2" dst="$3" local_sha remote_sha
  local_sha="$(sha256sum "$src" | awk '{print $1}')"
  ssh "${ssh_opts[@]}" "$h" "umask 077; cat > '$dst.part' && mv -f '$dst.part' '$dst'" < "$src"
  remote_sha="$(ssh_exec "$h" "sha256sum '$dst'" | awk '{print $1}')"
  [[ "$remote_sha" == "$local_sha" ]] || fail "SHA mismatch after upload: $dst"
}
download_alias(){
  local h="$1" src="$2" dst="$3" remote_sha local_sha
  remote_sha="$(ssh_exec "$h" "sha256sum '$src'" | awk '{print $1}')"
  ssh "${ssh_opts[@]}" "$h" "cat '$src'" > "$dst"
  local_sha="$(sha256sum "$dst" | awk '{print $1}')"
  [[ "$local_sha" == "$remote_sha" ]] || fail "SHA mismatch after download: $src"
}
verify_evidence_archive(){
  local archive="$1" kind="$2"
  python3 - "$archive" "$kind" "$CANDIDATE_SHA" "$SEQUENCE" "$CURRENT_HASH" <<'PYVERIFYEVIDENCE'
import hashlib, io, re, sys, tarfile
from pathlib import PurePosixPath

archive, kind, candidate_sha, sequence, baseline = sys.argv[1:]
manifest_name = 'FAILURE-SHA256SUMS.txt' if kind == 'failure' else 'SHA256SUMS.txt'
env_name = 'bootstrap-predeploy-evidence.env' if kind == 'bootstrap' else 'predeploy-evidence.env'
max_files = 6000
max_total = 256 * 1024 * 1024

with tarfile.open(archive, 'r:gz') as tf:
    members = tf.getmembers()
    if not members or len(members) > max_files:
        raise SystemExit(f'unsafe evidence member count: {len(members)}')
    total = 0
    files = {}
    for m in members:
        pp = PurePosixPath(m.name)
        if pp.is_absolute() or not m.name or any(x in ('', '.', '..') for x in pp.parts):
            raise SystemExit(f'unsafe evidence path: {m.name!r}')
        if m.issym() or m.islnk() or m.isdev():
            raise SystemExit(f'unsafe evidence member type: {m.name!r}')
        if m.isfile():
            total += m.size
            if total > max_total:
                raise SystemExit('evidence archive too large')
            if m.name in files:
                raise SystemExit(f'duplicate evidence member: {m.name}')
            files[m.name] = m
    if manifest_name not in files:
        raise SystemExit(f'missing {manifest_name}')

    def read(name):
        m = files.get(name)
        if m is None:
            raise SystemExit(f'missing evidence file: {name}')
        f = tf.extractfile(m)
        if f is None:
            raise SystemExit(f'cannot read evidence file: {name}')
        return f.read()

    manifest = read(manifest_name).decode('utf-8', 'strict').splitlines()
    listed = set()
    line_re = re.compile(r'^([0-9a-f]{64})  (.+)$')
    for line in manifest:
        mo = line_re.fullmatch(line)
        if not mo:
            raise SystemExit(f'invalid evidence manifest line: {line!r}')
        want, name = mo.groups()
        pp = PurePosixPath(name)
        if pp.is_absolute() or any(x in ('', '.', '..') for x in pp.parts) or name == manifest_name:
            raise SystemExit(f'unsafe manifest path: {name!r}')
        if name in listed:
            raise SystemExit(f'duplicate manifest path: {name}')
        data = read(name)
        got = hashlib.sha256(data).hexdigest()
        if got != want:
            raise SystemExit(f'evidence hash mismatch: {name}')
        listed.add(name)

    actual = set(files) - {manifest_name}
    if listed != actual:
        missing = sorted(actual-listed)
        extra = sorted(listed-actual)
        raise SystemExit(f'evidence manifest coverage mismatch missing={missing} extra={extra}')

    env = {}
    for line in read(env_name).decode('utf-8','strict').splitlines():
        if '=' in line and not line.startswith('#'):
            k,v=line.split('=',1); env[k]=v
    expected = {
        'STATUS':'PASS',
        'WORK_PACKAGE':'WP11-023',
        'ARCHIVE_SHA256':candidate_sha,
        'RELEASE_SEQUENCE':sequence,
        'EXPECTED_CURRENT_SOURCE_HASH':baseline,
    }
    for k,v in expected.items():
        if env.get(k) != v:
            raise SystemExit(f'evidence binding mismatch {k}: {env.get(k)!r} != {v!r}')

    if kind == 'deploy':
        deploy = read('deploy.log').decode('utf-8','replace')
        audit = read('production-audit.log').decode('utf-8','replace')
        if '[gomaf] DEPLOY PASS:' not in deploy:
            raise SystemExit('deployment evidence lacks DEPLOY PASS marker')
        if 'PRODUCTION AUDIT PASS' not in {x.strip() for x in audit.splitlines()}:
            raise SystemExit('deployment evidence lacks PRODUCTION AUDIT PASS marker')
    elif kind == 'failure':
        stage = read('failure-stage.txt').decode('utf-8','strict').strip()
        if stage == 'post-deploy-audit':
            for name in ('deploy.log','production-audit.log','automatic-rollback.log','source-restore.log'):
                read(name)
        elif stage == 'deploy-sh':
            read('deploy.log'); read('source-restore.log')
        else:
            raise SystemExit(f'unknown deployment failure stage: {stage!r}')

print(f'EVIDENCE_VERIFY_PASS kind={kind} archive={archive}')
PYVERIFYEVIDENCE
}

wait_public_health(){
  local limit="${1:-180}" start="$(date +%s)"
  while ! curl -fsS --max-time 8 https://gomaf.online/healthz >/dev/null 2>&1; do
    (( $(date +%s)-start < limit )) || return 1
    sleep 3
  done
}
install_mafia_managed_alias(){
  local ssh_dir="$HOME/.ssh" config="$HOME/.ssh/config" begin='# BEGIN GOMAF MANAGED BLOCK' end='# END GOMAF MANAGED BLOCK' tmp_cfg
  mkdir -p "$ssh_dir"; chmod 700 "$ssh_dir"; touch "$config"; chmod 600 "$config"
  cp -a "$config" "$config.bak.$(date +%Y%m%d%H%M%S)"
  tmp_cfg="$(mktemp)"
  awk -v b="$begin" -v e="$end" '$0==b{skip=1;next}$0==e{skip=0;next}!skip{print}' "$config" > "$tmp_cfg"
  cat >> "$tmp_cfg" <<CFG

$begin
Host mafia
    HostName $MAFIA_HOST
    User mafia
    Port $MAFIA_PORT
    IdentityFile $MAFIA_KEY
    IdentitiesOnly yes
    ServerAliveInterval 30
    ServerAliveCountMax 3
    TCPKeepAlive yes
    UpdateHostKeys yes
$end
CFG
  mv "$tmp_cfg" "$config"; chmod 600 "$config"
  ssh -G mafia >/dev/null || fail 'Локальный managed mafia SSH alias после hardening некорректен.'
}

cleanup(){
  rm -rf -- "$LOCAL_TMP" 2>/dev/null || true
  if (( HARDENED == 1 )); then ssh_exec "$OPSADMIN_SSH" "sudo -n rm -rf '$REMOTE'" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

for c in ssh ssh-keygen sha256sum curl awk python3; do need "$c"; done
cd "$SCRIPT_DIR"
[[ -f "$BOOTSTRAP_ARCHIVE" && -f "$BOOTSTRAP_SHA_FILE" ]] || fail 'Рядом со скриптом нет bootstrap ZIP/SHA.'
sha256sum -c "$BOOTSTRAP_SHA_FILE"
wait_ssh "$OPSADMIN_SSH" 180 || fail "SSH $OPSADMIN_SSH недоступен."
PUBLIC_HEALTH_READY=0
if wait_public_health 6; then
  PUBLIC_HEALTH_READY=1
  say 'Текущий production healthz ready.'
else
  say 'Public healthz не ready; перед продолжением будет обязательная read-only проверка intentional production freeze.'
fi

# Resolve connection coordinates without requiring the legacy mafia credential to
# work. This makes the one-shot runner restartable after a previous attempt
# already rotated mafia authorized_keys but failed later in PREDEPLOY/deploy.
MAFIA_HOST="$(ssh -G "$MAFIA_SSH" | awk '$1=="hostname"{print $2;exit}')"
MAFIA_PORT="$(ssh -G "$MAFIA_SSH" | awk '$1=="port"{print $2;exit}')"
[[ -n "$MAFIA_HOST" && "$MAFIA_PORT" =~ ^[0-9]+$ ]] || fail 'Не удалось определить SSH host/port mafia.'

CURRENT_HASH=''
if ssh_exec "$MAFIA_SSH" true >/dev/null 2>&1; then
  CURRENT_HASH="$(ssh_exec "$MAFIA_SSH" 'cat /srv/gomaf/.deploy/current_source_hash 2>/dev/null || true')"
elif [[ -f "$MAFIA_KEY" ]] && ssh "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$MAFIA_KEY" -p "$MAFIA_PORT" "mafia@$MAFIA_HOST" true </dev/null >/dev/null 2>&1; then
  say 'Legacy mafia alias уже не действует; использую сохранённый restricted deployment key от предыдущего hardening attempt.'
  CURRENT_HASH="$(ssh "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$MAFIA_KEY" -p "$MAFIA_PORT" "mafia@$MAFIA_HOST" 'cat /srv/gomaf/.deploy/current_source_hash 2>/dev/null || true' </dev/null)"
else
  say 'mafia SSH credential недоступен; читаю baseline через административный opsadmin recovery path.'
  CURRENT_HASH="$(ssh_exec "$OPSADMIN_SSH" 'sudo -n cat /srv/gomaf/.deploy/current_source_hash 2>/dev/null || true')"
fi
[[ "$CURRENT_HASH" =~ ^[0-9a-f]{12}$ ]] || fail "Не удалось получить current_source_hash: $CURRENT_HASH"
say "Текущий production source hash: $CURRENT_HASH"

install -d -m 0700 "$STATE_DIR"
if [[ ! -f "$MAFIA_KEY" ]]; then
  say 'Создаю отдельный ограниченный SSH key для deployment identity mafia.'
  ssh-keygen -q -t ed25519 -a 100 -N '' -C 'gomaf-mafia-deploy' -f "$MAFIA_KEY"
fi
chmod 0600 "$MAFIA_KEY"; chmod 0644 "$MAFIA_KEY.pub"

# Preserve the exact currently-authorized opsadmin keys; host hardening will not
# replace the admin trust root with a newly generated unencrypted key.
ssh_exec "$OPSADMIN_SSH" 'cat ~/.ssh/authorized_keys' > "$LOCAL_TMP/opsadmin-authorized_keys"
[[ -s "$LOCAL_TMP/opsadmin-authorized_keys" ]] || fail 'opsadmin authorized_keys пуст.'
ssh-keygen -l -f "$LOCAL_TMP/opsadmin-authorized_keys" >/dev/null || fail 'Некорректный opsadmin authorized_keys.'
MAFIA_BLOB="$(awk 'NF>=2 {print $2;exit}' "$MAFIA_KEY.pub")"
if awk 'NF>=2{print $2}' "$LOCAL_TMP/opsadmin-authorized_keys" | grep -Fxq "$MAFIA_BLOB"; then fail 'Новый mafia key совпадает с opsadmin key.'; fi

ssh_exec "$OPSADMIN_SSH" "rm -rf '$REMOTE'; install -d -m 0700 '$REMOTE'"
upload_alias "$OPSADMIN_SSH" "$BOOTSTRAP_ARCHIVE" "$REMOTE/$BOOTSTRAP_ARCHIVE"
upload_alias "$OPSADMIN_SSH" "$BOOTSTRAP_SHA_FILE" "$REMOTE/$BOOTSTRAP_SHA_FILE"
ssh_exec "$OPSADMIN_SSH" "cd '$REMOTE'; sha256sum -c '$BOOTSTRAP_SHA_FILE'; mkdir source; python3 -m zipfile -e '$BOOTSTRAP_ARCHIVE' source; test \"\$(find source -mindepth 1 -maxdepth 1 -type d | wc -l)\" -eq 1"
REMOTE_SRC="$(ssh_exec "$OPSADMIN_SSH" "find '$REMOTE/source' -mindepth 1 -maxdepth 1 -type d -print -quit")"
[[ "$REMOTE_SRC" == "$REMOTE/source/"* ]] || fail 'Некорректный remote source root.'
if (( PUBLIC_HEALTH_READY == 0 )); then
  say 'Проверяю, что production действительно полностью остановлен, а не находится в неизвестном partial-failure состоянии.'
  ssh -tt "${ssh_opts[@]}" "$OPSADMIN_SSH" "sudo bash '$REMOTE_SRC/ops/verify-production-freeze.sh'" \
    || fail 'Public healthz недоступен и intentional production freeze не подтверждён. Никаких изменений не выполнено.'
  say 'Intentional production freeze подтверждён. Продолжаю изолированный PREDEPLOY без запуска production.'
fi

say 'На VPS выполняю online source preparation. Production ещё не меняется.'
ssh -tt "${ssh_opts[@]}" "$OPSADMIN_SSH" "sudo bash '$REMOTE_SRC/ops/wp11-023-online-prepare.sh'" \
  || fail 'Online preparation завершилась FAIL.'

SEQUENCE="$(date -u +%Y%m%d%H%M%S)"
REMOTE_CANDIDATE="$REMOTE/candidate.zip"
say "Собираю финальный candidate sequence=$SEQUENCE baseline=$CURRENT_HASH."
ssh -tt "${ssh_opts[@]}" "$OPSADMIN_SSH" \
  "sudo bash '$REMOTE_SRC/ops/release/package-candidate.sh' '$REMOTE_CANDIDATE' '$SEQUENCE' '$CURRENT_HASH' && sudo chown \$(id -un):\$(id -gn) '$REMOTE_CANDIDATE'" \
  || fail 'Candidate packaging завершился FAIL.'

download_alias "$OPSADMIN_SSH" "$REMOTE_CANDIDATE" "$LOCAL_TMP/candidate.zip"
CANDIDATE_SHA="$(sha256sum "$LOCAL_TMP/candidate.zip" | awk '{print $1}')"
upload_alias "$OPSADMIN_SSH" "$LOCAL_TMP/opsadmin-authorized_keys" "$REMOTE/opsadmin-authorized_keys"
upload_alias "$OPSADMIN_SSH" "$MAFIA_KEY.pub" "$REMOTE/mafia-deploy.pub"

say 'BOOTSTRAP PREDEPLOY: полный build/test/migration rehearsal/SBOM/vulnerability/E2E без rollout.'
ssh -tt "${ssh_opts[@]}" "$OPSADMIN_SSH" \
  "sudo bash '$REMOTE_SRC/ops/bootstrap-wp11-023-predeploy.sh' '$REMOTE_CANDIDATE' '$REMOTE/bootstrap-evidence'" \
  || fail 'Bootstrap PREDEPLOY FAIL. Production не менялся.'
mkdir -p "$RESULT_DIR"
download_alias "$OPSADMIN_SSH" "$REMOTE/bootstrap-evidence/bootstrap-predeploy-evidence.tar.gz" "$RESULT_DIR/bootstrap-predeploy-evidence.tar.gz"
verify_evidence_archive "$RESULT_DIR/bootstrap-predeploy-evidence.tar.gz" bootstrap || fail 'Bootstrap PREDEPLOY evidence self-verification failed.'

# Only now, after bootstrap PREDEPLOY PASS, production config may be hardened.
say 'Проверяю новую 12+ ADMIN password policy. Если старый пароль короткий, сейчас будет один скрытый интерактивный запрос.'
ssh -tt "${ssh_opts[@]}" "$OPSADMIN_SSH" "sudo bash '$REMOTE_SRC/ops/ensure-admin-password-policy.sh'" \
  || fail 'ADMIN password policy transition failed.'

say 'Устанавливаю candidate-bound host-security transition, привязанный к bootstrap PASS.'
ssh -tt "${ssh_opts[@]}" "$OPSADMIN_SSH" \
  "sudo bash '$REMOTE_SRC/ops/install-wp11-023-host-security.sh' '$REMOTE_CANDIDATE' '$REMOTE/bootstrap-evidence/bootstrap-predeploy-evidence.env' '$REMOTE/opsadmin-authorized_keys' '$REMOTE/mafia-deploy.pub'" \
  || fail 'Host-security transition failed after PREDEPLOY PASS.'
HARDENED=1

new_mafia_ssh(){ ssh "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$MAFIA_KEY" -p "$MAFIA_PORT" "mafia@$MAFIA_HOST" "$@" </dev/null; }
new_mafia_upload(){
  local src="$1" dst="$2" l r
  l="$(sha256sum "$src" | awk '{print $1}')"
  ssh "${ssh_opts[@]}" -o IdentitiesOnly=yes -i "$MAFIA_KEY" -p "$MAFIA_PORT" "mafia@$MAFIA_HOST" "umask 077; cat > '$dst.part' && mv -f '$dst.part' '$dst'" < "$src"
  r="$(new_mafia_ssh "sha256sum '$dst'" | awk '{print $1}')"; [[ "$l" == "$r" ]] || fail "New mafia upload SHA mismatch: $dst"
}
new_mafia_download(){
  local src="$1" dst="$2" r l
  r="$(new_mafia_ssh "sha256sum '$src'" | awk '{print $1}')"
  new_mafia_ssh "cat '$src'" > "$dst"; l="$(sha256sum "$dst" | awk '{print $1}')"; [[ "$l" == "$r" ]] || fail "New mafia download SHA mismatch: $src"
}
for _ in $(seq 1 36); do new_mafia_ssh true >/dev/null 2>&1 && break; sleep 5; done
new_mafia_ssh true >/dev/null 2>&1 || fail 'Новый ограниченный mafia SSH key не работает после hardening.'
# Persist the rotated credential immediately. If any later gate fails, the next
# one-shot run can still reach the restricted deployment identity.
install_mafia_managed_alias

say 'Передаю тот же verified candidate в root-owned release workflow.'
new_mafia_upload "$LOCAL_TMP/candidate.zip" /srv/gomaf-incoming/candidate.zip
say 'FINAL PREDEPLOY: повторная проверка тем же candidate после host-hardening.'
new_mafia_ssh 'sudo -n /usr/local/sbin/gomaf-predeploy-release' || fail 'Final PREDEPLOY FAIL. Production rollout не начат.'
new_mafia_download /srv/gomaf-incoming/final-predeploy-evidence.tar.gz "$RESULT_DIR/final-predeploy-evidence.tar.gz"
verify_evidence_archive "$RESULT_DIR/final-predeploy-evidence.tar.gz" final || fail 'Final PREDEPLOY evidence self-verification failed.'

DEPLOY_APPROVAL_PHRASE='РАЗРЕШАЮ ЗАПУСК PRODUCTION'
[[ -t 0 ]] || fail 'Production deploy требует интерактивного подтверждения после FINAL PREDEPLOY; stdin не является TTY. Rollout не начат.'
printf 'FINAL PREDEPLOY PASS. Для запуска production введите точно: %s\n> ' "$DEPLOY_APPROVAL_PHRASE" >&2
deploy_approval=''
if ! IFS= read -r deploy_approval; then
  fail 'Явное подтверждение запуска production не получено. Rollout не начат.'
fi
[[ "$deploy_approval" == "$DEPLOY_APPROVAL_PHRASE" ]] || fail 'Фраза подтверждения не совпала. Rollout не начат.'
unset deploy_approval
say 'Явное интерактивное подтверждение production rollout получено после FINAL PREDEPLOY.'
set +e
new_mafia_ssh 'sudo -n /usr/local/sbin/gomaf-deploy-release'
deploy_rc=$?
set -e
if (( deploy_rc != 0 )); then
  if new_mafia_ssh 'test -f /srv/gomaf-incoming/deployment-failure-evidence.tar.gz' >/dev/null 2>&1; then
    if new_mafia_download /srv/gomaf-incoming/deployment-failure-evidence.tar.gz "$RESULT_DIR/deployment-failure-evidence.tar.gz"; then
      verify_evidence_archive "$RESULT_DIR/deployment-failure-evidence.tar.gz" failure || fail 'Deployment failure evidence self-verification failed.'
    fi
  fi
  fail 'Production deploy/production audit завершился FAIL; server-side automatic rollback был запрошен. См. failure evidence, если он был создан.'
fi
new_mafia_download /srv/gomaf-incoming/deployment-evidence.tar.gz "$RESULT_DIR/deployment-evidence.tar.gz"
verify_evidence_archive "$RESULT_DIR/deployment-evidence.tar.gz" deploy || fail 'Deployment evidence self-verification failed.'
wait_public_health 180 || fail 'После server-side DEPLOY PASS внешний healthz не стал ready.'

cp "$LOCAL_TMP/candidate.zip" "$RESULT_DIR/"
printf '%s  candidate.zip\n' "$CANDIDATE_SHA" > "$RESULT_DIR/candidate.zip.sha256"
(cd "$RESULT_DIR" && sha256sum candidate.zip bootstrap-predeploy-evidence.tar.gz final-predeploy-evidence.tar.gz deployment-evidence.tar.gz > CONTENTS-SHA256SUMS.txt)

ssh_exec "$OPSADMIN_SSH" "sudo -n rm -rf '$REMOTE'" >/dev/null 2>&1 || true
say "UPDATE PASS. Candidate SHA-256: $CANDIDATE_SHA"
say "Evidence сохранены локально: $RESULT_DIR"
