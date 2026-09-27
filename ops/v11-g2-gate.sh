#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT/verification/v11-g2/final"
REPORT="$REPORT_DIR/gate-report.txt"
PASS_MARKER="$REPORT_DIR/PASS"
RETURN_BUNDLE="$REPORT_DIR/GoMaf-v11-G2-PASS-RETURN.tar.gz"
BACKEND_LOG="$REPORT_DIR/e2e-backend.log"
FRONTEND_LOG="$REPORT_DIR/e2e-frontend.log"
mkdir -p "$REPORT_DIR"
rm -f "$PASS_MARKER" "$RETURN_BUNDLE" "$BACKEND_LOG" "$FRONTEND_LOG"

exec > >(tee "$REPORT") 2>&1

printf 'GoMaf V11-G2 executable gate\n'
printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'root=%s\n' "$ROOT"
printf 'java=%s\n' "$(java -version 2>&1 | head -1)"
printf 'javac=%s\n' "$(javac -version 2>&1)"
printf 'node=%s\n' "$(node --version 2>&1)"
printf 'npm=%s\n' "$(npm --version 2>&1)"
printf 'source_zip_sha256=%s\n' "${V11_G2_SOURCE_ZIP_SHA256:-not-provided}"

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

chmod +x "$ROOT/ops/ui-ux-audit.sh" "$ROOT/ops/v11-g2-wp006-audit.sh" "$ROOT/ops/v11-g2-audit.sh" 2>/dev/null || true

echo 'GATE source manifest verification'
sha256sum -c V11-G2-SHA256SUMS.txt >/dev/null
echo 'SOURCE MANIFEST: PASS'
rm -rf "$ROOT/frontend/node_modules" "$ROOT/frontend/dist" "$ROOT/frontend/playwright-report" "$ROOT/frontend/test-results" "$ROOT/backend/target" "$ROOT/ops/root/__pycache__"

cd "$ROOT"
echo 'GATE legacy source audit'
bash ops/source-audit.sh

echo 'GATE V11-G1 regression audit'
bash ops/v11-g1-audit.sh

echo 'GATE WP11-006 regression audit'
bash ops/v11-g2-wp006-audit.sh

echo 'GATE V11-G2 source/security/UI/E2E audit'
bash ops/v11-g2-audit.sh

cd "$ROOT/frontend"
echo 'GATE synchronize package-lock for pinned G2 dependencies'
npm install --package-lock-only --ignore-scripts --no-audit --no-fund

echo 'GATE frontend npm ci'
npm ci --no-audit --no-fund

echo 'GATE frontend lint'
npm run lint

echo 'GATE frontend typecheck'
npm run typecheck

echo 'GATE frontend unit tests'
npm test

echo 'GATE frontend production build'
npm run build

cd "$ROOT/backend"
echo 'GATE targeted G2 backend tests'
"${MVN[@]}" -B -Dtest=PlayersReadApiIntegrationTests,PlayerAdministrationApiIntegrationTests,ApiFoundationIntegrationTests,SecurityIntegrationTests,ModeratorAdminApiIntegrationTests,G3DomainApiIntegrationTests test

echo 'GATE full backend mvn verify'
"${MVN[@]}" -B verify

cd "$ROOT/frontend"
echo 'GATE Playwright Chromium install'
npx playwright install chromium

BACKEND_PID=''
FRONTEND_PID=''
cleanup() {
  set +e
  if [[ -n "$FRONTEND_PID" ]]; then kill "$FRONTEND_PID" 2>/dev/null || true; pkill -P "$FRONTEND_PID" 2>/dev/null || true; fi
  if [[ -n "$BACKEND_PID" ]]; then kill "$BACKEND_PID" 2>/dev/null || true; pkill -P "$BACKEND_PID" 2>/dev/null || true; fi
  wait "$FRONTEND_PID" 2>/dev/null || true
  wait "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

cd "$ROOT/backend"
echo 'GATE start isolated E2E backend on 127.0.0.1:8787'
("${MVN[@]}" -B -DskipTests -Dspring-boot.run.profiles=e2e spring-boot:test-run >"$BACKEND_LOG" 2>&1) &
BACKEND_PID=$!
for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8787/healthz >/dev/null 2>&1; then break; fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then echo 'FAIL E2E backend exited'; tail -120 "$BACKEND_LOG"; exit 1; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8787/healthz >/dev/null || { echo 'FAIL E2E backend readiness'; tail -120 "$BACKEND_LOG"; exit 1; }
echo 'E2E backend ready'

cd "$ROOT/frontend"
echo 'GATE start React Vite server on 127.0.0.1:5173'
(npm run dev -- --host 127.0.0.1 >"$FRONTEND_LOG" 2>&1) &
FRONTEND_PID=$!
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:5173/players >/dev/null 2>&1; then break; fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then echo 'FAIL Vite exited'; tail -120 "$FRONTEND_LOG"; exit 1; fi
  sleep 1
done
curl -fsS http://127.0.0.1:5173/players >/dev/null || { echo 'FAIL Vite readiness'; tail -120 "$FRONTEND_LOG"; exit 1; }
echo 'Vite ready'

echo 'GATE Playwright Players E2E'
npm run e2e

cleanup
trap - EXIT INT TERM

cd "$ROOT"
PACKAGE_LOCK_SHA="$(sha256sum frontend/package-lock.json | awk '{print $1}')"
SOURCE_MANIFEST_SHA="$(sha256sum V11-G2-SHA256SUMS.txt | awk '{print $1}')"
printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'package_lock_sha256=%s\n' "$PACKAGE_LOCK_SHA"
printf 'source_manifest_sha256=%s\n' "$SOURCE_MANIFEST_SHA"
printf 'WP11-006 PASS (regression verified)\n'
printf 'WP11-007 PASS\n'
printf 'WP11-008 PASS\n'
printf 'WP11-009 PASS\n'
printf 'V11-G2 PASS\n'
printf 'V11-G2 PASS\npackage_lock_sha256=%s\nsource_manifest_sha256=%s\n' "$PACKAGE_LOCK_SHA" "$SOURCE_MANIFEST_SHA" > "$PASS_MARKER"

tar_items=(
  V11-G2-SHA256SUMS.txt
  frontend/package-lock.json
  frontend/dist
  backend/target/surefire-reports
  verification/v11-g2/final/PASS
  verification/v11-g2/final/gate-report.txt
  verification/v11-g2/final/e2e-backend.log
  verification/v11-g2/final/e2e-frontend.log
)
[[ -d frontend/playwright-report ]] && tar_items+=(frontend/playwright-report)

tar -czf "$RETURN_BUNDLE" -C "$ROOT" "${tar_items[@]}"
printf 'return_bundle=%s\n' "$RETURN_BUNDLE"
printf 'return_bundle_sha256=%s\n' "$(sha256sum "$RETURN_BUNDLE" | awk '{print $1}')"
