#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v rsync >/dev/null || { echo "Нужен rsync" >&2; exit 1; }
command -v ssh >/dev/null || { echo "Нужен ssh" >&2; exit 1; }
ssh -G mafia >/dev/null 2>&1 || { echo "Сначала выполни ./ops/setup-client-ssh-alias.sh" >&2; exit 1; }

rsync -az --delete \
  --exclude '.deploy/' \
  --exclude '.audit/' \
  --exclude 'backend/target/' \
  --exclude '.git/' \
  --exclude '*.zip' \
  --exclude 'production.env' \
  --exclude '.env' \
  --exclude '.env.*' \
  "$ROOT/" mafia:/srv/gomaf/
ssh mafia 'cd /srv/gomaf && ./ops/deploy.sh'
