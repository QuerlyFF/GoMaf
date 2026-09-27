#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${1:-}"
OUT="${2:-$ROOT/verification/wp11-023/sbom}"
TOOLS="$ROOT/.wp11-023-security-tools.env"
[[ -n "$IMAGE" ]] || { echo 'SBOM FAIL: image argument required' >&2; exit 1; }
[[ -r "$TOOLS" ]] || { echo 'SBOM FAIL: security tools manifest missing' >&2; exit 1; }
# shellcheck disable=SC1090
source "$TOOLS"
[[ "${SYFT_IMAGE:-}" =~ @sha256:[0-9a-f]{64}$ ]] || { echo 'SBOM FAIL: SYFT_IMAGE is not digest-pinned' >&2; exit 1; }
command -v docker >/dev/null || { echo 'SBOM FAIL: docker missing' >&2; exit 1; }
SAFE_RUN="$ROOT/ops/root/gomaf-safe-test-run"
[[ -x "$SAFE_RUN" ]] || { echo 'SBOM FAIL: bounded container runner missing' >&2; exit 1; }
docker image inspect "$IMAGE" >/dev/null
mkdir -p "$OUT"
TOKEN="$(date -u +%Y%m%d%H%M%S)-$$"
SOURCE_C="gomaf-predeploy-syft-source-$TOKEN"
IMAGE_C="gomaf-predeploy-syft-image-$TOKEN"
cleanup(){
  docker rm -f "$SOURCE_C" "$IMAGE_C" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

"$SAFE_RUN" "$SOURCE_C" 512 0.50 \
  --rm -e GOMAXPROCS=1 -v "$ROOT:/src:ro" "$SYFT_IMAGE" dir:/src -o cyclonedx-json \
  > "$OUT/source.cdx.json"
"$SAFE_RUN" "$IMAGE_C" 512 0.50 \
  --rm -e GOMAXPROCS=1 -v /var/run/docker.sock:/var/run/docker.sock "$SYFT_IMAGE" "$IMAGE" -o cyclonedx-json \
  > "$OUT/image.cdx.json"

python3 - "$OUT/source.cdx.json" "$OUT/image.cdx.json" <<'PY'
import json,sys
for p in sys.argv[1:]:
    x=json.load(open(p))
    if not isinstance(x,dict) or not x.get('bomFormat') or 'components' not in x:
        raise SystemExit('invalid CycloneDX SBOM: '+p)
PY
(cd "$OUT" && sha256sum source.cdx.json image.cdx.json > SHA256SUMS.txt)
echo 'SBOM PASS'
