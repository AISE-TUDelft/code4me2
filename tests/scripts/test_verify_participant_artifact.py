"""Focused tests for scripts/verify-participant-artifact.py.

Run from the plugin repository root:

    ../code4me2-server/.venv/bin/python -m pytest -q tests/scripts

The fixtures build tiny plugin ZIPs whose nested plugin jar contains a
``research-runtime/proxy-manifest.json`` plus its payload files, mirroring the
layout `stageResearchProxy` produces.
"""

from __future__ import annotations

import hashlib
import io
import json
import subprocess
import sys
import zipfile
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parents[2]
VERIFIER = PLUGIN_ROOT / "scripts" / "verify-participant-artifact.py"
RUNTIME_PREFIX = "research-runtime/"


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def file_record(path: str, payload: bytes, *, executable: bool = False) -> dict:
    return {
        "path": path,
        "sha256": sha256(payload),
        "size": len(payload),
        "executable": executable,
    }


def agent_block(payloads: dict[str, bytes], entrypoint: str) -> dict:
    return {
        "entrypoint": [entrypoint, "--managed"],
        "digest": sha256(payloads[entrypoint]),
        "files": [
            file_record(path, payload, executable=True) for path, payload in payloads.items()
        ],
    }


def proxy_platform(
    platform_id: str,
    payloads: dict[str, bytes],
    *,
    self_contained: bool = True,
    entrypoint: str | None = None,
    agent: dict | None = None,
) -> dict:
    os_name, arch = platform_id.split("-", 1)
    selected = entrypoint or next(iter(payloads))
    platform = {
        "os": os_name,
        "arch": arch,
        "self_contained": self_contained,
        "entrypoint": [selected],
        "files": [
            file_record(path, payload, executable=(path == selected))
            for path, payload in payloads.items()
        ],
    }
    if agent is not None:
        platform["agent"] = agent
    return platform


def valid_manifest() -> tuple[dict, dict[str, bytes]]:
    mac_proxy = b"#!/bin/sh\nexit 0\n"
    mac_lib = b"mach-o payload"
    mac_agent = b"#!/bin/sh\n# managed agent\n"
    linux_proxy = b"\x7fELF proxy payload"
    linux_lib = b"elf payload"
    payloads = {
        "platforms/macos-aarch64/telemetry-acp-proxy": mac_proxy,
        "platforms/macos-aarch64/_internal/lib.dylib": mac_lib,
        "agents/macos-aarch64/code4me2-agent": mac_agent,
        "platforms/linux-x64/telemetry-acp-proxy": linux_proxy,
        "platforms/linux-x64/_internal/lib.so": linux_lib,
    }
    manifest = {
        "schema_version": "1",
        "platforms": [
            proxy_platform(
                "macos-aarch64",
                {
                    "platforms/macos-aarch64/telemetry-acp-proxy": mac_proxy,
                    "platforms/macos-aarch64/_internal/lib.dylib": mac_lib,
                },
                agent=agent_block(
                    {"agents/macos-aarch64/code4me2-agent": mac_agent},
                    "agents/macos-aarch64/code4me2-agent",
                ),
            ),
            proxy_platform(
                "linux-x64",
                {
                    "platforms/linux-x64/telemetry-acp-proxy": linux_proxy,
                    "platforms/linux-x64/_internal/lib.so": linux_lib,
                },
            ),
        ],
    }
    return manifest, payloads


def write_plugin_zip(
    path: Path,
    manifest: dict | None,
    payloads: dict[str, bytes],
    *,
    extra_members: dict[str, bytes] | None = None,
) -> Path:
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as archive:
        if manifest is not None:
            archive.writestr(f"{RUNTIME_PREFIX}proxy-manifest.json", json.dumps(manifest))
        for relative, data in payloads.items():
            archive.writestr(f"{RUNTIME_PREFIX}{relative}", data)
    with zipfile.ZipFile(path, "w") as outer:
        outer.writestr("client/lib/client-1.0.0.jar", jar.getvalue())
        for name, data in (extra_members or {}).items():
            outer.writestr(name, data)
    return path


def run_verifier(archive: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(VERIFIER), str(archive)],
        capture_output=True,
        text=True,
        timeout=120,
    )


def test_valid_self_contained_artifact_passes(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive = write_plugin_zip(tmp_path / "valid.zip", manifest, payloads)
    result = run_verifier(archive)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "participant artifact verified" in result.stdout


def test_source_only_artifact_fails(tmp_path: Path) -> None:
    payloads = {
        "run.py": b"print('dev launcher')\n",
        "py/telemetry_acp_proxy/__init__.py": b"",
    }
    manifest = {
        "schema_version": "1",
        "platforms": [
            proxy_platform("macos-aarch64", payloads, self_contained=False, entrypoint="run.py")
        ],
    }
    archive = write_plugin_zip(tmp_path / "source-only.zip", manifest, payloads)
    result = run_verifier(archive)
    assert result.returncode != 0
    combined = result.stdout + result.stderr
    assert "self_contained must be true" in combined
    assert "source fallback" in combined


def test_run_py_entrypoint_fails_even_when_declared_self_contained(tmp_path: Path) -> None:
    payloads = {"run.py": b"print('dev launcher')\n"}
    manifest = {
        "schema_version": "1",
        "platforms": [proxy_platform("linux-x64", payloads, entrypoint="run.py")],
    }
    archive = write_plugin_zip(tmp_path / "run-py.zip", manifest, payloads)
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "source fallback" in result.stdout + result.stderr


def test_digest_mismatched_manifest_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    manifest["platforms"][0]["files"][0]["sha256"] = "0" * 64
    archive = write_plugin_zip(tmp_path / "digest-mismatch.zip", manifest, payloads)
    result = run_verifier(archive)
    assert result.returncode != 0
    combined = result.stdout + result.stderr
    assert "sha256 mismatch" in combined


def test_agent_digest_mismatch_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    manifest["platforms"][0]["agent"]["digest"] = "1" * 64
    archive = write_plugin_zip(tmp_path / "agent-digest-mismatch.zip", manifest, payloads)
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "agent digest" in result.stdout + result.stderr


def test_missing_manifest_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive = write_plugin_zip(tmp_path / "missing-manifest.zip", None, payloads)
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "was not found in the plugin archive" in result.stdout + result.stderr


def test_forbidden_path_scan_still_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive = write_plugin_zip(
        tmp_path / "forbidden.zip",
        manifest,
        payloads,
        extra_members={"client/local-dev/notes.txt": b"developer only"},
    )
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "forbidden path" in result.stdout + result.stderr


def test_non_zip_archive_fails(tmp_path: Path) -> None:
    archive = tmp_path / "not-a-zip.zip"
    archive.write_bytes(b"this is not a zip archive")
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "not a valid ZIP archive" in result.stdout + result.stderr
