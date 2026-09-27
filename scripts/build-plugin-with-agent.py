#!/usr/bin/env python3
"""Build the plugin with an agent release: copy, sync the recipe, package.

One local command that turns a producer release (the ``native-<platform>.json``
manifest and the ZIP next to it) into an installable plugin ZIP:

  1. copies the release archive into ``src/main/resources/code4me-runtime/``
  2. rewrites ``code4me-runtime/manifest.json`` (the single agent identity) from
     that manifest -- no hand-typed digests, no editor
  3. runs ``./gradlew verifyResearchRuntimeConsistency buildPlugin``

The producer manifest and the plugin recipe differ in exactly two places: the
recipe stores the archive path relative to the resources root and needs a
per-artifact ``managed_protocol``. Everything else (runtime_version, sha256,
size, executable, server_commit) is used verbatim.

Usage:
    python3 scripts/build-plugin-with-agent.py <release-dir-or-manifest>
        [--platform macos-arm64] [--no-build]

Examples:
    python3 scripts/build-plugin-with-agent.py ../code4me2-server/dist/release-1.2.3
    python3 scripts/build-plugin-with-agent.py ../code4me2-server/dist/release-1.2.3/native-macos-arm64.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
RUNTIME_DIR = PLUGIN_ROOT / "src" / "main" / "resources" / "code4me-runtime"
RECIPE_PATH = RUNTIME_DIR / "manifest.json"
#: Top-level provenance fields the recipe keeps from the previous document.
PRESERVED_RECIPE_FIELDS = ("tested_ide_version", "tested_platforms")


def host_platform() -> str:
    os_name = "macos" if platform.system() == "Darwin" else platform.system().lower()
    arch = "arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
    return f"{os_name}-{arch}"


def resolve_manifest(target: Path) -> Path:
    if target.is_dir():
        candidates = sorted(target.glob("native-*.json"))
        if len(candidates) != 1:
            raise SystemExit(
                f"{target} must contain exactly one native-*.json (found {len(candidates)}); "
                "pass the manifest file explicitly"
            )
        return candidates[0]
    if not target.is_file():
        raise SystemExit(f"release manifest not found: {target}")
    return target


def artifact_for(release: dict, wanted: str, manifest_path: Path) -> dict:
    artifacts = release.get("artifacts") or []
    matches = [
        item
        for item in artifacts
        if f"{item.get('platform')}-{item.get('architecture')}" == wanted
    ]
    if len(matches) != 1:
        declared = [f"{a.get('platform')}-{a.get('architecture')}" for a in artifacts]
        raise SystemExit(
            f"{manifest_path.name} declares no unique artifact for {wanted}; declared: {declared}"
        )
    return matches[0]


def sync_runtime(
    source: dict,
    *,
    server_commit: str | None,
    manifest_path: Path,
    plugin_commit: str,
    release: dict | None = None,
) -> tuple[Path, str]:
    """Copy the release archive and rewrite the recipe. Returns (archive, digest)."""
    source_zip = manifest_path.parent / Path(str(source["archive"])).name
    if not source_zip.is_file():
        raise SystemExit(f"release archive is missing next to the manifest: {source_zip}")
    payload = source_zip.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    expected = str(source["sha256"]).removeprefix("sha256:")
    if digest != expected:
        raise SystemExit(
            f"release archive does not match its manifest: {digest} != {expected}"
        )

    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    destination = RUNTIME_DIR / source_zip.name
    for stale in RUNTIME_DIR.glob("code4me-agent-*.zip"):
        if stale.name != destination.name:
            stale.unlink()
            print(f"removed stale agent archive: {stale.name}")
    if not destination.is_file() or destination.read_bytes() != payload:
        destination.write_bytes(payload)
        print(f"copied {source_zip.name} -> {destination.relative_to(PLUGIN_ROOT)}")

    previous = json.loads(RECIPE_PATH.read_text(encoding="utf-8")) if RECIPE_PATH.is_file() else {}
    artifact = {
        "runtime_id": source["runtime_id"],
        "version": source["version"],
        "platform": source["platform"],
        "architecture": source["architecture"],
        # The recipe's archive path is relative to src/main/resources.
        "archive": f"code4me-runtime/{destination.name}",
        "sha256": digest,
        "size": len(payload),
        "executable": source["executable"],
        "managed_protocol": str(source.get("managed_protocol") or "1"),
    }
    if source.get("tests"):
        artifact["tests"] = source["tests"]
    # The plugin refuses a study launch unless the bundled recipe declares the
    # adapter the bootstrap pins; the producer manifest carries it top-level
    # (or per artifact), so it travels into the recipe on both levels.
    adapter = source.get("adapter") or (release or {}).get("adapter")
    if adapter:
        artifact["adapter"] = adapter
    recipe = {
        "manifest_version": 1,
        "runtime_version": source["version"],
        "managed_protocol_version": "1",
        "server_commit": server_commit,
        "plugin_commit": plugin_commit,
        **{key: previous[key] for key in PRESERVED_RECIPE_FIELDS if key in previous},
        **({"adapter": adapter} if adapter else {}),
        "artifacts": [artifact],
    }
    serialized = json.dumps(recipe, indent=2) + "\n"
    if RECIPE_PATH.read_text(encoding="utf-8") != serialized:
        RECIPE_PATH.write_text(serialized, encoding="utf-8")
    print(f"recipe updated: code4me-runtime/manifest.json ({digest[:16]}… {len(payload)} bytes)")
    return destination, digest


def main() -> int:
    args = parse_args()
    manifest_path = resolve_manifest(Path(args.release).expanduser().resolve())
    release = json.loads(manifest_path.read_text(encoding="utf-8"))
    wanted = args.platform or host_platform()
    source = artifact_for(release, wanted, manifest_path)
    plugin_commit = subprocess.check_output(
        ["git", "rev-parse", "--short", "HEAD"], cwd=PLUGIN_ROOT, text=True
    ).strip()
    sync_runtime(
        source,
        server_commit=release.get("server_commit"),
        manifest_path=manifest_path,
        plugin_commit=plugin_commit,
    )

    if args.no_build:
        print("\nrecipe and archive staged; rerun without --no-build to package")
        return 0

    subprocess.run(
        [str(PLUGIN_ROOT / "gradlew"), "verifyResearchRuntimeConsistency", "buildPlugin"],
        cwd=PLUGIN_ROOT,
        check=True,
    )
    produced = sorted(
        (PLUGIN_ROOT / "build" / "distributions").glob("client-*.zip"),
        key=lambda path: path.stat().st_mtime,
    )
    if not produced:
        raise SystemExit("the build produced no plugin ZIP under build/distributions/")
    print(f"\nplugin ZIP: {produced[-1]}")
    print("Install: IntelliJ -> Settings -> Plugins -> gear -> Install Plugin from Disk…")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("release", help="release directory or native-<platform>.json manifest")
    parser.add_argument(
        "--platform", help=f"platform to stage (default: host, currently {host_platform()})"
    )
    parser.add_argument("--no-build", action="store_true", help="only copy and sync the recipe")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
