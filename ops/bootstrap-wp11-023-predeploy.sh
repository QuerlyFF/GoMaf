#!/usr/bin/env bash
set -Eeuo pipefail

[[ $EUID -eq 0 ]] || { echo 'Run via sudo from opsadmin.' >&2; exit 1; }
[[ $# -eq 2 ]] || { echo 'Usage: sudo bash bootstrap-wp11-023-predeploy.sh <candidate.zip> <output-dir>' >&2; exit 2; }
ARCHIVE="$1"; OUT="$2"
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SAFE_ZIP="$SOURCE_ROOT/ops/release/safe-zip.py"
for f in "$ARCHIVE"; do [[ -f "$f" && ! -L "$f" && -s "$f" ]] || { echo "Unsafe/missing input: $f" >&2; exit 1; }; done
for c in openssl python3 sha256sum tar docker curl; do command -v "$c" >/dev/null || { echo "$c missing" >&2; exit 1; }; done
[[ -f "$SAFE_ZIP" && ! -L "$SAFE_ZIP" ]] || { echo 'Safe ZIP extractor missing/unsafe' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker daemon unavailable' >&2; exit 1; }

TXN="$(mktemp -d /tmp/gomaf-wp11-023-bootstrap.XXXXXX)"
cleanup(){ rm -rf -- "$TXN"; }
trap cleanup EXIT INT TERM
install -d -m 0700 "$TXN/extracted" "$TXN/runtime-shadow"
TOP="$(python3 "$SAFE_ZIP" extract "$ARCHIVE" "$TXN/extracted")"
ROOT="$TXN/extracted/$TOP"
[[ -d "$ROOT" && ! -L "$ROOT" ]] || { echo 'Candidate root missing' >&2; exit 1; }
[[ -f "$ROOT/RELEASE-SHA256SUMS.txt" && -f "$ROOT/RELEASE-CANDIDATE.env" ]] || { echo 'Candidate metadata missing' >&2; exit 1; }
(cd "$ROOT" && sha256sum -c RELEASE-SHA256SUMS.txt >/dev/null) || { echo 'Internal candidate checksum failure' >&2; exit 1; }
bash "$ROOT/ops/normalize-source-permissions.sh" "$ROOT" >/dev/null

readarray -t META < <(python3 - "$ROOT/RELEASE-CANDIDATE.env" <<'PY'
import re,sys
from pathlib import Path
data={}
allowed={'FORMAT','WORK_PACKAGE','RELEASE_SEQUENCE','EXPECTED_CURRENT_SOURCE_HASH'}
for raw in Path(sys.argv[1]).read_text('utf-8').splitlines():
    if not raw or raw.startswith('#'): continue
    if '=' not in raw: raise SystemExit('malformed metadata')
    k,v=raw.split('=',1)
    if k not in allowed or k in data: raise SystemExit('invalid metadata key')
    data[k]=v
if set(data)!=allowed or data['FORMAT']!='1' or data['WORK_PACKAGE']!='WP11-023': raise SystemExit('wrong metadata')
if not re.fullmatch(r'[1-9][0-9]{5,19}',data['RELEASE_SEQUENCE']): raise SystemExit('invalid sequence')
if not re.fullmatch(r'[0-9a-f]{12}',data['EXPECTED_CURRENT_SOURCE_HASH']): raise SystemExit('invalid baseline')
print(data['RELEASE_SEQUENCE']); print(data['EXPECTED_CURRENT_SOURCE_HASH'])
PY
)
SEQUENCE="${META[0]}"; EXPECTED="${META[1]}"
CURRENT="$(cat /srv/gomaf/.deploy/current_source_hash 2>/dev/null || true)"
[[ "$CURRENT" == "$EXPECTED" ]] || { echo "Production baseline mismatch: expected=$EXPECTED current=${CURRENT:-missing}" >&2; exit 1; }

# Shadow runtime metadata: PREDEPLOY may read the active production release and
# DB, but any runtime_prepare writes remain inside /tmp and cannot alter /srv.
for f in active_release runtime.env current_image current_source_hash previous_source_hash; do
  [[ -f "/srv/gomaf/.deploy/$f" ]] && cp -a "/srv/gomaf/.deploy/$f" "$TXN/runtime-shadow/$f"
done
[[ -f "$TXN/runtime-shadow/active_release" ]] || { echo 'Missing production active_release metadata' >&2; exit 1; }
[[ -f "$TXN/runtime-shadow/runtime.env" ]] || printf 'APP_IMAGE=gomaf/app:current\n' > "$TXN/runtime-shadow/runtime.env"
chmod 0600 "$TXN/runtime-shadow"/* 2>/dev/null || true

# Build an ephemeral validation config. It copies non-secret current settings,
# but supplies only temporary values for WP11-023-only fields; production.env is
# never edited during bootstrap PREDEPLOY.
cp /etc/gomaf/production.env "$TXN/predeploy.env"
chmod 0600 "$TXN/predeploy.env"
TMP_MIG_PASS="$(openssl rand -hex 32)"; TMP_ADMIN="$(openssl rand -hex 20)"
export TMP_MIG_PASS TMP_ADMIN
python3 - "$TXN/predeploy.env" <<'PY'
import os,sys
from pathlib import Path
p=Path(sys.argv[1]); lines=p.read_text('utf-8').splitlines()
updates={'MIGRATION_DB_USER':'wp11023_predeploy_migrator','MIGRATION_DB_PASSWORD':os.environ['TMP_MIG_PASS'],
         'APP_ADMIN_USERNAME':'admin','APP_ADMIN_PASSWORD':"'"+os.environ['TMP_ADMIN']+"'"}
out=[]; seen=set()
for line in lines:
    if '=' in line and not line.lstrip().startswith('#'):
        k=line.split('=',1)[0]
        if k in updates: out.append(k+'='+updates[k]); seen.add(k); continue
    out.append(line)
for k,v in updates.items():
    if k not in seen: out.append(k+'='+v)
p.write_text('\n'.join(out)+'\n', encoding='utf-8')
PY
unset TMP_MIG_PASS TMP_ADMIN

ARCHIVE_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
EVIDENCE="$TXN/predeploy-evidence.env"
export GOMAF_ENV_FILE="$TXN/predeploy.env"
export GOMAF_RUNTIME_DIR="$TXN/runtime-shadow"
printf '[wp11-023-bootstrap] candidate integrity and baseline verified; starting isolated PREDEPLOY; production files are read-only inputs\n'
"$ROOT/ops/wp11-023-predeploy.sh" "$ARCHIVE_SHA" "$SEQUENCE" "$EXPECTED" "$EVIDENCE"
[[ -f "$EVIDENCE" ]] || { echo 'PREDEPLOY evidence missing' >&2; exit 1; }
grep -qx 'STATUS=PASS' "$EVIDENCE" || { echo 'PREDEPLOY status is not PASS' >&2; exit 1; }
grep -qx "ARCHIVE_SHA256=$ARCHIVE_SHA" "$EVIDENCE" || { echo 'PREDEPLOY archive binding mismatch' >&2; exit 1; }
grep -qx "RELEASE_SEQUENCE=$SEQUENCE" "$EVIDENCE" || { echo 'PREDEPLOY sequence binding mismatch' >&2; exit 1; }
grep -qx "EXPECTED_CURRENT_SOURCE_HASH=$EXPECTED" "$EVIDENCE" || { echo 'PREDEPLOY baseline binding mismatch' >&2; exit 1; }

rm -rf -- "$OUT"
install -d -m 0700 "$OUT"
install -m 0600 "$EVIDENCE" "$OUT/bootstrap-predeploy-evidence.env"
cp -a "$ROOT/verification/wp11-023/predeploy" "$OUT/predeploy-report"
printf '%s  candidate.zip\n' "$ARCHIVE_SHA" > "$OUT/candidate.sha256"
(
  cd "$OUT"
  sha256sum bootstrap-predeploy-evidence.env candidate.sha256 > SHA256SUMS.txt
  find predeploy-report -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum >> SHA256SUMS.txt
  tar -czf bootstrap-predeploy-evidence.tar.gz bootstrap-predeploy-evidence.env predeploy-report candidate.sha256 SHA256SUMS.txt
)
chmod 0600 "$OUT/bootstrap-predeploy-evidence.tar.gz"
if [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != root ]] && id "$SUDO_USER" >/dev/null 2>&1; then
  chown -R "$SUDO_USER":"$(id -gn "$SUDO_USER")" "$OUT"
fi
printf 'WP11-023 BOOTSTRAP PREDEPLOY PASS\nEVIDENCE=%s\n' "$OUT/bootstrap-predeploy-evidence.env"
