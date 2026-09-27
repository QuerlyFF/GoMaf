#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT/verification/v11-g4/retirement"
REPORT="$REPORT_DIR/gate-report.txt"
PASS_MARKER="$REPORT_DIR/PASS"
RETURN_BUNDLE="$REPORT_DIR/GoMaf-v11-WP11-022-PREDEPLOY-PASS-RETURN.tar.gz"
BACKEND_LOG="$REPORT_DIR/e2e-backend.log"
DOCKER_LOG="$REPORT_DIR/docker-build.log"
DOCKER_INSPECT="$REPORT_DIR/docker-image-inspect.txt"
JAR_LIST="$REPORT_DIR/jar-static-list.txt"
mkdir -p "$REPORT_DIR"
rm -f "$PASS_MARKER" "$RETURN_BUNDLE" "$BACKEND_LOG" "$DOCKER_LOG" "$DOCKER_INSPECT" "$JAR_LIST"

exec > >(tee "$REPORT") 2>&1

printf 'GoMaf V11-G4 WP11-022 LEGACY RETIREMENT predeploy gate\n'
printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'root=%s\n' "$ROOT"
printf 'java=%s\n' "$(java -version 2>&1 | head -1)"
printf 'javac=%s\n' "$(javac -version 2>&1)"
printf 'node=%s\n' "$(node --version 2>&1)"
printf 'npm=%s\n' "$(npm --version 2>&1)"
printf 'source_zip_sha256=%s\n' "${WP11_022_SOURCE_ZIP_SHA256:-not-provided}"

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

for cmd in curl python3 sha256sum jar docker; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "FAIL $cmd is required"; exit 1; }
done

docker info >/dev/null 2>&1 || { echo 'FAIL Docker daemon is not available to current user'; exit 1; }

chmod +x \
  "$ROOT/ops/ui-ux-audit.sh" \
  "$ROOT/ops/v11-g2-wp006-audit.sh" \
  "$ROOT/ops/v11-g2-audit.sh" \
  "$ROOT/ops/v11-g3-audit.sh" \
  "$ROOT/ops/v11-g4-audit.sh" 2>/dev/null || true

echo 'GATE source manifest verification'
sha256sum -c V11-G4-RETIREMENT-SHA256SUMS.txt >/dev/null
echo 'SOURCE MANIFEST: PASS'

rm -rf \
  "$ROOT/frontend/node_modules" \
  "$ROOT/frontend/dist" \
  "$ROOT/frontend/playwright-report" \
  "$ROOT/frontend/test-results" \
  "$ROOT/backend/target" \
  "$ROOT/ops/root/__pycache__"

cd "$ROOT"
echo 'GATE final source audit after legacy retirement'
bash ops/source-audit.sh

echo 'GATE WP11-006 regression audit'
bash ops/v11-g2-wp006-audit.sh

echo 'GATE V11-G2 regression audit'
bash ops/v11-g2-audit.sh

echo 'GATE V11-G3 regression audit'
bash ops/v11-g3-audit.sh

echo 'GATE V11-G4 production integration audit'
bash ops/v11-g4-audit.sh

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
if find dist -type f -name '*.map' -print -quit | grep -q .; then
  echo 'FAIL production frontend contains source maps'
  exit 1
fi
test -f dist/index.html || { echo 'FAIL frontend dist/index.html missing'; exit 1; }
find dist/assets -maxdepth 1 -type f -name '*.js' -print -quit | grep -q . || { echo 'FAIL frontend JS asset missing'; exit 1; }

cd "$ROOT"
rm -rf backend/target/generated-resources/spa
mkdir -p backend/target/generated-resources/spa
cp -a frontend/dist/. backend/target/generated-resources/spa/

echo 'GATE targeted G4 backend tests'
cd "$ROOT/backend"
"${MVN[@]}" -B -Dtest=ProductionSpaIntegrationTests,ApiFoundationIntegrationTests,SecurityIntegrationTests,PlayerAdministrationApiIntegrationTests,ModeratorAdminApiIntegrationTests,G3DomainApiIntegrationTests test

echo 'GATE full backend mvn verify with embedded production SPA resources'
"${MVN[@]}" -B verify

