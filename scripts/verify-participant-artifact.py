#!/usr/bin/env python3
"""Reject participant plugin archives containing secrets or developer-only paths."""

from __future__ import annotations

import argparse
import re
import tempfile
import zipfile
from pathlib import Path
from typing import BinaryIO


FORBIDDEN_NAMES = (".env", "local-dev", ".venv")
FORBIDDEN_TEXT = (
    re.compile(rb"(?:/|\\)(?:local-dev|code4me2-server)(?:/|\\)", re.IGNORECASE),
    re.compile(rb"(?:file:/{2,3}|[A-Za-z]:\\Users\\)[^\r\n\x00]+code4me", re.IGNORECASE),
    re.compile(rb"\bsk-[A-Za-z0-9_-]{20,}\b"),
    re.compile(rb"\bgh[oprsu]_[A-Za-z0-9]{20,}\b"),
    re.compile(
        rb"\b(?:OPENAI|OPENROUTER|GROQ)_API_KEY\s*=\s*[\"']?(?:sk-|gsk_)[A-Za-z0-9_-]{16,}"
    ),
)
MAX_NESTED_ARCHIVE_BYTES = 512 * 1024 * 1024


def copy_bounded(source: BinaryIO, target: BinaryIO, *, maximum: int) -> None:
    copied = 0
    while True:
        chunk = source.read(min(1024 * 1024, maximum - copied + 1))
        if not chunk:
            return
        copied += len(chunk)
        if copied > maximum:
            raise ValueError("expanded nested archive exceeds verification limit")
        target.write(chunk)


def inspect_zip(
    label: str,
    source: Path | BinaryIO,
    findings: list[str],
    *,
    depth: int = 0,
) -> None:
    if depth > 4:
        findings.append(f"{label}: archive nesting exceeds verification limit")
        return
    with zipfile.ZipFile(source) as archive:
        for member in archive.infolist():
            lowered = member.filename.lower()
            if any(marker in lowered for marker in FORBIDDEN_NAMES):
                findings.append(f"{label}!{member.filename}: forbidden path")
            if member.is_dir():
                continue
            if lowered.endswith((".jar", ".zip")):
                if member.file_size > MAX_NESTED_ARCHIVE_BYTES:
                    findings.append(f"{label}!{member.filename}: nested archive is too large")
                    continue
                with archive.open(member) as nested_input, tempfile.SpooledTemporaryFile(
                    max_size=16 * 1024 * 1024
                ) as nested:
                    try:
                        copy_bounded(
                            nested_input,
                            nested,
                            maximum=MAX_NESTED_ARCHIVE_BYTES,
                        )
                    except ValueError as error:
                        findings.append(f"{label}!{member.filename}: {error}")
                        continue
                    nested.seek(0)
                    try:
                        inspect_zip(
                            f"{label}!{member.filename}", nested, findings, depth=depth + 1
                        )
                    except zipfile.BadZipFile:
                        findings.append(f"{label}!{member.filename}: invalid nested archive")
                continue
            if member.file_size > 50 * 1024 * 1024:
                continue
            data = archive.read(member)
            # Native wheels routinely contain upstream compiler source paths
            # in debug/string tables. Release credentials and project-local
            # path configuration are text resources, so scanning binary blobs
            # creates false positives without improving that assurance.
            if b"\x00" in data[:8192]:
                continue
            for pattern in FORBIDDEN_TEXT:
                if pattern.search(data):
                    findings.append(f"{label}!{member.filename}: matched {pattern.pattern!r}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    findings: list[str] = []
    inspect_zip(str(args.archive), args.archive, findings)
    if findings:
        raise SystemExit("participant artifact verification failed:\n" + "\n".join(findings))
    print(f"participant artifact verified: {args.archive}")


if __name__ == "__main__":
    main()
