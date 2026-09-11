#!/usr/bin/env python3
"""Build a current-platform managed runtime and embed it in a local plugin ZIP."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import platform
import subprocess
import sys
import tempfile
import venv
import zipfile
from pathlib import Path


SCRIPT_VERSION = "1"


def run(command: list[str], *, cwd: Path, env: dict[str, str] | None = None) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def digest_files(root: Path, files: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def source_fingerprint(plugin_root: Path, server_root: Path) -> str:
    server_files = [
        path
        for path in (server_root / "src" / "code4me2_agent").rglob("*.py")
        if path.name != "_build_version.py"
    ]
    server_files.extend(
        path
        for path in [
            server_root / "pyproject.toml",
            server_root / "packaging" / "code4me2-agent.spec",
            server_root / "packaging" / "archive_runtime.py",
            server_root / "packaging" / "requirements-runtime.lock",
            server_root / "packaging" / "requirements-build.lock",
            server_root / "packaging" / "requirements-test.lock",
        ]
        if path.is_file()
    )
    plugin_files = list((plugin_root / "src" / "main" / "kotlin").rglob("*.kt"))
    plugin_files.extend(
        path
        for path in (plugin_root / "src" / "main" / "resources").rglob("*")
        if path.is_file() and "code4me-runtime" not in path.parts
    )
    plugin_files.extend(
        path
        for path in [plugin_root / "build.gradle.kts", plugin_root / "gradle.properties"]
        if path.is_file()
    )
    combined = hashlib.sha256()
    combined.update(digest_files(server_root, server_files).encode("ascii"))
    combined.update(digest_files(plugin_root, plugin_files).encode("ascii"))
    return combined.hexdigest()[:12]


def plugin_base_version(plugin_root: Path) -> str:
    for line in (plugin_root / "gradle.properties").read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "pluginVersion":
            version = value.strip().split("+", 1)[0]
            if version:
                return version
    raise SystemExit("pluginVersion is missing from gradle.properties")


def local_version(base: str, fingerprint: str) -> str:
    return f"{base}.{fingerprint}" if "-" in base else f"{base}-local.{fingerprint}"


def current_platform() -> tuple[str, str, str]:
    system = sys.platform
    machine = platform.machine().lower()
    architecture = "arm64" if machine in {"arm64", "aarch64"} else "x64" if machine in {"x86_64", "amd64"} else machine
    os_name = "macos" if system == "darwin" else "windows" if system == "win32" else "linux" if system.startswith("linux") else system
    platform_id = f"{os_name}-{architecture}"
    supported = {"macos-arm64", "macos-x64", "windows-x64", "linux-x64"}
    if platform_id not in supported:
        raise SystemExit(f"unsupported local runtime platform: {platform_id}")
    executable = "code4me2-agent.exe" if os_name == "windows" else "code4me2-agent"
    return os_name, architecture, executable


def venv_python(environment_root: Path) -> Path:
    return environment_root / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def dependency_fingerprint(server_root: Path) -> str:
    files = [
        server_root / "pyproject.toml",
        server_root / "packaging" / "requirements-runtime.lock",
        server_root / "packaging" / "requirements-build.lock",
        server_root / "packaging" / "requirements-test.lock",
    ]
    digest = hashlib.sha256()
    digest.update(SCRIPT_VERSION.encode("ascii"))
    digest.update(f"{sys.version_info.major}.{sys.version_info.minor}".encode("ascii"))
    for path in files:
        digest.update(path.read_bytes())
    return digest.hexdigest()


def prepare_build_environment(server_root: Path) -> Path:
    environment_root = server_root / "build" / "local-runtime-venv"
    python = venv_python(environment_root)
    if not python.is_file():
        print(f"Creating cached runtime build environment at {environment_root}", flush=True)
        venv.EnvBuilder(with_pip=True).create(environment_root)
    expected = dependency_fingerprint(server_root)
    stamp = environment_root / ".code4me-dependencies.sha256"
    if not stamp.is_file() or stamp.read_text(encoding="utf-8").strip() != expected:
        run(
            [
                str(python),
                "-m",
                "pip",
                "install",
                "-r",
                "packaging/requirements-runtime.lock",
                "-r",
                "packaging/requirements-build.lock",
                "-r",
                "packaging/requirements-test.lock",
            ],
            cwd=server_root,
        )
        stamp.write_text(expected + "\n", encoding="utf-8")
    run([str(python), "-m", "pip", "install", "--no-deps", "."], cwd=server_root)
    return python


def git_revision(repository: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short=12", "HEAD"], cwd=repository, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "local-working-tree"


def generated_manifest(
    plugin_root: Path,
    server_root: Path,
    archive: Path,
    version: str,
    os_name: str,
    architecture: str,
    executable: str,
) -> dict[str, object]:
    source_manifest = json.loads(
        (plugin_root / "src/main/resources/code4me-runtime/manifest.json").read_text(encoding="utf-8")
    )
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    source_manifest["runtime_version"] = version
    source_manifest["server_commit"] = git_revision(server_root)
    source_manifest["plugin_commit"] = git_revision(plugin_root)
    source_manifest["tested_platforms"] = [f"{os_name}-{architecture}"]
    source_manifest["artifacts"] = [
        {
            "runtime_id": "code4me-agent",
            "version": version,
            "platform": os_name,
            "architecture": architecture,
            "archive": f"code4me-runtime/{archive.name}",
            "sha256": checksum,
            "executable": executable,
            "managed_protocol": str(source_manifest.get("managed_protocol_version", "1")),
        }
    ]
    return source_manifest


def verify_plugin_zip(plugin_zip: Path, version: str, archive_name: str) -> None:
    with zipfile.ZipFile(plugin_zip) as outer:
        jars = [name for name in outer.namelist() if name.endswith(".jar")]
        for jar_name in jars:
            try:
                with zipfile.ZipFile(io.BytesIO(outer.read(jar_name))) as jar:
                    manifest_name = "code4me-runtime/manifest.json"
                    embedded_archive = f"code4me-runtime/{archive_name}"
                    if manifest_name not in jar.namelist():
                        continue
                    manifest = json.loads(jar.read(manifest_name))
                    if manifest.get("runtime_version") != version:
                        raise SystemExit("embedded runtime version does not match local build version")
                    artifacts = manifest.get("artifacts", [])
                    if len(artifacts) != 1 or artifacts[0].get("archive") != embedded_archive:
                        raise SystemExit("local plugin must contain exactly the current-platform runtime")
                    actual = hashlib.sha256(jar.read(embedded_archive)).hexdigest()
                    if actual != artifacts[0].get("sha256"):
                        raise SystemExit("embedded runtime checksum does not match its manifest")
                    return
            except zipfile.BadZipFile:
                continue
    raise SystemExit("built plugin does not contain a managed runtime manifest")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build and test the latest native Code4Me runtime and local plugin ZIP."
    )
    parser.add_argument(
        "--server-repo",
        type=Path,
        help="Code4Me server/runtime repository (default: sibling code4me2-server)",
    )
    parser.add_argument("--skip-tests", action="store_true", help="Skip runtime and plugin tests")
    args = parser.parse_args()

    if sys.version_info < (3, 11):
        raise SystemExit("buildzip requires Python 3.11 or newer")
    plugin_root = Path(__file__).resolve().parent.parent
    server_root = (args.server_repo or plugin_root.parent / "code4me2-server").resolve()
    if not (server_root / "packaging/code4me2-agent.spec").is_file():
        raise SystemExit(f"managed runtime repository not found: {server_root}")

    os_name, architecture, executable_name = current_platform()
    fingerprint = source_fingerprint(plugin_root, server_root)
    version = local_version(plugin_base_version(plugin_root), fingerprint)
    print(f"Building Code4Me local test ZIP {version} for {os_name}-{architecture}", flush=True)

    python = prepare_build_environment(server_root)
    build_version = server_root / "src/code4me2_agent/_build_version.py"
    previous_version = build_version.read_bytes() if build_version.exists() else None
    try:
        run(
            [str(python), "packaging/stamp_runtime_version.py", "--version", version],
            cwd=server_root,
        )
        if not args.skip_tests:
            test_environment = os.environ.copy()
            test_environment["PYTHONPATH"] = str(server_root / "src")
            run(
                [str(python), "-m", "pytest", "-q", "tests/code4me2_agent"],
                cwd=server_root,
                env=test_environment,
            )
        run(
            [
                str(python),
                "-m",
                "PyInstaller",
                "--clean",
                "--noconfirm",
                "packaging/code4me2-agent.spec",
            ],
            cwd=server_root,
        )
        executable = server_root / "dist" / "code4me2-agent" / executable_name
        run([str(executable), "--version"], cwd=server_root)
        run([str(executable), "--self-check"], cwd=server_root)

        with tempfile.TemporaryDirectory(prefix="code4me-local-runtime-") as temporary:
            resource_root = Path(temporary) / "resources"
            runtime_root = resource_root / "code4me-runtime"
            runtime_root.mkdir(parents=True)
            archive_name = f"code4me-agent-{os_name}-{architecture}.zip"
            archive = runtime_root / archive_name
            run(
                [
                    str(python),
                    "packaging/archive_runtime.py",
                    "--platform",
                    f"{os_name}-{architecture}",
                    "--output",
                    str(archive),
                ],
                cwd=server_root,
            )
            manifest = generated_manifest(
                plugin_root,
                server_root,
                archive,
                version,
                os_name,
                architecture,
                executable_name,
            )
            (runtime_root / "manifest.json").write_text(
                json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
            )

            gradle = plugin_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
            tasks = ["clean"]
            if not args.skip_tests:
                tasks.append("test")
            tasks.append("buildPlugin")
            run(
                [
                    str(gradle),
                    "--no-configuration-cache",
                    *tasks,
                    f"-PpluginVersion={version}",
                    f"-Pcode4me.localRuntimeDir={resource_root}",
                ],
                cwd=plugin_root,
            )

            expected = plugin_root / "build" / "distributions" / f"client-{version}.zip"
            candidates = sorted(
                (plugin_root / "build" / "distributions").glob("*.zip"),
                key=lambda path: path.stat().st_mtime,
                reverse=True,
            )
            plugin_zip = expected if expected.is_file() else candidates[0] if candidates else None
            if plugin_zip is None:
                raise SystemExit("Gradle completed without producing a plugin ZIP")
            verify_plugin_zip(plugin_zip, version, archive_name)
            print(f"\nVerified local test ZIP:\n{plugin_zip}\n", flush=True)
    finally:
        if previous_version is None:
            build_version.unlink(missing_ok=True)
        else:
            build_version.write_bytes(previous_version)


if __name__ == "__main__":
    main()