JAR="$ROOT/backend/target/classic-mafia-3.1.0.jar"
test -f "$JAR" || { echo 'FAIL Spring Boot jar missing'; exit 1; }
jar tf "$JAR" | grep -E '^BOOT-INF/classes/static/(index[.]html|assets/.*[.](js|css))$' > "$JAR_LIST"
grep -q '^BOOT-INF/classes/static/index.html$' "$JAR_LIST" || { echo 'FAIL SPA index not embedded in jar'; exit 1; }
grep -Eq '^BOOT-INF/classes/static/assets/.*[.]js$' "$JAR_LIST" || { echo 'FAIL SPA JS asset not embedded in jar'; exit 1; }
echo 'JAR embedded SPA: PASS'
if jar tf "$JAR" | grep -qi 'thymeleaf'; then
  echo 'FAIL Thymeleaf library/class leaked into retirement jar'
  exit 1
fi
if jar tf "$JAR" | grep -q '^BOOT-INF/classes/templates/'; then
  echo 'FAIL legacy template resources leaked into retirement jar'
  exit 1
fi
echo 'JAR legacy retirement: PASS'

cd "$ROOT"
DOCKER_TAG="gomaf/v11-g4-precutover:$(date -u +%Y%m%d%H%M%S)-$$"
echo "GATE production multi-stage Docker build: $DOCKER_TAG"
if ! docker build --pull --progress=plain -t "$DOCKER_TAG" . >"$DOCKER_LOG" 2>&1; then
  echo 'FAIL Docker production build'
  tail -200 "$DOCKER_LOG"
  exit 1
fi
docker image inspect "$DOCKER_TAG" > "$DOCKER_INSPECT"
[[ "$(docker image inspect -f '{{.Config.User}}' "$DOCKER_TAG")" == '10001' ]] || { echo 'FAIL Docker runtime user is not 10001'; exit 1; }
if docker run --rm --entrypoint sh "$DOCKER_TAG" -ec 'command -v node >/dev/null 2>&1 || command -v npm >/dev/null 2>&1'; then
  echo 'FAIL Node/npm leaked into runtime image'
  exit 1
fi
CID="$(docker create --entrypoint true "$DOCKER_TAG")"
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true; docker image rm -f "$DOCKER_TAG" >/dev/null 2>&1 || true' EXIT
DOCKER_JAR="$REPORT_DIR/docker-app.jar"
docker cp "$CID:/app/app.jar" "$DOCKER_JAR"
jar tf "$DOCKER_JAR" | grep -q '^BOOT-INF/classes/static/index.html$' || { echo 'FAIL Docker runtime jar lacks SPA index'; exit 1; }
jar tf "$DOCKER_JAR" | grep -Eq '^BOOT-INF/classes/static/assets/.*[.]js$' || { echo 'FAIL Docker runtime jar lacks SPA JS'; exit 1; }
if jar tf "$DOCKER_JAR" | grep -qi 'thymeleaf'; then echo 'FAIL Docker runtime jar contains Thymeleaf'; exit 1; fi
if jar tf "$DOCKER_JAR" | grep -q '^BOOT-INF/classes/templates/'; then echo 'FAIL Docker runtime jar contains legacy templates'; exit 1; fi
docker rm -f "$CID" >/dev/null
CID=''
docker image rm -f "$DOCKER_TAG" >/dev/null
DOCKER_TAG=''
trap - EXIT
echo 'Docker production pipeline: PASS'

cd "$ROOT/frontend"
echo 'GATE Playwright Chromium install'
npx playwright install chromium

BACKEND_PID=''
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
  cleanup_group "$BACKEND_PID"
}
trap cleanup EXIT INT TERM

cleanup_stale_gate_processes() {
  local pattern='\.gomaf-v11-g[234][^ ]*/GoMaf-v11-worktree/backend'
  local pids
  pids="$(pgrep -u "$(id -u)" -f "$pattern" 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "GATE cleanup stale prior V11 backend processes: $pids"
    kill -TERM $pids 2>/dev/null || true
    sleep 1
    kill -KILL $pids 2>/dev/null || true
  fi
}

