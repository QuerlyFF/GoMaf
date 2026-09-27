#!/usr/bin/env python3
"""Fail-closed ZIP validation/extraction for GoMaf WP11-023 release archives."""
from __future__ import annotations

import os
import stat
import sys
import zipfile
from pathlib import Path, PurePosixPath

MAX_FILES = 5000
MAX_UNCOMPRESSED = 128 * 1024 * 1024


def die(msg: str) -> "NoReturn":
    raise SystemExit(msg)


def inspect_archive(path: Path) -> tuple[str, list[zipfile.ZipInfo]]:
    if not path.is_file() or path.is_symlink():
        die("archive missing/unsafe")
    roots: set[str] = set()
    seen: set[str] = set()
    total = 0
    with zipfile.ZipFile(path) as zf:
        infos = zf.infolist()
        if not infos or len(infos) > MAX_FILES:
            die("invalid ZIP entry count")
        for info in infos:
            name = info.filename
            if "\x00" in name or "\\" in name:
                die("invalid ZIP path")
            p = PurePosixPath(name)
            if p.is_absolute() or not p.parts or any(part in ("", ".", "..") for part in p.parts):
                die("unsafe ZIP path: " + name)
            norm = p.as_posix().rstrip("/")
            if not norm or norm in seen:
                die("duplicate ZIP path: " + name)
            seen.add(norm)
            roots.add(p.parts[0])
            if info.flag_bits & 1:
                die("encrypted ZIP entry not allowed")
            if info.file_size < 0:
                die("invalid ZIP entry size")
            total += info.file_size
            if total > MAX_UNCOMPRESSED:
                die("ZIP uncompressed size limit exceeded")
            mode = (info.external_attr >> 16) & 0xFFFF
            kind = stat.S_IFMT(mode)
            if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
                die("non-regular ZIP entry not allowed: " + name)
        if len(roots) != 1:
            die("ZIP must contain exactly one top-level directory")
    return next(iter(roots)), infos


def extract_archive(archive: Path, destination: Path) -> str:
    top, _ = inspect_archive(archive)
    if destination.exists():
        if destination.is_symlink() or not destination.is_dir():
            die("destination missing/unsafe")
        if any(destination.iterdir()):
            die("destination must be empty")
    else:
        destination.mkdir(mode=0o700, parents=True)
    dest = destination.resolve(strict=True)

    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            p = PurePosixPath(info.filename)
            target = dest.joinpath(*p.parts)
            mode = (info.external_attr >> 16) & 0xFFFF
            kind = stat.S_IFMT(mode)
            is_dir = info.is_dir() or kind == stat.S_IFDIR
            perms = stat.S_IMODE(mode) if mode else (0o755 if is_dir else 0o644)
            perms &= 0o777
            if is_dir:
                target.mkdir(mode=perms or 0o755, parents=True, exist_ok=True)
                os.chmod(target, perms or 0o755)
                continue
            target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                die("duplicate/existing extraction target: " + info.filename)
            with zf.open(info, "r") as src, open(target, "xb") as dst:
                while True:
                    chunk = src.read(1024 * 1024)
                    if not chunk:
                        break
                    dst.write(chunk)
            os.chmod(target, perms or 0o644)
    return top


def main() -> int:
    if len(sys.argv) not in (3, 4) or sys.argv[1] not in {"inspect", "extract"}:
        print(f"Usage: {sys.argv[0]} inspect <archive.zip> | extract <archive.zip> <empty-dir>", file=sys.stderr)
        return 2
    cmd = sys.argv[1]
    archive = Path(sys.argv[2])
    if cmd == "inspect":
        top, _ = inspect_archive(archive)
        print(top)
        return 0
    if len(sys.argv) != 4:
        return 2
    top = extract_archive(archive, Path(sys.argv[3]))
    print(top)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
