#!/usr/bin/env bash
set -Eeuo pipefail

[[ $EUID -eq 0 ]] || { echo "Запусти через sudo." >&2; exit 1; }
[[ $# -eq 2 ]] || {
  cat >&2 <<'USAGE'
WP11-023 fresh-server setup requires two distinct SSH public keys:
  sudo bash ops/setup-server.sh <opsadmin.pub> <mafia-deploy.pub>

Release signing is not used. Candidate trust is enforced later by SHA-256/internal manifest, baseline binding, anti-replay sequence and PREDEPLOY evidence.
USAGE
  exit 2
}
SOURCE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OPSADMIN_KEY="$1"
DEPLOY_KEY="$2"
TARGET_ROOT=/srv/gomaf

for f in "$OPSADMIN_KEY" "$DEPLOY_KEY"; do
  [[ -f "$f" && ! -L "$f" && -s "$f" ]] || { echo "Unsafe/missing key file: $f" >&2; exit 1; }
done
ssh-keygen -l -f "$OPSADMIN_KEY" >/dev/null || { echo "Invalid opsadmin SSH key." >&2; exit 1; }
ssh-keygen -l -f "$DEPLOY_KEY" >/dev/null || { echo "Invalid mafia SSH key." >&2; exit 1; }
[[ "$(awk 'NF>=2 {print $2; exit}' "$OPSADMIN_KEY")" != "$(awk 'NF>=2 {print $2; exit}' "$DEPLOY_KEY")" ]] || {
  echo "opsadmin and mafia must use distinct SSH keys." >&2; exit 1;
}

. /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == "24.04" ]] || {
  echo "Expected Ubuntu 24.04 LTS; found ${PRETTY_NAME:-unknown}." >&2; exit 1;
}
MEM_MB=$(( $(awk '/MemTotal:/ {print $2}' /proc/meminfo) / 1024 ))
DISK_MB=$(df -Pm / | awk 'NR==2 {print $4}')
(( MEM_MB >= 900 )) || { echo "At least about 1 GB RAM is required; found ${MEM_MB} MB." >&2; exit 1; }
(( DISK_MB >= 6144 )) || { echo "At least 6 GB free on / is required; found ${DISK_MB} MB." >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl gnupg openssl rsync ufw fail2ban unattended-upgrades python3 sudo auditd aide

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  apt-get remove -y docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc 2>/dev/null || true
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  cat > /etc/apt/sources.list.d/docker.sources <<DOCKER
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
DOCKER
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
install -d -m 0755 /etc/docker
python3 - <<'PY'
import json
from pathlib import Path
p=Path('/etc/docker/daemon.json')
data=json.loads(p.read_text()) if p.exists() and p.read_text().strip() else {}
data['live-restore']=True
data['log-driver']='local'
p.write_text(json.dumps(data, indent=2)+'\n')
PY
systemctl enable docker.service containerd.service
systemctl restart docker

# Fresh setup creates the deployment identity without any root-equivalent or
# secret-reading supplementary group. Host administration is a separate key.
if ! id mafia >/dev/null 2>&1; then useradd -m -s /bin/bash mafia; fi
passwd -l mafia >/dev/null 2>&1 || true
for g in docker gomaf; do
  if id -nG mafia | tr ' ' '\n' | grep -qx "$g"; then gpasswd -d mafia "$g" >/dev/null; fi
done

install -d -m 0755 -o root -g root "$TARGET_ROOT"
rsync -a --delete \
  --exclude '.deploy/' --exclude '.audit/' --exclude 'backups/' --exclude '*.zip' \
  --exclude '.env' --exclude '.env.*' \
  "$SOURCE_ROOT/" "$TARGET_ROOT/"
chown -R root:root "$TARGET_ROOT"

if [[ ! -e /etc/gomaf/production.env ]]; then
  GOMAF_ENV_FILE=/etc/gomaf/production.env bash "$TARGET_ROOT/ops/init-production-config.sh"
fi
chown root:root /etc/gomaf /etc/gomaf/production.env
chmod 0700 /etc/gomaf
chmod 0600 /etc/gomaf/production.env

# Fresh configuration contains a deliberate placeholder. Set the only
# canonical ADMIN password before the host-security installer performs its
# fail-closed policy validation. This prompt is local to the administrative
# terminal; the password is never printed or copied to source.
install -m 0755 -o root -g root "$TARGET_ROOT/ops/set-admin-credentials.sh" /usr/local/sbin/gomaf-set-admin
if grep -q "^APP_ADMIN_PASSWORD='CHANGE_ME_" /etc/gomaf/production.env; then
  /usr/local/sbin/gomaf-set-admin
fi

# Candidate-bound host-security helpers are installed only after a concrete
# candidate passes bootstrap PREDEPLOY. Fresh setup must not fabricate PASS evidence.

SSH_PORT="$(sshd -T | awk '$1=="port" {print $2; exit}')"
ufw default deny incoming
ufw default allow outgoing
ufw allow "$SSH_PORT/tcp"
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable
cat > /etc/fail2ban/jail.d/gomaf-sshd.local <<F2B
[sshd]
enabled = true
port = $SSH_PORT
maxretry = 5
findtime = 10m
bantime = 1h
F2B
systemctl enable --now fail2ban
systemctl restart fail2ban
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT
systemctl enable --now unattended-upgrades.service 2>/dev/null || true

for port in 80 443; do
  if ss -ltn "sport = :$port" | tail -n +2 | grep -q .; then
    echo "WARNING: TCP port $port is already occupied; deployment must remain blocked until resolved." >&2
  fi
done

printf '\nWP11-023 FRESH HOST SETUP PASS\n'
printf 'Source root: %s (root-owned)\n' "$TARGET_ROOT"
printf 'Production config: /etc/gomaf/production.env (root:root 0600)\n'
printf 'Host admin: opsadmin; deployment identity: mafia\n'
printf 'mafia groups: %s\n' "$(id -nG mafia)"
printf 'Deploy path: candidate -> bootstrap PREDEPLOY -> candidate-bound host security -> final PREDEPLOY -> deploy\n'
printf 'Release signing is disabled by owner policy; SHA-256/internal manifest, baseline and anti-replay checks remain mandatory.\n'
