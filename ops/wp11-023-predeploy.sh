#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE_SHA="${1:-manual}"; SEQUENCE="${2:-0}"; EXPECTED_CURRENT="${3:-a1ff69d34fb5}"
EVIDENCE="${4:-$ROOT/verification/wp11-023/predeploy-evidence.env}"
REPORT_DIR="$ROOT/verification/wp11-023/predeploy"; REPORT="$REPORT_DIR/report.log"
TOKEN="$(date -u +%Y%m%d%H%M%S)-$$"
IMAGE=''; KEEP_IMAGE=0; TEST_IMAGE="gomaf/wp11-023-backend-test:$TOKEN"
E2E_NET="gomaf-wp11-023-e2e-$TOKEN"; E2E_BACKEND="gomaf-predeploy-e2e-$TOKEN"; BROWSER_CONTAINER="gomaf-predeploy-browser-$TOKEN"
E2E_WORK="$(mktemp -d /tmp/gomaf-wp11-023-e2e.XXXXXX)"
mkdir -p "$REPORT_DIR" "$(dirname "$EVIDENCE")"; rm -f "$EVIDENCE"
exec > >(tee "$REPORT") 2>&1
fail(){ echo "PREDEPLOY FAIL: $*" >&2; exit 1; }
cleanup(){
  docker rm -f "$BROWSER_CONTAINER" "$E2E_BACKEND" >/dev/null 2>&1 || true
  docker network rm "$E2E_NET" >/dev/null 2>&1 || true
  docker image rm -f "$TEST_IMAGE" >/dev/null 2>&1 || true
  if [[ "$KEEP_IMAGE" != 1 && -n "$IMAGE" ]]; then
    docker image rm -f "$IMAGE" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$E2E_WORK"
}
trap cleanup EXIT INT TERM
for c in bash python3 sha256sum docker curl; do command -v "$c" >/dev/null 2>&1 || fail "$c missing"; done
docker info >/dev/null 2>&1 || fail 'Docker daemon unavailable'
[[ "$ARCHIVE_SHA" == manual || "$ARCHIVE_SHA" =~ ^[0-9a-f]{64}$ ]] || fail 'invalid archive sha'
[[ "$SEQUENCE" =~ ^[0-9]+$ ]] || fail 'invalid release sequence'
[[ "$EXPECTED_CURRENT" =~ ^[0-9a-f]{12}$ ]] || fail 'invalid expected source hash'
cd "$ROOT"
SAFE_BUILD="$ROOT/ops/root/gomaf-safe-build"
SAFE_RUN="$ROOT/ops/root/gomaf-safe-test-run"
PREFLIGHT="$ROOT/ops/root/gomaf-test-preflight"
wait_resources() {
  local label="$1" out attempt
  for attempt in $(seq 1 36); do
    if out="$("$PREFLIGHT" --resources-only 2>&1)"; then
      echo "[predeploy] resource gate PASS before $label: $out"
      return 0
    fi
    if (( attempt == 1 || attempt % 6 == 0 )); then
      echo "[predeploy] waiting for resource gate before $label: $out" >&2
    fi
    sleep 5
  done
  "$PREFLIGHT" --resources-only >&2 || true
  fail "resource gate did not recover before $label"
}
[[ -x "$SAFE_BUILD" && -x "$SAFE_RUN" && -x "$PREFLIGHT" ]] || fail 'bounded build/test helpers missing'
SOURCE_HASH="$(bash ops/source-hash.sh "$ROOT")"
[[ "$SOURCE_HASH" =~ ^[0-9a-f]{12}$ ]] || fail 'invalid candidate source hash'
ARCHIVE_SHORT="$(printf '%.12s' "$ARCHIVE_SHA")"
IMAGE="gomaf/wp11-023-approved:$SOURCE_HASH-$ARCHIVE_SHORT"
BUILT_AT="$(date -u +%Y%m%dT%H%M%SZ)"
bash ops/source-audit.sh
bash ops/ui-ux-audit.sh
bash ops/wp11-023-security-audit.sh
LOCK="$ROOT/.wp11-023-security-tools.env"; [[ -r "$LOCK" ]] || fail 'verified immutable image lock missing'
# shellcheck disable=SC1090
source "$LOCK"
for v in NODE_IMAGE MAVEN_IMAGE RUNTIME_BASE_IMAGE POSTGRES_IMAGE CADDY_IMAGE PLAYWRIGHT_IMAGE SYFT_IMAGE TRIVY_IMAGE; do
  ref="${!v:-}"; [[ "$ref" =~ @sha256:[0-9a-f]{64}$ ]] || fail "$v is not digest-pinned"