assert_port_free() {
  local port="$1"
  python3 - "$port" <<'PYPORT'
import socket, sys
port=int(sys.argv[1])
s=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
try:
    s.bind(('127.0.0.1',port))
except OSError as exc:
    print(f'FAIL port 127.0.0.1:{port} already occupied: {exc}')
    raise SystemExit(1)
finally:
    s.close()
PYPORT
}

cleanup_stale_gate_processes
assert_port_free 8787

cd "$ROOT/backend"
echo 'GATE start Spring-served production SPA E2E backend on 127.0.0.1:8787'
setsid "${MVN[@]}" -B -DskipTests -Dspring-boot.run.profiles=e2e spring-boot:test-run >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 90); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then echo 'FAIL G4 E2E backend exited'; tail -160 "$BACKEND_LOG"; exit 1; fi
  if curl -fsS http://127.0.0.1:8787/healthz >/dev/null 2>&1; then break; fi
  sleep 1
done
kill -0 "$BACKEND_PID" 2>/dev/null || { echo 'FAIL G4 E2E backend not alive after readiness'; tail -160 "$BACKEND_LOG"; exit 1; }
curl -fsS http://127.0.0.1:8787/healthz >/dev/null || { echo 'FAIL G4 E2E backend readiness'; tail -160 "$BACKEND_LOG"; exit 1; }
SPA_HTML="$(curl -fsS http://127.0.0.1:8787/news)"
printf '%s\n' "$SPA_HTML" | grep -q '/assets/' || { echo 'FAIL Spring E2E server is not serving embedded Vite index'; exit 1; }
printf '%s\n' "$SPA_HTML" | grep -q 'G4-SPA-INDEX' && { echo 'FAIL test fixture shadowed production Vite index'; exit 1; }
echo 'Spring embedded SPA ready'

cd "$ROOT/frontend"
echo 'GATE Playwright G2 + G3 + G4 against Spring production asset server'
PLAYWRIGHT_BASE_URL=http://127.0.0.1:8787 npm run e2e

cleanup
trap - EXIT INT TERM

cd "$ROOT"
PACKAGE_LOCK_SHA="$(sha256sum frontend/package-lock.json | awk '{print $1}')"
SOURCE_MANIFEST_SHA="$(sha256sum V11-G4-RETIREMENT-SHA256SUMS.txt | awk '{print $1}')"
printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'package_lock_sha256=%s\n' "$PACKAGE_LOCK_SHA"
printf 'source_manifest_sha256=%s\n' "$SOURCE_MANIFEST_SHA"
printf 'WP11-017 PASS\n'
printf 'WP11-018 PASS\n'
printf 'WP11-019 PASS\n'
printf 'WP11-020 PASS\n'
printf 'WP11-021 PASS\n'
printf 'WP11-022 PREDEPLOY PASS\n'
printf 'FINAL PRODUCTION RETIREMENT DEPLOY PENDING\n'
printf 'V11-G4 RETIREMENT PREDEPLOY PASS\n'
printf 'WP11-022 PREDEPLOY PASS\nV11-G4 RETIREMENT PREDEPLOY PASS\npackage_lock_sha256=%s\nsource_manifest_sha256=%s\n' "$PACKAGE_LOCK_SHA" "$SOURCE_MANIFEST_SHA" > "$PASS_MARKER"

rm -f "$REPORT_DIR/docker-app.jar"
tar_items=(
  V11-G4-RETIREMENT-SHA256SUMS.txt
  frontend/package-lock.json
  frontend/dist
  backend/target/surefire-reports
  verification/v11-g4/retirement/PASS
  verification/v11-g4/retirement/gate-report.txt
  verification/v11-g4/retirement/e2e-backend.log
  verification/v11-g4/retirement/docker-build.log
  verification/v11-g4/retirement/docker-image-inspect.txt
  verification/v11-g4/retirement/jar-static-list.txt
)
[[ -d frontend/playwright-report ]] && tar_items+=(frontend/playwright-report)

tar -czf "$RETURN_BUNDLE" -C "$ROOT" "${tar_items[@]}"
printf 'return_bundle=%s\n' "$RETURN_BUNDLE"
printf 'return_bundle_sha256=%s\n' "$(sha256sum "$RETURN_BUNDLE" | awk '{print $1}')"
