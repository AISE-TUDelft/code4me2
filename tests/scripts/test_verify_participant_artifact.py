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
from pathlib import Path, PurePosixPath

PLUGIN_ROOT = Path(__file__).resolve().parents[2]
VERIFIER = PLUGIN_ROOT / "scripts" / "verify-participant-artifact.py"
RUNTIME_PREFIX = "research-runtime/"
AGENT_PREFIX = "code4me-runtime/"
PLATFORM_MATRIX = ("macos-aarch64", "macos-x64", "linux-x64", "windows-x64")


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def agent_zip(executable_payload: bytes = b"managed agent") -> bytes:
    """A tiny valid ZIP so the archive member is not misread as a nested archive."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("code4me2-agent", executable_payload)
    return buffer.getvalue()


def file_record(path: str, payload: bytes, *, executable: bool = False) -> dict:
    return {
        "path": path,
        "sha256": sha256(payload),
        "size": len(payload),
        "executable": executable,
    }


def proxy_platform(
    platform_id: str,
    payloads: dict[str, bytes],
    *,
    self_contained: bool = True,
    entrypoint: str | None = None,
) -> dict:
    os_name, arch = platform_id.split("-", 1)
    selected = entrypoint or next(iter(payloads))
    return {
        "os": os_name,
        "arch": arch,
        "self_contained": self_contained,
        "entrypoint": [selected],
        "files": [
            file_record(path, payload, executable=(path == selected))
            for path, payload in payloads.items()
        ],
    }


def agent_archive_name(platform_id: str) -> str:
    return f"{AGENT_PREFIX}code4me-agent-{platform_id}.zip"


def agent_archive_bytes(platform_id: str, payload: bytes | None = None) -> bytes:
    """A deterministic per-platform fixture archive; a raw payload is wrapped."""
    if payload is not None:
        return payload if payload.startswith(b"PK\x03\x04") else agent_zip(payload)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("code4me2-agent", f"managed agent {platform_id}".encode())
    return buffer.getvalue()


def agent_recipe(archive_bytes: bytes, platforms: tuple[str, ...] = PLATFORM_MATRIX) -> dict:
    """The single managed agent recipe: one artifact per platform."""
    artifacts = []
    for platform_id in platforms:
        os_name, arch = platform_id.split("-", 1)
        payload = (
            agent_archive_bytes(platform_id, archive_bytes)
            if platform_id == platforms[0]
            else agent_archive_bytes(platform_id)
        )
        artifacts.append(
            {
                "runtime_id": "code4me-agent",
                "version": "1.2.3",
                "platform": os_name,
                "architecture": arch,
                "archive": agent_archive_name(platform_id),
                "sha256": sha256(payload),
                "size": len(payload),
                "executable": "code4me2-agent.exe" if os_name == "windows" else "code4me2-agent",
                "managed_protocol": "1",
            }
        )
    return {
        "manifest_version": 1,
        "runtime_version": "1.2.3",
        "managed_protocol_version": "1",
        "server_commit": "0" * 40,
        "plugin_commit": "0" * 7,
        "artifacts": artifacts,
    }


def valid_manifest() -> tuple[dict, dict[str, bytes]]:
    mac_proxy = b"#!/bin/sh\nexit 0\n"
    mac_lib = b"mach-o payload"
    linux_proxy = b"\x7fELF proxy payload"
    linux_lib = b"elf payload"
    payloads = {
        "platforms/macos-aarch64/telemetry-acp-proxy": mac_proxy,
        "platforms/macos-aarch64/_internal/lib.dylib": mac_lib,
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
    recipe: dict | None = None,
    agent_archive: bytes | None = None,
    agent_archives: dict[str, bytes] | None = None,
    extra_members: dict[str, bytes] | None = None,
) -> Path:
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as archive:
        if manifest is not None:
            archive.writestr(f"{RUNTIME_PREFIX}proxy-manifest.json", json.dumps(manifest))
        for relative, data in payloads.items():
            archive.writestr(f"{RUNTIME_PREFIX}{relative}", data)
        if recipe is not None:
            archive.writestr(f"{AGENT_PREFIX}manifest.json", json.dumps(recipe))
            for index, artifact in enumerate(recipe.get("artifacts", [])):
                platform_id = f"{artifact.get('platform')}-{artifact.get('architecture')}"
                supplied = (agent_archives or {}).get(artifact["archive"].rsplit("/", 1)[-1])
                if supplied is not None:
                    payload = supplied
                elif index == 0 and agent_archive is not None:
                    payload = agent_archive_bytes(platform_id, agent_archive)
                else:
                    payload = agent_archive_bytes(platform_id)
                archive.writestr(artifact["archive"], payload)
    with zipfile.ZipFile(path, "w") as outer:
        outer.writestr("client/lib/client-1.0.0.jar", jar.getvalue())
        for name, data in (extra_members or {}).items():
            outer.writestr(name, data)
    return path


def run_verifier(
    archive: Path, *, require_participant_release: bool = False
) -> subprocess.CompletedProcess:
    command = [sys.executable, str(VERIFIER), str(archive)]
    if require_participant_release:
        command.append("--require-participant-release")
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=120,
    )


def test_valid_self_contained_artifact_passes(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive_bytes = agent_zip(b"the shipped managed agent")
    archive = write_plugin_zip(
        tmp_path / "valid.zip",
        manifest,
        payloads,
        recipe=agent_recipe(archive_bytes),
        agent_archive=archive_bytes,
    )
    result = run_verifier(archive)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "participant artifact verified" in result.stdout

    # The strict participant-release mode also requires the inventory; a fixture
    # without one must be rejected there.
    strict = run_verifier(archive, require_participant_release=True)
    assert strict.returncode != 0
    assert "participant release inventory" in strict.stdout + strict.stderr


def test_agent_recipe_digest_mismatch_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    recipe = agent_recipe(agent_zip(b"declared bytes"))
    archive = write_plugin_zip(
        tmp_path / "agent-digest-mismatch.zip",
        manifest,
        payloads,
        recipe=recipe,
        agent_archive=agent_zip(b"a different valid archive"),
    )
    result = run_verifier(archive, require_participant_release=True)
    assert result.returncode != 0
    assert "sha256 mismatch" in result.stdout + result.stderr


def test_agent_recipe_size_mismatch_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive_bytes = agent_zip(b"the shipped managed agent")
    recipe = agent_recipe(archive_bytes)
    recipe["artifacts"][0]["size"] = 1
    archive = write_plugin_zip(
        tmp_path / "agent-size-mismatch.zip",
        manifest,
        payloads,
        recipe=recipe,
        agent_archive=archive_bytes,
    )
    result = run_verifier(archive, require_participant_release=True)
    assert result.returncode != 0
    assert "size mismatch" in result.stdout + result.stderr


def test_missing_agent_recipe_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive = write_plugin_zip(tmp_path / "missing-recipe.zip", manifest, payloads)
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "agent recipe" in result.stdout + result.stderr


def test_release_keyed_agent_block_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    manifest["platforms"][0]["agents"] = [{"release_id": "release-1"}]
    archive_bytes = agent_zip()
    archive = write_plugin_zip(
        tmp_path / "legacy-agents.zip",
        manifest,
        payloads,
        recipe=agent_recipe(archive_bytes),
    )
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "release-keyed" in result.stdout + result.stderr


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


def test_openai_style_secret_still_fails(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive = write_plugin_zip(
        tmp_path / "secret.zip",
        manifest,
        payloads,
        extra_members={"client/notes.txt": b"key sk-" + b"A" * 48 + b"\n"},
    )
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "sk-" in result.stdout + result.stderr


def test_bundled_library_css_identifiers_are_not_secrets(tmp_path: Path) -> None:
    manifest, payloads = valid_manifest()
    archive = write_plugin_zip(
        tmp_path / "css.zip",
        manifest,
        payloads,
        recipe=agent_recipe(agent_zip()),
        extra_members={
            "client/_internal/sklearn/utils/_repr_html/estimator.css": (
                b".sk-global label.sk-toggleable__label-arrow:before { content: 'x'; }\n"
            )
        },
    )
    result = run_verifier(archive)
    assert result.returncode == 0, result.stdout + result.stderr


def test_non_zip_archive_fails(tmp_path: Path) -> None:
    archive = tmp_path / "not-a-zip.zip"
    archive.write_bytes(b"this is not a zip archive")
    result = run_verifier(archive)
    assert result.returncode != 0
    assert "not a valid ZIP archive" in result.stdout + result.stderr
