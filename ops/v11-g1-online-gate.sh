#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT/verification/v11-g1"
mkdir -p "$REPORT_DIR"
REPORT="$REPORT_DIR/online-gate-report.txt"
PASS_MARKER="$REPORT_DIR/PASS"
rm -f "$PASS_MARKER"
exec > >(tee "$REPORT") 2>&1

printf 'GoMaf V11-G1 online gate\n'
printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'root=%s\n' "$ROOT"

need() { command -v "$1" >/dev/null 2>&1 || { echo "FAIL missing tool: $1"; exit 1; }; }
need java
need javac
need npm
need node
need bash
need sha256sum
need sha512sum
need tar

JAVA_VERSION="$(java -version 2>&1 | head -1)"
JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/{print $2; exit}')"
JAVA_MAJOR="${JAVA_SPEC%%.*}"
if (( JAVA_MAJOR < 21 )); then
  echo "FAIL Java >=21 required"
  exit 1
fi
NODE_VERSION="$(node -p 'process.versions.node')"
NPM_VERSION="$(npm -v)"
printf 'java=%s\nnode=%s\nnpm=%s\n' "$JAVA_VERSION" "$NODE_VERSION" "$NPM_VERSION"

NODE_MAJOR="${NODE_VERSION%%.*}"
if (( NODE_MAJOR < 22 )); then
  echo "FAIL Node.js >=22 required"
  exit 1
fi

if command -v mvn >/dev/null 2>&1; then
  MVN=(mvn)
elif [[ -x "$ROOT/backend/mvnw" ]]; then
  MVN=("$ROOT/backend/mvnw")
else
  echo 'FAIL Maven is required (mvn or backend/mvnw).'
  exit 1
fi

# Keep source audits isolated from generated dependency/build artifacts and
# recover executable bits that ZIP extraction may not preserve.
chmod +x "$ROOT/ops/ui-ux-audit.sh" "$ROOT/backend/mvnw" 2>/dev/null || true
rm -rf \
  "$ROOT/frontend/node_modules" \
  "$ROOT/frontend/dist" \
  "$ROOT/backend/target" \
  "$ROOT/ops/root/__pycache__"

cd "$ROOT/frontend"
if [[ ! -f package-lock.json ]]; then
  echo 'BOOTSTRAP creating package-lock.json from pinned package.json'
  npm install --package-lock-only --ignore-scripts --no-audit --no-fund
fi
# npm --package-lock-only should not populate node_modules, but keep the
# source-audit boundary fail-closed if npm behaviour changes.
rm -rf node_modules dist

cd "$ROOT"
echo 'GATE legacy source audit'
bash ops/source-audit.sh

echo 'GATE V11-G1 source audit'
bash ops/v11-g1-audit.sh

cd "$ROOT/frontend"
echo 'GATE npm ci'
npm ci --no-audit --no-fund

echo 'GATE frontend lint'
npm run lint

echo 'GATE frontend typecheck'
npm run typecheck

echo 'GATE frontend tests'
npm test

echo 'GATE frontend build'
npm run build

echo 'GATE backend mvn verify'
cd "$ROOT/backend"
"${MVN[@]}" -B verify

cd "$ROOT"
printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'package_lock_sha256=%s\n' "$(sha256sum frontend/package-lock.json | awk '{print $1}')"
printf 'V11-G1 PASS\n'
printf 'V11-G1 PASS\npackage_lock_sha256=%s\n' "$(sha256sum frontend/package-lock.json | awk '{print $1}')" > "$PASS_MARKER"

RETURN_BUNDLE="$REPORT_DIR/GoMaf-v11-G1-PASS-RETURN.tar.gz"
rm -f "$RETURN_BUNDLE"
tar -czf "$RETURN_BUNDLE" -C "$ROOT" \
  frontend/package-lock.json \
  frontend/dist \
  backend/target/surefire-reports \
  verification/v11-g1/PASS \
  verification/v11-g1/online-gate-report.txt
printf 'return_bundle=%s\n' "$RETURN_BUNDLE"
printf 'return_bundle_sha256=%s\n' "$(sha256sum "$RETURN_BUNDLE" | awk '{print $1}')"
