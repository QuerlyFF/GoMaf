#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd docker
runtime_prepare

KEEP="${GOMAF_RELEASES_TO_KEEP:-5}"
[[ "$KEEP" =~ ^[0-9]+$ && "$KEEP" -ge 2 ]] || die "GOMAF_RELEASES_TO_KEEP должен быть числом >= 2."
CURRENT_RELEASE="$(cat "$ACTIVE_RELEASE_FILE" 2>/dev/null || true)"
PREVIOUS_RELEASE="$(cat "$RUNTIME_DIR/previous_release" 2>/dev/null || true)"

mapfile -t releases < <(find "$RELEASES_DIR" -mindepth 1 -maxdepth 1 -type d ! -name '.staging-*' -printf '%T@ %p\n' | sort -nr | awk '{print $2}')
index=0
for dir in "${releases[@]}"; do
  ((index+=1))
  if [[ "$dir" == "$CURRENT_RELEASE" || "$dir" == "$PREVIOUS_RELEASE" || "$index" -le "$KEEP" ]]; then
    continue
  fi
  tag="gomaf/app:$(basename "$dir")"
  docker image rm "$tag" >/dev/null 2>&1 || true
  # Immutable snapshots are mode read-only while retained. Thaw only the
  # already-selected obsolete tree immediately before removing it.
  chmod -R u+w -- "$dir"
  rm -rf -- "$dir"
  log "Удалён старый release: $dir"
done

# Old BuildKit cache is not runtime state. Removing cache older than 7 days prevents
# source builds from consuming the entire VPS disk while keeping recent layers fast.
docker builder prune -f --filter 'until=168h' >/dev/null 2>&1 || true
