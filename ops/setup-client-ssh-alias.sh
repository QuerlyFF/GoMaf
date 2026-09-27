#!/usr/bin/env bash
set -Eeuo pipefail

SSH_DIR="$HOME/.ssh"
CONFIG="$SSH_DIR/config"
BEGIN="# BEGIN GOMAF MANAGED BLOCK"
END="# END GOMAF MANAGED BLOCK"
mkdir -p "$SSH_DIR"
chmod 700 "$SSH_DIR"
touch "$CONFIG"
chmod 600 "$CONFIG"
cp -a "$CONFIG" "$CONFIG.bak.$(date +%Y%m%d%H%M%S)"

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
awk -v begin="$BEGIN" -v end="$END" '
  $0 == begin {skip=1; next}
  $0 == end {skip=0; next}
  !skip {print}
' "$CONFIG" > "$TMP"

cat >> "$TMP" <<EOF2

$BEGIN
Host mafia
    HostName gomaf.online
    User mafia
    ServerAliveInterval 30
    ServerAliveCountMax 3
    TCPKeepAlive yes
    UpdateHostKeys yes
$END
EOF2
mv "$TMP" "$CONFIG"
chmod 600 "$CONFIG"
ssh -G mafia >/dev/null
printf 'SSH alias создан. После настройки DNS и сервера вход: ssh mafia\n'
