#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run via sudo.' >&2; exit 1; }
ENV_FILE="${GOMAF_ENV_FILE:-/etc/gomaf/production.env}"
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || { echo "Missing/unsafe $ENV_FILE" >&2; exit 1; }
if python3 - "$ENV_FILE" <<'PY'
from pathlib import Path
import sys
vals={}
for line in Path(sys.argv[1]).read_text('utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        k,v=line.split('=',1); vals[k]=v.strip().strip("'\"")
p=vals.get('APP_ADMIN_PASSWORD','')
raise SystemExit(0 if 12 <= len(p) <= 64 and len(p.encode()) <= 72 and not p.startswith('CHANGE_ME') else 1)
PY
then
  echo 'ADMIN PASSWORD POLICY PASS'
  exit 0
fi
printf 'Текущий production ADMIN пароль короче новой политики WP11-023.\n'
printf 'Bootstrap PREDEPLOY уже прошёл; сейчас безопасно задать новый пароль перед host-hardening.\n'
bash "$(cd "$(dirname "$0")" && pwd)/set-admin-credentials.sh"
