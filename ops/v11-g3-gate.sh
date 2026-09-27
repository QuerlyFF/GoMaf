#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT/verification/v11-g3/final"
REPORT="$REPORT_DIR/gate-report.txt"
PASS_MARKER="$REPORT_DIR/PASS"
RETURN_BUNDLE="$REPORT_DIR/GoMaf-v11-G3-PASS-RETURN.tar.gz"
BACKEND_LOG="$REPORT_DIR/e2e-backend.log"
FRONTEND_LOG="$REPORT_DIR/e2e-frontend.log"
mkdir -p "$REPORT_DIR"
rm -f "$PASS_MARKER" "$RETURN_BUNDLE" "$BACKEND_LOG" "$FRONTEND_LOG"

exec > >(tee "$REPORT") 2>&1

printf 'GoMaf V11-G3 executable gate\n'
printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'root=%s\n' "$ROOT"
printf 'java=%s\n' "$(java -version 2>&1 | head -1)"
printf 'javac=%s\n' "$(javac -version 2>&1)"
printf 'node=%s\n' "$(node --version 2>&1)"
printf 'npm=%s\n' "$(npm --version 2>&1)"
printf 'source_zip_sha256=%s\n' "${V11_G3_SOURCE_ZIP_SHA256:-not-provided}"

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

chmod +x \
  "$ROOT/ops/ui-ux-audit.sh" \
  "$ROOT/ops/v11-g1-audit.sh" \
  "$ROOT/ops/v11-g2-wp006-audit.sh" \
  "$ROOT/ops/v11-g2-audit.sh" \
  "$ROOT/ops/v11-g3-audit.sh" 2>/dev/null || true

echo 'GATE source manifest verification'
sha256sum -c V11-G3-SHA256SUMS.txt >/dev/null
echo 'SOURCE MANIFEST: PASS'

rm -rf \
  "$ROOT/frontend/node_modules" \
  "$ROOT/frontend/dist" \
  "$ROOT/frontend/playwright-report" \
  "$ROOT/frontend/test-results" \
  "$ROOT/backend/target" \
  "$ROOT/ops/root/__pycache__"

cd "$ROOT"
echo 'GATE legacy source audit'
bash ops/source-audit.sh

echo 'GATE V11-G1 regression audit'
bash ops/v11-g1-audit.sh

echo 'GATE WP11-006 regression audit'
bash ops/v11-g2-wp006-audit.sh

echo 'GATE V11-G2 regression audit'
bash ops/v11-g2-audit.sh

echo 'GATE V11-G3 source/security/UI/E2E audit'
bash ops/v11-g3-audit.sh

cd "$ROOT/frontend"
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
echo 'GATE targeted G3 backend tests'
"${MVN[@]}" -B -Dtest=ProfileApiIntegrationTests,G3DomainApiIntegrationTests,ModeratorAdminApiIntegrationTests test

echo 'GATE full backend mvn verify'
"${MVN[@]}" -B verify

cd "$ROOT/frontend"
echo 'GATE Playwright Chromium install'
npx playwright install chromium

BACKEND_PID=''
FRONTEND_PID=''
cleanup_group() {
  local pid="${1:-}"
  [[ -z "$pid" ]] && return 0
  kill -TERM -- "-$pid" 2>/dev/null || true
  sleep 0.2
  kill -KILL -- "-$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
cleanup() {
  set +e
  cleanup_group "$FRONTEND_PID"
  cleanup_group "$BACKEND_PID"
}
trap cleanup EXIT INT TERM

cleanup_stale_gate_processes() {
  local pattern='\.gomaf-v11-g[23][^ ]*/GoMaf-v11-worktree/(frontend|backend)'
  local pids
  pids="$(pgrep -u "$(id -u)" -f "$pattern" 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "GATE cleanup stale prior V11 test processes: $pids"
    kill -TERM $pids 2>/dev/null || true
    sleep 1
    kill -KILL $pids 2>/dev/null || true
  fi
}

assert_port_free() {
  local port="$1"
  python3 - "$port" <<'PYPORT'
import socket, sys
port = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    s.bind(('127.0.0.1', port))
except OSError as exc:
    print(f'FAIL port 127.0.0.1:{port} already occupied: {exc}')
    raise SystemExit(1)
finally:
    s.close()
PYPORT
}

cleanup_stale_gate_processes
assert_port_free 8787
assert_port_free 5173

cd "$ROOT/backend"
echo 'GATE start isolated E2E backend on 127.0.0.1:8787'
setsid "${MVN[@]}" -B -DskipTests -Dspring-boot.run.profiles=e2e spring-boot:test-run >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 90); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then echo 'FAIL E2E backend exited'; tail -120 "$BACKEND_LOG"; exit 1; fi
  if curl -fsS http://127.0.0.1:8787/healthz >/dev/null 2>&1; then break; fi
  sleep 1
