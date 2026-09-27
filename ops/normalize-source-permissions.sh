#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
[[ "$ROOT" == /* ]] || ROOT="$(cd "$ROOT" && pwd)"
[[ -d "$ROOT/ops" && -d "$ROOT/docker" && -f "$ROOT/backend/pom.xml" && -f "$ROOT/compose.prod.yaml" ]] \
  || { printf 'ERROR: не найден корень GoMaf source tree: %s\n' "$ROOT" >&2; exit 1; }

# ZIP extraction through Python's stdlib does not restore Unix executable bits.
# Restore execute permission only for the known script surfaces; application
# source/config/docs stay non-executable.
find "$ROOT/ops" -maxdepth 1 -type f -name '*.sh' -exec chmod 0755 -- {} +
find "$ROOT/ops/root" -maxdepth 1 -type f -exec chmod 0755 -- {} +
find "$ROOT/docker" -maxdepth 1 -type f -name '*.sh' -exec chmod 0755 -- {} +

printf 'SOURCE PERMISSIONS NORMALIZED: %s\n' "$ROOT"