done
[[ "${ADOPTIUM_RELEASE:-}" == 'jdk-21.0.12.1+1' ]] || fail 'wrong Adoptium CSPU release lock'
[[ "${TEMURIN_JDK_SHA256:-}" =~ ^[0-9a-f]{64}$ && "${TEMURIN_JRE_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || fail 'invalid Temurin checksums'

# Dockerfile itself executes npm ci/lint/typecheck/Vitest/build and mvn verify.
# Every heavy build is bounded and cancelled if host memory/swap crosses the
# post-incident safety floor.
wait_resources 'backend test image build'
echo '[predeploy] bounded build backend test stage'
"$SAFE_BUILD" "gomaf-predeploy-backend-build-$TOKEN" 1024 0.75   --pull --progress=plain --target backend-build -t "$TEST_IMAGE" "$ROOT"
wait_resources 'exact production image build'
echo '[predeploy] bounded build exact production candidate'
docker image rm -f "$IMAGE" >/dev/null 2>&1 || true
"$SAFE_BUILD" "gomaf-predeploy-app-build-$TOKEN" 1024 0.75   --pull --progress=plain   --label "com.gomaf.application=classic-mafia"   --label "com.gomaf.source-hash=$SOURCE_HASH"   --label "com.gomaf.built-at=$BUILT_AT"   -t "$IMAGE" "$ROOT"
IMAGE_ID="$(docker image inspect -f '{{.Id}}' "$IMAGE")"
[[ "$IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'invalid exact candidate image id'
[[ "$(docker image inspect -f '{{index .Config.Labels "com.gomaf.source-hash"}}' "$IMAGE")" == "$SOURCE_HASH" ]] || fail 'candidate image source label mismatch'
[[ "$(docker image inspect -f '{{index .Config.Labels "com.gomaf.application"}}' "$IMAGE")" == classic-mafia ]] || fail 'candidate image application label mismatch'
[[ "$(docker image inspect -f '{{.Config.User}}' "$IMAGE")" == 10001 ]] || fail 'runtime UID != 10001'
"$SAFE_RUN" "gomaf-predeploy-java-$TOKEN" 128 0.20 --rm --entrypoint java "$IMAGE" -version   2>&1 | tee "$REPORT_DIR/java-version.txt" | grep -F '21.0.12.1' >/dev/null || fail 'runtime Java is not 21.0.12.1'
"$SAFE_RUN" "gomaf-predeploy-tools-$TOKEN" 128 0.20 --rm --entrypoint sh "$IMAGE"   -ec '! command -v node && ! command -v npm && ! command -v mvn && ! command -v javac'   || fail 'build tool leaked into runtime image'

wait_resources 'SBOM scan'
bash ops/generate-sbom.sh "$IMAGE" "$REPORT_DIR/sbom"
wait_resources 'vulnerability scan'
bash ops/online-vulnerability-scan.sh "$IMAGE" "$REPORT_DIR/vulnerability"
[[ "$ARCHIVE_SHA" != manual ]] || fail 'manual mode cannot produce production PREDEPLOY PASS'
wait_resources 'migration rehearsal'
bash ops/migration-rehearsal.sh "$IMAGE" "$ROOT" "" "$TEST_IMAGE"

# Browser E2E runs entirely in containers. The backend build stage contains test
# classes/resources; spring-boot:test-run uses the test runtime classpath and test resources
# and E2eDataInitializer.
wait_resources 'browser E2E backend'
echo '[predeploy] bounded browser E2E'
docker network create "$E2E_NET" >/dev/null
"$SAFE_RUN" "$E2E_BACKEND" 512 0.50 -d --network "$E2E_NET" --network-alias backend   -e SERVER_ADDRESS=0.0.0.0 -w /workspace/backend --entrypoint mvn "$TEST_IMAGE"   -B -DskipTests -Dspring-boot.run.profiles=e2e spring-boot:test-run >/dev/null
E2E_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$E2E_BACKEND")"
[[ "$E2E_IP" =~ ^[0-9a-fA-F:.]+$ ]] || fail 'E2E backend IP unavailable'
READY=0
for _ in $(seq 1 120); do
  if curl -fsS --max-time 3 "http://$E2E_IP:8787/healthz" >/dev/null 2>&1; then READY=1; break; fi
  if ! docker inspect -f '{{.State.Running}}' "$E2E_BACKEND" 2>/dev/null | grep -qx true; then
    docker logs --tail=250 "$E2E_BACKEND" >&2 || true; fail 'E2E backend exited'
  fi
  sleep 1
done
[[ "$READY" == 1 ]] || { docker logs --tail=250 "$E2E_BACKEND" >&2 || true; fail 'E2E backend not ready'; }
cp -a "$ROOT/frontend/." "$E2E_WORK/"
rm -rf "$E2E_WORK/node_modules" "$E2E_WORK/playwright-report" "$E2E_WORK/test-results"
wait_resources 'browser E2E runner'
"$SAFE_RUN" "$BROWSER_CONTAINER" 768 0.75 --rm --network "$E2E_NET" --shm-size=384m   -e PLAYWRIGHT_BASE_URL=http://backend:8787 -e CI=1   -v "$E2E_WORK:/work" -w /work "$PLAYWRIGHT_IMAGE"   bash -ec 'npm ci --no-audit --no-fund && npm run e2e'
[[ -d "$E2E_WORK/playwright-report" ]] && cp -a "$E2E_WORK/playwright-report" "$REPORT_DIR/playwright-report"
[[ -d "$E2E_WORK/test-results" ]] || fail 'Playwright test-results missing; screenshot evidence unavailable'
SCREENSHOT_COUNT="$(find "$E2E_WORK/test-results" -type f -name '*.png' | wc -l | tr -d ' ')"
[[ "$SCREENSHOT_COUNT" == 215 ]] || fail "expected 215 Playwright screenshots, got $SCREENSHOT_COUNT"
cp -a "$E2E_WORK/test-results" "$REPORT_DIR/test-results"
(
  cd "$REPORT_DIR/test-results"
  find . -type f -name '*.png' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SCREENSHOT-SHA256SUMS.txt
  sha256sum -c SCREENSHOT-SHA256SUMS.txt >/dev/null
)
SCREENSHOT_SHA="$(sha256sum "$REPORT_DIR/test-results/SCREENSHOT-SHA256SUMS.txt" | awk '{print $1}')"

docker rm -f "$E2E_BACKEND" >/dev/null; E2E_BACKEND=''
docker network rm "$E2E_NET" >/dev/null; E2E_NET=''
REPORT_SHA="$(sha256sum "$REPORT" | awk '{print $1}')"
SBOM_SHA="$(sha256sum "$REPORT_DIR/sbom/SHA256SUMS.txt" | awk '{print $1}')"
VULN_SHA="$(sha256sum "$REPORT_DIR/vulnerability/SHA256SUMS.txt" | awk '{print $1}')"
cat > "$EVIDENCE" <<ENV
STATUS=PASS
WORK_PACKAGE=WP11-023
ARCHIVE_SHA256=$ARCHIVE_SHA
RELEASE_SEQUENCE=$SEQUENCE
EXPECTED_CURRENT_SOURCE_HASH=$EXPECTED_CURRENT
CANDIDATE_SOURCE_HASH=$SOURCE_HASH
CANDIDATE_IMAGE=$IMAGE
CANDIDATE_IMAGE_ID=$IMAGE_ID
REPORT_SHA256=$REPORT_SHA
SBOM_MANIFEST_SHA256=$SBOM_SHA
VULNERABILITY_MANIFEST_SHA256=$VULN_SHA
SCREENSHOT_MANIFEST_SHA256=$SCREENSHOT_SHA
SCREENSHOT_COUNT=$SCREENSHOT_COUNT
FINISHED_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ENV
chmod 0600 "$EVIDENCE"
KEEP_IMAGE=1
echo "WP11-023 PREDEPLOY PASS exact_image_id=$IMAGE_ID source_hash=$SOURCE_HASH"
