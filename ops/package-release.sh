#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-/mnt/data}"
VERSION='WP11-023'
BOOTSTRAP_BASENAME="Cristalix-Mafia-${VERSION}-bootstrap"
BOOTSTRAP_ARCHIVE="$OUT_DIR/${BOOTSTRAP_BASENAME}.zip"
BOOTSTRAP_SHA_FILE="$BOOTSTRAP_ARCHIVE.sha256"
DEPLOY_WRAPPER="$OUT_DIR/gomaf-deploy-${VERSION}.sh"
ALL_ARCHIVE="$OUT_DIR/Cristalix-Mafia-${VERSION}-ALL.zip"
ALL_SHA_FILE="$ALL_ARCHIVE.sha256"
fail(){ printf 'PACKAGE FAIL: %s\n' "$*" >&2; exit 1; }
info(){ printf 'PACKAGE: %s\n' "$*"; }
for c in python3 zip unzip sha256sum tar; do command -v "$c" >/dev/null || fail "$c is required"; done
mkdir -p "$OUT_DIR"; cd "$ROOT"

if find . -maxdepth 2 -type f \( -name '*WP11-023-VM*' -o -name '*VM-HANDOFF*' \) -print -quit | grep -q .; then
  fail 'obsolete WP11-023 VM workflow artefact is present in source tree'
fi

info 'running source audit on bootstrap source'
bash ./ops/source-audit.sh
bash ./ops/ui-ux-audit.sh

python3 - <<'PY'
from pathlib import Path
import sys
root=Path('.')
forbidden_names={'.env','production.env','release-private.pem','id_rsa','id_ed25519'}
forbidden=[]
for p in root.rglob('*'):
    if not p.is_file(): continue
    rel=p.as_posix(); low=rel.lower()
    if any(x in p.parts for x in ('.git','.audit','target','node_modules','dist','verification','.trivy-cache','playwright-report','test-results','__pycache__')): continue
    if p.name in forbidden_names or low.endswith(('.dump','.p12','.pfx','.jks','.pyc','.class','.jar','.war')): forbidden.append(rel)
if forbidden:
    print('Forbidden bootstrap artefacts:',*sorted(set(forbidden)),sep='\n',file=sys.stderr); raise SystemExit(1)
PY

STAGE="$(mktemp -d)"; VERIFY="$(mktemp -d)"; ALL_STAGE="$(mktemp -d)"; ALL_VERIFY="$(mktemp -d)"
cleanup(){ rm -rf -- "$STAGE" "$VERIFY" "$ALL_STAGE" "$ALL_VERIFY"; }
trap cleanup EXIT INT TERM
DEST="$STAGE/$BOOTSTRAP_BASENAME"; mkdir -m 0755 "$DEST"
tar \
  --exclude='./.git' --exclude='./backend/target' --exclude='./frontend/node_modules' --exclude='./frontend/dist' \
  --exclude='./frontend/playwright-report' --exclude='./frontend/test-results' --exclude='./verification' \
  --exclude='./.trivy-cache' --exclude='./.deploy' --exclude='./.audit' --exclude='./__pycache__' \
  --exclude='*.pyc' --exclude='*.class' --exclude='*.jar' --exclude='*.war' --exclude='*.dump' \
  --exclude='*.zip' --exclude='*.tar.gz' -cf - . | tar -xf - -C "$DEST"
bash "$DEST/ops/normalize-source-permissions.sh" "$DEST" >/dev/null
chmod 0755 "$DEST/client/gomaf-deploy-update.sh.in" "$DEST/client/gomaf-deploy-update.sh" 2>/dev/null || true
cat > "$DEST/BOOTSTRAP-INFO.txt" <<INFO
work_package=WP11-023
purpose=online-finalization-and-candidate-bound-predeploy-bootstrap
production_secrets_in_archive=no
production_rollout_directly_from_bootstrap=no
final_candidate_created_and_verified_on_vps=yes
INFO
(
  cd "$DEST"
  find . -type f ! -name RELEASE-SHA256SUMS.txt -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > RELEASE-SHA256SUMS.txt
)
rm -f -- "$BOOTSTRAP_ARCHIVE" "$BOOTSTRAP_SHA_FILE"
(cd "$STAGE" && zip -q -X -r "$BOOTSTRAP_ARCHIVE" "$BOOTSTRAP_BASENAME")
(cd "$OUT_DIR" && sha256sum "$(basename "$BOOTSTRAP_ARCHIVE")" > "$(basename "$BOOTSTRAP_SHA_FILE")")
chmod 0644 "$BOOTSTRAP_ARCHIVE" "$BOOTSTRAP_SHA_FILE"
unzip -tqq "$BOOTSTRAP_ARCHIVE"; unzip -q "$BOOTSTRAP_ARCHIVE" -d "$VERIFY"
(cd "$VERIFY/$BOOTSTRAP_BASENAME" && sha256sum -c RELEASE-SHA256SUMS.txt >/dev/null)

