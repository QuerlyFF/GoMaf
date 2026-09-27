#!/usr/bin/env bash
set -Eeuo pipefail
command -v sudo >/dev/null 2>&1 || { echo "sudo не найден" >&2; exit 1; }
sudo -n /usr/local/sbin/gomaf-export-logs
