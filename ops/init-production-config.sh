#!/usr/bin/env bash
set -Eeuo pipefail
TARGET="${GOMAF_ENV_FILE:-/etc/gomaf/production.env}"
TEMPLATE="$(cd "$(dirname "$0")/.." && pwd)/config/production.env.template"

if [[ $EUID -ne 0 ]]; then
  echo "Запусти через sudo: sudo bash ops/init-production-config.sh" >&2
  exit 1
fi

install -d -m 0700 -o root -g root "$(dirname "$TARGET")"
if [[ -e "$TARGET" ]]; then
  echo "$TARGET уже существует; секреты не перезаписаны."
  exit 0
fi

pg_secret="$(openssl rand -hex 32)"
app_secret="$(openssl rand -hex 32)"
migration_secret="$(openssl rand -hex 32)"
ip_secret="$(openssl rand -hex 32)"

mem_mb=$(( $(awk '/MemTotal:/ {print $2}' /proc/meminfo) / 1024 ))
if (( mem_mb < 1400 )); then
  app_mem=448m
  db_mem=320m
  caddy_mem=96m
elif (( mem_mb < 3000 )); then
  app_mem=640m
  db_mem=512m
  caddy_mem=128m
else
  app_mem=768m
  db_mem=768m
  caddy_mem=192m
fi

sed \
  -e "s/__GENERATED_POSTGRES_PASSWORD__/$pg_secret/" \
  -e "s/__GENERATED_APP_DB_PASSWORD__/$app_secret/" \
  -e "s/__GENERATED_MIGRATION_DB_PASSWORD__/$migration_secret/" \
  -e "s/__GENERATED_IP_HASH_SECRET__/$ip_secret/" \
  -e "s/__APP_MEMORY_LIMIT__/$app_mem/" \
  -e "s/__DB_MEMORY_LIMIT__/$db_mem/" \
  -e "s/__CADDY_MEMORY_LIMIT__/$caddy_mem/" \
  "$TEMPLATE" > "$TARGET"
chown root:root "$TARGET"
chmod 600 "$TARGET"

echo "Создан $TARGET"
echo "Перед deploy задай только APP_ADMIN_PASSWORD через sudo /usr/local/sbin/gomaf-set-admin. Username всегда admin."
