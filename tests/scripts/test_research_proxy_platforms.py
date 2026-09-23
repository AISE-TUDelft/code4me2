"""Focused tests for scripts/research-proxy-platforms.py.

Run from the plugin repository root:

    ../code4me2-server/.venv/bin/python -m pytest -q tests/scripts
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN_ROOT / "scripts" / "research-proxy-platforms.py"


def release_manifest(archives: list[str]) -> dict:
    return {
        "manifest_version": 1,
        "managed_protocol_version": "1",
        "runtime_version": "1.2.3",
        "server_commit": "abc1234",
        "artifacts": [
            {"archive": f"code4me-runtime/{archive}", "sha256": "0" * 64} for archive in archives
        ],
    }


def run_script(tmp_path: Path, manifest: dict) -> subprocess.CompletedProcess:
    manifest_path = tmp_path / "release.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    output_path = tmp_path / "github-output.txt"
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(manifest_path), "--github-output", str(output_path)],
        capture_output=True,
        text=True,
        timeout=60,
    )


def test_full_release_derives_the_supported_matrix(tmp_path: Path) -> None:
    result = run_script(
        tmp_path,
        release_manifest(
            [
                "code4me-agent-macos-arm64.zip",
                "code4me-agent-macos-x64.zip",
                "code4me-agent-windows-x64.zip",
                "code4me-agent-linux-x64.zip",
            ]
        ),
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "platforms=macos-aarch64,macos-x64,linux-x64,windows-x64" in result.stdout

    outputs = (tmp_path / "github-output.txt").read_text(encoding="utf-8")
    matrix_line = next(line for line in outputs.splitlines() if line.startswith("matrix="))
    matrix = json.loads(matrix_line.removeprefix("matrix="))
    by_platform = {entry["platform"]: entry for entry in matrix}
    assert by_platform["macos-aarch64"]["runner"] == "macos-15"
    assert by_platform["macos-x64"]["runner"] == "macos-15-intel"
    assert by_platform["linux-x64"]["runner"] == "ubuntu-24.04"
    assert by_platform["windows-x64"]["runner"] == "windows-2022"
    assert by_platform["windows-x64"]["executable"] == "telemetry-acp-proxy.exe"
    assert by_platform["linux-x64"]["executable"] == "telemetry-acp-proxy"


def test_subset_release_only_builds_declared_platforms(tmp_path: Path) -> None:
    result = run_script(
        tmp_path,
        release_manifest(["code4me-agent-macos-arm64.zip", "code4me-agent-linux-x64.zip"]),
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "platforms=macos-aarch64,linux-x64" in result.stdout
    assert "windows-x64" not in result.stdout
    assert "warning: the runtime release declares no archive for" in result.stderr


def test_unrecognized_release_fails(tmp_path: Path) -> None:
    result = run_script(tmp_path, release_manifest(["agent.zip"]))
    assert result.returncode != 0
    assert "no platform could be derived" in result.stdout + result.stderr
