#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd python3
validate_prod_config
runtime_prepare

BACKUP_DIR="$(env_get BACKUP_DIR)"
RETENTION_DAYS="$(env_get BACKUP_RETENTION_DAYS)"
MIN_KEEP="$(env_get_default BACKUP_MIN_KEEP 3)"
MIN_FREE_MB="$(env_get_default BACKUP_MIN_FREE_MB 2048)"
TARGET_FREE_MB="$(env_get_default BACKUP_TARGET_FREE_MB 3072)"
REQUIRED_EXTRA_BYTES="${1:-0}"
[[ "$REQUIRED_EXTRA_BYTES" =~ ^[0-9]+$ ]] || die "backup-maintenance: required bytes must be numeric."

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

BACKUP_DIR="$BACKUP_DIR" \
RETENTION_DAYS="$RETENTION_DAYS" \
MIN_KEEP="$MIN_KEEP" \
MIN_FREE_MB="$MIN_FREE_MB" \
TARGET_FREE_MB="$TARGET_FREE_MB" \
REQUIRED_EXTRA_BYTES="$REQUIRED_EXTRA_BYTES" \
RUNTIME_DIR="$RUNTIME_DIR" \
python3 <<'PY'
from pathlib import Path
import os
import time

root = Path(os.environ['BACKUP_DIR']).resolve()
retention_days = int(os.environ['RETENTION_DAYS'])
min_keep = int(os.environ['MIN_KEEP'])
min_free = int(os.environ['MIN_FREE_MB']) * 1024 * 1024
target_free = int(os.environ['TARGET_FREE_MB']) * 1024 * 1024
extra = int(os.environ['REQUIRED_EXTRA_BYTES'])
runtime = Path(os.environ['RUNTIME_DIR'])
now = time.time()

def free_bytes():
    st = os.statvfs(root)
    return st.f_bavail * st.f_frsize

def dumps():
    return sorted(
        [p for p in root.glob('gomaf-*.dump') if p.is_file()],
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )

items = dumps()
protected = {p.resolve() for p in items[:min_keep]}
for marker in ('last_predeploy_backup', 'last_successful_backup'):
    f = runtime / marker
    try:
        value = Path(f.read_text().strip()).resolve()
    except Exception:
        continue
    if value.parent == root and value.exists():
        protected.add(value)

# Keep at least the newest copy of high-value recovery classes even if not in MIN_KEEP.
for reason in ('predeploy', 'prerollback', 'prerestore'):
    match = next((p for p in items if f'-{reason}-' in p.name), None)
    if match is not None:
        protected.add(match.resolve())

def remove_dump(path: Path, why: str):
    resolved = path.resolve()
    if resolved in protected:
        return False
    if resolved.parent != root or not path.name.startswith('gomaf-') or path.suffix != '.dump':
        raise SystemExit(f'Unsafe backup candidate: {path}')
    sidecar = Path(str(path) + '.sha256')
    path.unlink(missing_ok=True)
    sidecar.unlink(missing_ok=True)
    print(f'DELETED {path.name} reason={why}')
    return True

# Normal retention: expire old low-priority backups first, then old recovery backups.
cutoff = now - retention_days * 86400
old = [p for p in reversed(items) if p.stat().st_mtime < cutoff]
old.sort(key=lambda p: (0 if any(f'-{r}-' in p.name for r in ('daily','manual')) else 1, p.stat().st_mtime))
for p in old:
    remove_dump(p, 'retention')

# Low-space mode. Reserve both the configured floor and enough bytes for the next dump.
required = max(target_free, min_free + extra)
if free_bytes() < required:
    remaining = dumps()
    candidates = [p for p in reversed(remaining) if p.resolve() not in protected]
    candidates.sort(key=lambda p: (0 if any(f'-{r}-' in p.name for r in ('daily','manual')) else 1, p.stat().st_mtime))
    for p in candidates:
        if free_bytes() >= required:
            break
        remove_dump(p, 'low-space')

free = free_bytes()
print(f'FREE_BYTES={free}')
print(f'REQUIRED_BYTES={required}')
print(f'PROTECTED_DUMPS={len(protected)}')
if free < required:
    raise SystemExit(2)
PY
