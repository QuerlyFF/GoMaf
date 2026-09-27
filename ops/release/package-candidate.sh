#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ $# -eq 3 ]] || { echo "Usage: $0 <output.zip> <release-sequence> <expected-current-source-hash>" >&2; exit 2; }
OUT="$1"; SEQUENCE="$2"; EXPECTED="$3"
[[ "$OUT" == /* ]] || OUT="$(pwd)/$OUT"
[[ "$SEQUENCE" =~ ^[1-9][0-9]{5,19}$ ]] || { echo 'Invalid release sequence' >&2; exit 1; }
[[ "$EXPECTED" =~ ^[0-9a-f]{12}$ ]] || { echo 'Invalid expected current source hash' >&2; exit 1; }
for c in python3 sha256sum tar; do command -v "$c" >/dev/null || { echo "$c missing" >&2; exit 1; }; done
[[ -f "$ROOT/.wp11-023-security-tools.env" ]] || { echo 'Run ops/wp11-023-online-prepare.sh first' >&2; exit 1; }

cd "$ROOT"
bash ops/source-audit.sh
bash ops/ui-ux-audit.sh
bash ops/wp11-023-security-audit.sh

STAGE="$(mktemp -d)"; VERIFY="$(mktemp -d)"
cleanup(){ rm -rf -- "$STAGE" "$VERIFY"; }
trap cleanup EXIT INT TERM
NAME="GoMaf-WP11-023-candidate"
DEST="$STAGE/$NAME"
mkdir -m 0755 "$DEST"

# Source-only release. Build/test/cache/evidence/secrets are never packaged.
tar \
  --exclude='./.git' \
  --exclude='./backend/target' \
  --exclude='./frontend/node_modules' \
  --exclude='./frontend/dist' \
  --exclude='./frontend/playwright-report' \
  --exclude='./frontend/test-results' \
  --exclude='./verification' \
  --exclude='./.trivy-cache' \
  --exclude='./.deploy' \
  --exclude='./.audit' \
  --exclude='./__pycache__' \
  --exclude='*.pyc' --exclude='*.class' --exclude='*.jar' --exclude='*.war' \
  --exclude='*.dump' --exclude='*.bak' --exclude='*.zip' --exclude='*.tar.gz' \
  -cf - . | tar -xf - -C "$DEST"

bash "$DEST/ops/normalize-source-permissions.sh" "$DEST" >/dev/null
cat > "$DEST/RELEASE-CANDIDATE.env" <<META
FORMAT=1
WORK_PACKAGE=WP11-023
RELEASE_SEQUENCE=$SEQUENCE
EXPECTED_CURRENT_SOURCE_HASH=$EXPECTED
META
chmod 0644 "$DEST/RELEASE-CANDIDATE.env"

python3 - "$DEST" <<'PY'
from pathlib import Path
import re,sys
root=Path(sys.argv[1])
forbidden_names={'.env','production.env','id_rsa','id_ed25519','release-private.pem'}
forbidden_suffixes=('.pem.key','.p12','.pfx','.jks','.dump','.sql.gz','.pyc','.class','.jar','.war')
errors=[]
for p in root.rglob('*'):
    if not p.is_file(): continue
    rel=p.relative_to(root).as_posix(); low=rel.lower()
    if p.name in forbidden_names or low.endswith(forbidden_suffixes): errors.append(rel)
    if '/backups/' in '/'+low+'/' or '/.ssh/' in '/'+low+'/': errors.append(rel)
    # A public release key is allowed only when explicitly named public; private keys never are.
    if 'private' in p.name.lower() and ('key' in p.name.lower() or p.suffix.lower()=='.pem'): errors.append(rel)
if errors:
    print('Forbidden release artefacts:', *sorted(set(errors)), sep='\n', file=sys.stderr)
    raise SystemExit(1)
PY

(
  cd "$DEST"
  find . -type f ! -name RELEASE-SHA256SUMS.txt -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > RELEASE-SHA256SUMS.txt
)
mkdir -p "$(dirname "$OUT")"
rm -f -- "$OUT"
python3 - "$STAGE" "$NAME" "$OUT" <<'PYZIP'
from pathlib import Path
import stat, sys, zipfile
stage=Path(sys.argv[1]).resolve()
name=sys.argv[2]
out=Path(sys.argv[3]).resolve()
root=stage/name
if not root.is_dir() or root.is_symlink():
    raise SystemExit('candidate staging root missing/unsafe')
entries=[root, *sorted(root.rglob('*'), key=lambda p: p.relative_to(stage).as_posix())]
for p in entries:
    if p.is_symlink():
        raise SystemExit('symlink not allowed in candidate: '+p.relative_to(stage).as_posix())
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=True) as zf:
    for p in entries:
        arc=p.relative_to(stage).as_posix()
        if p.is_dir():
            zi=zipfile.ZipInfo(arc.rstrip('/')+'/', date_time=__import__('time').localtime(p.stat().st_mtime)[:6])
            zi.create_system=3
            zi.external_attr=(stat.S_IFDIR | (p.stat().st_mode & 0o777)) << 16
            zf.writestr(zi, b'')
        elif p.is_file():
            zf.write(p, arc)
        else:
            raise SystemExit('non-regular candidate entry: '+arc)
PYZIP
python3 "$ROOT/ops/release/safe-zip.py" extract "$OUT" "$VERIFY" >/dev/null
(
  cd "$VERIFY/$NAME"
  sha256sum -c RELEASE-SHA256SUMS.txt >/dev/null
)
chmod 0644 "$OUT"
printf 'CANDIDATE=%s\nARCHIVE_SHA256=%s\nRELEASE_SEQUENCE=%s\nEXPECTED_CURRENT_SOURCE_HASH=%s\n' \
  "$OUT" "$(sha256sum "$OUT" | awk '{print $1}')" "$SEQUENCE" "$EXPECTED"
