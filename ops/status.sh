#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd docker
validate_prod_config
compose ps
DOMAIN="$(env_get DOMAIN)"
echo
curl -fsS --max-time 10 "https://$DOMAIN/healthz" || true
echo
