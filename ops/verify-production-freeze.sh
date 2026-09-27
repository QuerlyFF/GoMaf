#!/usr/bin/env bash
set -Eeuo pipefail

[[ $EUID -eq 0 ]] || { echo 'Run as root.' >&2; exit 1; }
for c in docker ss; do
  command -v "$c" >/dev/null 2>&1 || { echo "$c missing" >&2; exit 1; }
done

for name in gomaf-app-1 gomaf-db-1 gomaf-caddy-1; do
  docker inspect "$name" >/dev/null 2>&1 || {
    echo "Production container missing: $name" >&2
    exit 1
  }
  running="$(docker inspect -f '{{.State.Running}}' "$name")"
  [[ "$running" == false ]] || {
    echo "Production container is running: $name" >&2
    exit 1
  }
done

if ss -ltnH '( sport = :80 or sport = :443 )' | grep -q .; then
  echo 'Public port 80/443 is still listening.' >&2
  exit 1
fi

[[ -s /srv/gomaf/.deploy/active_release ]] || {
  echo 'Missing active_release metadata.' >&2
  exit 1
}
[[ -s /srv/gomaf/.deploy/current_source_hash ]] || {
  echo 'Missing current_source_hash metadata.' >&2
  exit 1
}

echo 'PRODUCTION_FREEZE_CONFIRMED'
