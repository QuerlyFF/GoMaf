#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
[[ -d "$ROOT" ]] || { echo "source-hash: root missing: $ROOT" >&2; exit 1; }

required_dirs=(backend frontend ops config client caddy docker)
required_files=(Dockerfile .dockerignore compose.prod.yaml .wp11-023-security-tools.env)

for path in "${required_dirs[@]}" "${required_files[@]}"; do
  [[ -e "$ROOT/$path" ]] || { echo "source-hash: required path missing: $path" >&2; exit 1; }
done

if (
  cd "$ROOT"
  find "${required_dirs[@]}" "${required_files[@]}" -type l -print -quit | grep -q .
); then
  echo "source-hash: symlink found in deployment-critical source set" >&2
  exit 1
fi

(
  cd "$ROOT"
  find "${required_dirs[@]}" "${required_files[@]}" -type f -print0     | LC_ALL=C sort -z     | xargs -0 sha256sum     | sha256sum     | awk '{print substr($1,1,12)}'
)
