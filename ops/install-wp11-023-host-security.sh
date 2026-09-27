#!/usr/bin/env bash
set -Eeuo pipefail

[[ $EUID -eq 0 ]] || { echo 'Run via sudo from opsadmin.' >&2; exit 1; }
[[ $# -eq 4 ]] || {
  echo 'Usage: sudo bash install-wp11-023-host-security.sh <candidate.zip> <bootstrap-predeploy-evidence.env> <opsadmin.pub> <mafia-deploy.pub>' >&2
  exit 2
}
ARCHIVE="$1"; EVIDENCE="$2"; OPSADMIN_KEY="$3"; DEPLOY_KEY="$4"
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SAFE_ZIP="$SOURCE_ROOT/ops/release/safe-zip.py"
ENV=/etc/gomaf/production.env
for f in "$ARCHIVE" "$EVIDENCE" "$OPSADMIN_KEY" "$DEPLOY_KEY"; do
  [[ -f "$f" && ! -L "$f" && -s "$f" ]] || { echo "Unsafe/missing input: $f" >&2; exit 1; }
done
for c in openssl python3 sha256sum ssh-keygen visudo docker; do command -v "$c" >/dev/null || { echo "$c missing" >&2; exit 1; }; done
[[ -f "$SAFE_ZIP" && ! -L "$SAFE_ZIP" ]] || { echo 'Safe ZIP extractor missing/unsafe' >&2; exit 1; }
[[ -f "$ENV" && ! -L "$ENV" ]] || { echo "Missing/unsafe $ENV" >&2; exit 1; }
id mafia >/dev/null 2>&1 || { echo 'Deployment user mafia is missing.' >&2; exit 1; }

ssh-keygen -l -f "$OPSADMIN_KEY" >/dev/null || { echo 'Invalid opsadmin SSH public key.' >&2; exit 1; }
ssh-keygen -l -f "$DEPLOY_KEY" >/dev/null || { echo 'Invalid mafia SSH public key.' >&2; exit 1; }
deploy_blob="$(awk 'NF>=2 {print $2; exit}' "$DEPLOY_KEY")"
[[ -n "$deploy_blob" ]] || { echo 'mafia deployment SSH key has no public-key blob.' >&2; exit 1; }
if awk 'NF>=2 {print $2}' "$OPSADMIN_KEY" | grep -Fxq "$deploy_blob"; then
  echo 'opsadmin and mafia must use distinct SSH keys.' >&2; exit 1
fi

TXN="$(mktemp -d /tmp/gomaf-host-security.XXXXXX)"
cleanup(){ rm -rf -- "$TXN"; }
trap cleanup EXIT INT TERM
mkdir -m 0700 "$TXN/extracted"
TOP="$(python3 "$SAFE_ZIP" extract "$ARCHIVE" "$TXN/extracted")"
CANDIDATE_ROOT="$TXN/extracted/$TOP"
[[ -d "$CANDIDATE_ROOT" && -f "$CANDIDATE_ROOT/RELEASE-SHA256SUMS.txt" && -f "$CANDIDATE_ROOT/RELEASE-CANDIDATE.env" ]] || { echo 'Candidate contents missing.' >&2; exit 1; }
(cd "$CANDIDATE_ROOT" && sha256sum -c RELEASE-SHA256SUMS.txt >/dev/null) || { echo 'Candidate internal checksum failure.' >&2; exit 1; }
bash "$CANDIDATE_ROOT/ops/normalize-source-permissions.sh" "$CANDIDATE_ROOT" >/dev/null
ARCHIVE_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
readarray -t META < <(python3 - "$CANDIDATE_ROOT/RELEASE-CANDIDATE.env" <<'PY'
import re,sys
from pathlib import Path
d={}
for line in Path(sys.argv[1]).read_text().splitlines():
    if line and not line.startswith('#'):
        k,v=line.split('=',1); d[k]=v
if d.get('FORMAT')!='1' or d.get('WORK_PACKAGE')!='WP11-023': raise SystemExit('wrong release metadata')
if not re.fullmatch(r'[1-9][0-9]{5,19}',d.get('RELEASE_SEQUENCE','')): raise SystemExit('bad sequence')
if not re.fullmatch(r'[0-9a-f]{12}',d.get('EXPECTED_CURRENT_SOURCE_HASH','')): raise SystemExit('bad baseline')
print(d['RELEASE_SEQUENCE']); print(d['EXPECTED_CURRENT_SOURCE_HASH'])
PY
)
SEQUENCE="${META[0]}"; EXPECTED="${META[1]}"; CURRENT="$(cat /srv/gomaf/.deploy/current_source_hash 2>/dev/null || true)"
[[ "$CURRENT" == "$EXPECTED" ]] || { echo "Production baseline changed: expected=$EXPECTED current=${CURRENT:-missing}" >&2; exit 1; }
grep -qx 'STATUS=PASS' "$EVIDENCE" || { echo 'Bootstrap PREDEPLOY status is not PASS.' >&2; exit 1; }
grep -qx 'WORK_PACKAGE=WP11-023' "$EVIDENCE" || { echo 'Wrong PREDEPLOY work package.' >&2; exit 1; }
grep -qx "ARCHIVE_SHA256=$ARCHIVE_SHA" "$EVIDENCE" || { echo 'PREDEPLOY evidence archive mismatch.' >&2; exit 1; }
grep -qx "RELEASE_SEQUENCE=$SEQUENCE" "$EVIDENCE" || { echo 'PREDEPLOY evidence sequence mismatch.' >&2; exit 1; }
grep -qx "EXPECTED_CURRENT_SOURCE_HASH=$EXPECTED" "$EVIDENCE" || { echo 'PREDEPLOY evidence baseline mismatch.' >&2; exit 1; }

# No host mutation happened above this line. The following transition is
# authorized only by a PASS bound to this exact candidate archive and baseline.
python3 - "$ENV" <<'PY'
import sys
from pathlib import Path
vals={}
for line in Path(sys.argv[1]).read_text('utf-8').splitlines():
    if '=' not in line or line.lstrip().startswith('#'): continue
    k,v=line.split('=',1); vals[k]=v.strip().strip("'\"")
pw=vals.get('APP_ADMIN_PASSWORD','')
if not (12 <= len(pw) <= 64) or len(pw.encode())>72 or pw.startswith('CHANGE_ME'):
    raise SystemExit('Existing APP_ADMIN_PASSWORD must be upgraded to 12-64 characters before host hardening.')
PY

# Preserve exact pre-transition config in root-only state.
install -d -m 0700 -o root -g root /var/lib/gomaf-release /var/lib/gomaf-release/approved
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
install -m 0600 -o root -g root "$ENV" "/var/lib/gomaf-release/production.env.pre-wp11-023-$stamp"
install -m 0600 -o root -g root "$EVIDENCE" "/var/lib/gomaf-release/bootstrap-predeploy-$stamp.env"

# Dedicated identities and keys. Password login stays disabled.
if ! id opsadmin >/dev/null 2>&1; then useradd -m -s /bin/bash -G sudo opsadmin; else usermod -aG sudo opsadmin; fi
passwd -l opsadmin >/dev/null 2>&1 || true; passwd -l mafia >/dev/null 2>&1 || true
install -d -m 0700 -o opsadmin -g opsadmin /home/opsadmin/.ssh
install -m 0600 -o opsadmin -g opsadmin "$OPSADMIN_KEY" /home/opsadmin/.ssh/authorized_keys
install -d -m 0700 -o mafia -g mafia /home/mafia/.ssh
install -m 0600 -o mafia -g mafia "$DEPLOY_KEY" /home/mafia/.ssh/authorized_keys

migration_secret="$(openssl rand -hex 32)"
export WP11023_MIGRATION_SECRET="$migration_secret"
python3 - "$ENV" <<'PY'
import os,sys,tempfile
from pathlib import Path
p=Path(sys.argv[1]); raw=p.read_text('utf-8').splitlines(); vals={}
for line in raw:
    if '=' in line and not line.lstrip().startswith('#'):
        k,v=line.split('=',1); vals[k]=v.strip().strip("'\"")
updates={}
if not vals.get('MIGRATION_DB_USER'): updates['MIGRATION_DB_USER']='mafia_migrator'
if not vals.get('MIGRATION_DB_PASSWORD'): updates['MIGRATION_DB_PASSWORD']=os.environ['WP11023_MIGRATION_SECRET']
check={**vals,**updates}
if check.get('MIGRATION_DB_USER')==check.get('APP_DB_USER'): raise SystemExit('Migration/runtime DB roles must differ')
mp=check.get('MIGRATION_DB_PASSWORD','')
if len(mp)<32 or any(c not in '0123456789abcdefABCDEF' for c in mp): raise SystemExit('Invalid migration DB password')
out=[]; seen=set()
for line in raw:
    if '=' in line and not line.lstrip().startswith('#'):
        k=line.split('=',1)[0]
        if k in updates: out.append(f'{k}={updates[k]}'); seen.add(k); continue
    out.append(line)
for k,v in updates.items():
    if k not in seen: out.append(f'{k}={v}')
fd,tmp=tempfile.mkstemp(prefix='.production.env.wp11023.',dir=str(p.parent),text=True)
try:
    with os.fdopen(fd,'w',encoding='utf-8') as f: f.write('\n'.join(out)+'\n')
    os.chmod(tmp,0o600); os.replace(tmp,p)
finally:
    try: os.unlink(tmp)
    except FileNotFoundError: pass
PY
unset WP11023_MIGRATION_SECRET migration_secret
chown root:root "$ENV"; chmod 0600 "$ENV"; chown root:root /etc/gomaf; chmod 0700 /etc/gomaf
rm -f /etc/gomaf/release-signing-public.pem

# Install only helper bytes extracted from the verified candidate archive.
install -d -m 0755 -o root -g root /usr/local/libexec
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/release/safe-zip.py" /usr/local/libexec/gomaf-safe-zip
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/root/gomaf-release-common" /usr/local/libexec/gomaf-release-common
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/root/gomaf-predeploy-release" /usr/local/sbin/gomaf-predeploy-release
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/root/gomaf-deploy-release" /usr/local/sbin/gomaf-deploy-release
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/set-admin-credentials.sh" /usr/local/sbin/gomaf-set-admin
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/root/gomaf-export-logs" /usr/local/sbin/gomaf-export-logs
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/root/gomaf-storage-maintenance" /usr/local/sbin/gomaf-storage-maintenance
install -m 0755 -o root -g root "$CANDIDATE_ROOT/ops/root/gomaf-v10-admin-switch" /usr/local/sbin/gomaf-v10-admin-switch

install -d -m 0700 -o mafia -g mafia /srv/gomaf-incoming
install -d -m 0755 -o root -g root /srv/gomaf
chown -R root:root /srv/gomaf
install -d -m 0755 -o root -g root /srv/gomaf-logs
install -d -m 0750 -o 10001 -g 10001 /srv/gomaf-logs/app
install -d -m 0750 -o root -g root /srv/gomaf-logs/caddy
install -d -m 0700 -o root -g root /srv/gomaf-log-exports /srv/gomaf-backups
# Reinstall the daily backup unit after production.env becomes root-only.
# The service intentionally runs as root because Docker daemon access is
# root-equivalent; do not grant docker group membership to the deployment user.
bash "$CANDIDATE_ROOT/ops/install-backup-timer.sh" >/dev/null
[[ ! -e /srv/gomaf/.deploy || -d /srv/gomaf/.deploy ]] || { echo 'Unsafe /srv/gomaf/.deploy' >&2; exit 1; }
if [[ -d /srv/gomaf/.deploy ]]; then chown -R root:root /srv/gomaf/.deploy; chmod 0700 /srv/gomaf/.deploy; fi

cat > /etc/sudoers.d/89-gomaf-opsadmin <<'SUDO'
opsadmin ALL=(ALL:ALL) NOPASSWD: ALL
SUDO
chmod 0440 /etc/sudoers.d/89-gomaf-opsadmin; visudo -cf /etc/sudoers.d/89-gomaf-opsadmin >/dev/null
cat > /etc/sudoers.d/90-gomaf-mafia <<'SUDO'
mafia ALL=(root) NOPASSWD: /usr/local/sbin/gomaf-predeploy-release ""
mafia ALL=(root) NOPASSWD: /usr/local/sbin/gomaf-deploy-release ""
SUDO
chmod 0440 /etc/sudoers.d/90-gomaf-mafia; visudo -cf /etc/sudoers.d/90-gomaf-mafia >/dev/null

for g in docker gomaf sudo; do
  if getent group "$g" >/dev/null 2>&1 && id -nG mafia | tr ' ' '\n' | grep -qx "$g"; then gpasswd -d mafia "$g" >/dev/null || true; fi
done
if id -nG mafia | tr ' ' '\n' | grep -Eq '^(docker|gomaf|sudo)$'; then echo 'Failed to remove privileged mafia groups.' >&2; exit 1; fi

cat > /etc/ssh/sshd_config.d/00-gomaf-hardening.conf <<'SSHD'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AuthenticationMethods publickey
MaxAuthTries 3
LoginGraceTime 30
X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
AllowStreamLocalForwarding no
PermitOpen none
PermitListen none
PermitTunnel no
AllowUsers opsadmin mafia
SSHD
rm -f /etc/ssh/sshd_config.d/60-gomaf-hardening.conf
sshd -t
ssh_effective="$(sshd -T)"
for expected in \
  'permitrootlogin no' \
  'passwordauthentication no' \
  'kbdinteractiveauthentication no' \
  'pubkeyauthentication yes' \
  'authenticationmethods publickey' \
  'maxauthtries 3' \
  'logingracetime 30' \
  'x11forwarding no' \
  'allowagentforwarding no' \
  'allowtcpforwarding no' \
  'allowstreamlocalforwarding no' \
  'permitopen none' \
  'permitlisten none' \
  'permittunnel no'; do
  printf '%s\n' "$ssh_effective" | grep -Fxq "$expected" || { echo "Effective sshd hardening mismatch: $expected" >&2; exit 1; }
done
printf '%s\n' "$ssh_effective" | grep -Fxq 'allowusers opsadmin' || { echo 'Effective sshd AllowUsers missing opsadmin' >&2; exit 1; }
printf '%s\n' "$ssh_effective" | grep -Fxq 'allowusers mafia' || { echo 'Effective sshd AllowUsers missing mafia' >&2; exit 1; }
systemctl reload ssh.service
[[ "$(stat -c '%U:%G:%a' "$ENV")" == 'root:root:600' ]] || { echo 'production.env permissions invalid' >&2; exit 1; }

# Install recurring operational visibility, AIDE/auditd/fail2ban policy and
# bounded validation guards from this checksum-verified candidate. Existing
# AIDE databases are intentionally preserved; the installer initializes a
# baseline only when no database exists on a fresh host.
bash "$CANDIDATE_ROOT/ops/install-ops-safety.sh" >/dev/null

# Old mafia processes still carry supplementary groups until they exit.
if pgrep -u mafia >/dev/null 2>&1; then pkill -KILL -u mafia >/dev/null 2>&1 || true; fi
printf 'WP11-023 HOST SECURITY INSTALL PASS\n'
printf 'candidate=%s sequence=%s baseline=%s\n' "$ARCHIVE_SHA" "$SEQUENCE" "$EXPECTED"
printf 'mafia groups: %s\n' "$(id -nG mafia)"
printf 'production.env: %s\n' "$(stat -c '%U:%G:%a' "$ENV")"