python3 - "$ROOT/client/gomaf-deploy-update.sh.in" "$DEPLOY_WRAPPER" "$BOOTSTRAP_BASENAME" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text('utf-8').replace('@@BOOTSTRAP_BASENAME@@',sys.argv[3])
if '@@BOOTSTRAP_BASENAME@@' in s: raise SystemExit('wrapper token replacement failed')
Path(sys.argv[2]).write_text(s,encoding='utf-8')
PY
chmod 0755 "$DEPLOY_WRAPPER"; bash -n "$DEPLOY_WRAPPER"

ALL_ROOT="$ALL_STAGE/Cristalix-Mafia-${VERSION}-ALL"; mkdir -m 0755 "$ALL_ROOT"
cp "$BOOTSTRAP_ARCHIVE" "$BOOTSTRAP_SHA_FILE" "$DEPLOY_WRAPPER" "$ALL_ROOT/"
cat > "$ALL_ROOT/START-HERE.txt" <<START
GoMaf WP11-023 security-hardening update

На основном Ubuntu-компьютере после распаковки запусти только:
  bash ./$(basename "$DEPLOY_WRAPPER")

Скрипт выполняет online preparation на VPS, SHA-256/internal-manifest проверку финального candidate,
bootstrap PREDEPLOY без rollout, host hardening только после PASS и повторный FINAL PREDEPLOY.
После FINAL PREDEPLOY PASS production НЕ запускается автоматически: требуется интерактивный TTY
и точный ручной ввод фразы «РАЗРЕШАЮ ЗАПУСК PRODUCTION». Любой другой ввод или EOF прекращает
работу без production rollout. Только после точного подтверждения выполняются deploy, production audit
и скачивание evidence обратно.

Release-signing key не используется; доверие основано на candidate SHA-256, internal manifest, baseline binding и anti-replay sequence.
START
(
  cd "$ALL_ROOT"
  sha256sum "$(basename "$BOOTSTRAP_ARCHIVE")" "$(basename "$BOOTSTRAP_SHA_FILE")" "$(basename "$DEPLOY_WRAPPER")" START-HERE.txt > CONTENTS-SHA256SUMS.txt
)
rm -f -- "$ALL_ARCHIVE" "$ALL_SHA_FILE"
(cd "$ALL_STAGE" && zip -q -X -r "$ALL_ARCHIVE" "$(basename "$ALL_ROOT")")
(cd "$OUT_DIR" && sha256sum "$(basename "$ALL_ARCHIVE")" > "$(basename "$ALL_SHA_FILE")")
chmod 0644 "$ALL_ARCHIVE" "$ALL_SHA_FILE"
unzip -tqq "$ALL_ARCHIVE"; unzip -q "$ALL_ARCHIVE" -d "$ALL_VERIFY"
(cd "$ALL_VERIFY/$(basename "$ALL_ROOT")" && sha256sum -c CONTENTS-SHA256SUMS.txt >/dev/null && bash -n "$(basename "$DEPLOY_WRAPPER")")

printf 'BOOTSTRAP_ARCHIVE=%s\nBOOTSTRAP_SHA256=%s\nDEPLOY_WRAPPER=%s\nALL_ARCHIVE=%s\nALL_SHA256=%s\n' \
 "$BOOTSTRAP_ARCHIVE" "$(sha256sum "$BOOTSTRAP_ARCHIVE"|awk '{print $1}')" "$DEPLOY_WRAPPER" "$ALL_ARCHIVE" "$(sha256sum "$ALL_ARCHIVE"|awk '{print $1}')"
