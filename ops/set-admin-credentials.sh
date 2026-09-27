#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Запусти: sudo /usr/local/sbin/gomaf-set-admin" >&2; exit 1; }
ENV_FILE="${GOMAF_ENV_FILE:-/etc/gomaf/production.env}"
[[ -f "$ENV_FILE" ]] || { echo "Не найден $ENV_FILE" >&2; exit 1; }

read -r -s -p 'Новый пароль ADMIN admin: ' admin_pass
echo
read -r -s -p 'Повтори пароль ADMIN admin: ' admin_pass2
echo
[[ "$admin_pass" == "$admin_pass2" ]] || { echo "Пароли не совпадают." >&2; exit 1; }

export GOMAF_ADMIN_PASS="$admin_pass"
python3 - "$ENV_FILE" <<'PY'
import os
import sys
from pathlib import Path

path = Path(sys.argv[1])
password = os.environ.get("GOMAF_ADMIN_PASS", "")
if not (12 <= len(password) <= 64) or len(password.encode("utf-8")) > 72:
    raise SystemExit("Пароль: 12-64 символа и максимум 72 UTF-8 байта.")
if password.lower() in {"test", "admin", "password", "12345678", "qwerty123"}:
    raise SystemExit("Этот пароль запрещён как тестовый/слабый.")
if "'" in password or "\n" in password or "\r" in password:
    raise SystemExit("Для production.env пароль не должен содержать одинарную кавычку или перевод строки.")

lines = path.read_text(encoding="utf-8").splitlines()
result = []
seen_user = False
seen_pass = False
for line in lines:
    if line.startswith("APP_ADMIN_USERNAME="):
        result.append("APP_ADMIN_USERNAME=admin")
        seen_user = True
    elif line.startswith("APP_ADMIN_PASSWORD="):
        result.append(f"APP_ADMIN_PASSWORD='{password}'")
        seen_pass = True
    else:
        result.append(line)
if not (seen_user and seen_pass):
    raise SystemExit("В production.env отсутствуют APP_ADMIN_USERNAME/APP_ADMIN_PASSWORD.")

tmp = path.with_name(path.name + ".tmp")
tmp.write_text("\n".join(result) + "\n", encoding="utf-8")
os.chmod(tmp, 0o600)
os.replace(tmp, path)
PY
unset GOMAF_ADMIN_PASS admin_pass admin_pass2
chown root:root "$ENV_FILE"
chmod 600 "$ENV_FILE"
printf 'ADMIN admin password обновлён в %s. Пароль не выводился.\n' "$ENV_FILE"
printf 'Изменение применится через следующий подписанный deployment workflow\n'