done
kill -0 "$BACKEND_PID" 2>/dev/null || { echo 'FAIL E2E backend not alive after readiness'; tail -120 "$BACKEND_LOG"; exit 1; }
curl -fsS http://127.0.0.1:8787/healthz >/dev/null || { echo 'FAIL E2E backend readiness'; tail -120 "$BACKEND_LOG"; exit 1; }
echo 'E2E backend ready'

cd "$ROOT/frontend"
echo 'GATE start React Vite server on 127.0.0.1:5173'
setsid npm run dev -- --host 127.0.0.1 >"$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!
for _ in $(seq 1 60); do
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then echo 'FAIL Vite exited'; tail -120 "$FRONTEND_LOG"; exit 1; fi
  if curl -fsS http://127.0.0.1:5173/news >/dev/null 2>&1; then break; fi
  sleep 1
done
kill -0 "$FRONTEND_PID" 2>/dev/null || { echo 'FAIL Vite not alive after readiness'; tail -120 "$FRONTEND_LOG"; exit 1; }
curl -fsS http://127.0.0.1:5173/news >/dev/null || { echo 'FAIL Vite readiness'; tail -120 "$FRONTEND_LOG"; exit 1; }
curl -fsS http://127.0.0.1:5173/src/content/NewsPage.tsx | grep -q 'Новости' || { echo 'FAIL Vite G3 source fingerprint'; tail -120 "$FRONTEND_LOG"; exit 1; }
echo 'Vite ready (G3 fingerprint verified)'

echo 'GATE Playwright G2 + G3 E2E'
npm run e2e

cleanup
trap - EXIT INT TERM

cd "$ROOT"
PACKAGE_LOCK_SHA="$(sha256sum frontend/package-lock.json | awk '{print $1}')"
SOURCE_MANIFEST_SHA="$(sha256sum V11-G3-SHA256SUMS.txt | awk '{print $1}')"
printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'package_lock_sha256=%s\n' "$PACKAGE_LOCK_SHA"
printf 'source_manifest_sha256=%s\n' "$SOURCE_MANIFEST_SHA"
printf 'V11-G2 PASS (regression verified)\n'
printf 'WP11-010 PASS\n'
printf 'WP11-011 PASS\n'
printf 'WP11-012 PASS\n'
printf 'WP11-013 PASS\n'
printf 'WP11-014 PASS\n'
printf 'WP11-015 PASS\n'
printf 'WP11-016 PASS\n'
printf 'V11-G3 PASS\n'
printf 'V11-G3 PASS\npackage_lock_sha256=%s\nsource_manifest_sha256=%s\n' "$PACKAGE_LOCK_SHA" "$SOURCE_MANIFEST_SHA" > "$PASS_MARKER"

tar_items=(
  V11-G3-SHA256SUMS.txt
  frontend/package-lock.json
  frontend/dist
  backend/target/surefire-reports
  verification/v11-g3/final/PASS
  verification/v11-g3/final/gate-report.txt
  verification/v11-g3/final/e2e-backend.log
  verification/v11-g3/final/e2e-frontend.log
)
[[ -d frontend/playwright-report ]] && tar_items+=(frontend/playwright-report)

tar -czf "$RETURN_BUNDLE" -C "$ROOT" "${tar_items[@]}"
printf 'return_bundle=%s\n' "$RETURN_BUNDLE"
printf 'return_bundle_sha256=%s\n' "$(sha256sum "$RETURN_BUNDLE" | awk '{print $1}')"
