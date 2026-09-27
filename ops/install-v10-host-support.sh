#!/usr/bin/env bash
set -Eeuo pipefail
echo "This V10 host-support installer is retired by WP11-023 because it grants deployment-user privileges that violate the current security model." >&2
echo "Use ops/install-wp11-023-host-security.sh with separate release/opsadmin/deployment keys." >&2
exit 1
