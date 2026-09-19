#!/usr/bin/env python3
"""Derive the participant research-proxy platform matrix from a runtime release.

The participant release's supported platforms are the native agent platforms
declared by the managed runtime release manifest
(``code4me-managed-runtime-release.json``, produced by
``code4me2-server/packaging/create_release_manifest.py``). Its
``artifacts[].archive`` names follow ``code4me-agent-<os>-<arch>.zip``; this
script maps them onto the supported research-proxy matrix and its
PyInstaller-capable GitHub runner labels.

Output (stdout and, when requested, ``$GITHUB_OUTPUT``):

    platforms=macos-aarch64,macos-x64,linux-x64,windows-x64
    matrix=[{"platform": "macos-aarch64", "runner": "macos-14", ...}, ...]

The ``matrix`` value feeds ``strategy.matrix.include``; ``platforms`` feeds the
strict staging flag ``-PresearchProxyPlatforms=``.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Supported participant research-proxy matrix. PyInstaller cannot cross-compile,
# so every entry names a runner whose CPU architecture matches the bundle.
# (`macos-13` is retired; Intel macOS runners are labeled `macos-15-intel`.)
SUPPORTED_PLATFORMS = (
    ("macos-aarch64", "macos-14"),
    ("macos-x64", "macos-15-intel"),
    ("linux-x64", "ubuntu-24.04"),
    ("windows-x64", "windows-2022"),
)
ARCHIVE_PATTERN = re.compile(
    r"^code4me-agent-(?P<os>macos|windows|linux)-(?P<arch>arm64|aarch64|x64|amd64)\.zip$"
)
ARCH_ALIASES = {"arm64": "aarch64", "aarch64": "aarch64", "x64": "x64", "amd64": "x64"}


def declared_platforms(manifest: dict) -> list[str]:
    if manifest.get("manifest_version") != 1:
        raise SystemExit("unsupported managed runtime release manifest version")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise SystemExit("managed runtime release manifest contains no artifacts")
    declared: list[str] = []
    for artifact in artifacts:
        archive = Path(str(artifact.get("archive", ""))).name if isinstance(artifact, dict) else ""
        match = ARCHIVE_PATTERN.fullmatch(archive)
        if match is None:
            print(f"ignoring unrecognized runtime artifact: {archive!r}", file=sys.stderr)
            continue
        platform_id = f"{match.group('os')}-{ARCH_ALIASES[match.group('arch')]}"
        if platform_id not in declared:
            declared.append(platform_id)
    if not declared:
        raise SystemExit(
            "no platform could be derived from the managed runtime release manifest; "
            "expected code4me-agent-<os>-<arch>.zip artifacts"
        )
    return declared


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_manifest", type=Path)
    parser.add_argument(
        "--github-output",
        type=Path,
        help="Append 'platforms=' and 'matrix=' lines for $GITHUB_OUTPUT.",
    )
    args = parser.parse_args()

    manifest = json.loads(args.release_manifest.read_text(encoding="utf-8"))
    declared = declared_platforms(manifest)

    matrix = [
        {
            "platform": platform_id,
            "runner": runner,
            "executable": (
                "telemetry-acp-proxy.exe"
                if platform_id.startswith("windows-")
                else "telemetry-acp-proxy"
            ),
        }
        for platform_id, runner in SUPPORTED_PLATFORMS
        if platform_id in declared
    ]
    if not matrix:
        raise SystemExit(
            "the managed runtime release declares no platform in the supported research "
            f"proxy matrix {[platform for platform, _ in SUPPORTED_PLATFORMS]}; found {declared}"
        )
    missing = [
        platform_id for platform_id, _ in SUPPORTED_PLATFORMS if platform_id not in declared
    ]
    if missing:
        print(
            f"warning: the runtime release declares no archive for {missing}; "
            "no research proxy will be built for those platforms",
            file=sys.stderr,
        )
    unsupported = [
        platform_id
        for platform_id in declared
        if platform_id not in {platform for platform, _ in SUPPORTED_PLATFORMS}
    ]
    if unsupported:
        print(
            f"warning: no research proxy build is defined for {unsupported}; "
            "those participants would be refused at launch",
            file=sys.stderr,
        )

    lines = [
        "platforms=" + ",".join(entry["platform"] for entry in matrix),
        "matrix=" + json.dumps(matrix, separators=(",", ":")),
    ]
    print("\n".join(lines))
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
