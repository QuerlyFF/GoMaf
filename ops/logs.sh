#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd docker
compose logs -f --tail=200 "${1:-app}"
