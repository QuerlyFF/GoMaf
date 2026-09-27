#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT/verification/v11-g2/wp11-006"
REPORT="$REPORT_DIR/gate-report.txt"
PASS_MARKER="$REPORT_DIR/PASS"
RETURN_BUNDLE="$REPORT_DIR/GoMaf-v11-WP11-006-PASS-RETURN.tar.gz"
mkdir -p "$REPORT_DIR"
rm -f "$PASS_MARKER" "$RETURN_BUNDLE"

exec > >(tee "$REPORT") 2>&1

printf 'GoMaf V11 WP11-006 executable gate\n'
printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'root=%s\n' "$ROOT"
printf 'java=%s\n' "$(java -version 2>&1 | head -1)"
printf 'javac=%s\n' "$(javac -version 2>&1)"
printf 'node=%s\n' "$(node --version 2>&1)"
printf 'npm=%s\n' "$(npm --version 2>&1)"
printf 'source_zip_sha256=%s\n' "${WP11006_SOURCE_ZIP_SHA256:-not-provided}"

JAVA_MAJOR="$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
NODE_MAJOR="$(node --version | sed 's/^v//' | cut -d. -f1)"
if (( JAVA_MAJOR < 21 )); then echo 'FAIL JDK 21+ required'; exit 1; fi
if (( NODE_MAJOR < 22 )); then echo 'FAIL Node.js 22+ required'; exit 1; fi

if command -v mvn >/dev/null 2>&1; then
  MVN=(mvn)
elif [[ -f "$ROOT/backend/mvnw" ]]; then
  chmod +x "$ROOT/backend/mvnw"
  MVN=("$ROOT/backend/mvnw")
else
  echo 'FAIL Maven is required (mvn or backend/mvnw).'
  exit 1
fi

chmod +x "$ROOT/ops/ui-ux-audit.sh" "$ROOT/ops/v11-g2-wp006-audit.sh" 2>/dev/null || true
echo 'GATE source manifest verification'
sha256sum -c WP11-006-SHA256SUMS.txt >/dev/null
echo 'SOURCE MANIFEST: PASS'
rm -rf "$ROOT/frontend/node_modules" "$ROOT/frontend/dist" "$ROOT/backend/target" "$ROOT/ops/root/__pycache__"

cd "$ROOT"
echo 'GATE legacy source audit'
bash ops/source-audit.sh

echo 'GATE V11-G1 regression audit'
bash ops/v11-g1-audit.sh

echo 'GATE WP11-006 source/privacy audit'
bash ops/v11-g2-wp006-audit.sh

cd "$ROOT/frontend"
echo 'GATE frontend npm ci'
npm ci --no-audit --no-fund

echo 'GATE frontend lint'
npm run lint

echo 'GATE frontend typecheck'
npm run typecheck

echo 'GATE frontend tests'
npm test

echo 'GATE frontend build'
npm run build

cd "$ROOT/backend"
echo 'GATE targeted Players/API/security tests'
"${MVN[@]}" -B -Dtest=PlayersReadApiIntegrationTests,ApiFoundationIntegrationTests,SecurityIntegrationTests test

echo 'GATE full backend mvn verify'
"${MVN[@]}" -B verify

cd "$ROOT"
PACKAGE_LOCK_SHA="$(sha256sum frontend/package-lock.json | awk '{print $1}')"
SOURCE_MANIFEST_SHA="$(sha256sum WP11-006-SHA256SUMS.txt | awk '{print $1}')"
printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'package_lock_sha256=%s\n' "$PACKAGE_LOCK_SHA"
printf 'source_manifest_sha256=%s\n' "$SOURCE_MANIFEST_SHA"
printf 'WP11-006 PASS\n'
printf 'WP11-006 PASS\npackage_lock_sha256=%s\nsource_manifest_sha256=%s\n' "$PACKAGE_LOCK_SHA" "$SOURCE_MANIFEST_SHA" > "$PASS_MARKER"

tar -czf "$RETURN_BUNDLE" -C "$ROOT" \
  WP11-006-SHA256SUMS.txt \
  frontend/package-lock.json \
  frontend/dist \
  backend/target/surefire-reports \
  verification/v11-g2/wp11-006/PASS \
  verification/v11-g2/wp11-006/gate-report.txt

printf 'return_bundle=%s\n' "$RETURN_BUNDLE"
printf 'return_bundle_sha256=%s\n' "$(sha256sum "$RETURN_BUNDLE" | awk '{print $1}')"
