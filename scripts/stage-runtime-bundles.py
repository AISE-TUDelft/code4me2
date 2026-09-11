#!/usr/bin/env python3
"""Stage verified native agent archives into the participant plugin resources."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def assert_executable_present(archive: Path, executable: str, platform: str) -> None:
    """Fail the build if the declared executable is not at the archive root.

    The plugin installer looks the binary up by this exact manifest field, so
    a typo here (e.g. a dropped character) passes checksums yet breaks every
    install. Raises SystemExit naming the actual top-level entries.
    """
    import zipfile

    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if executable in names:
            with bundle.open(executable) as executable_stream:
                magic = executable_stream.read(4)
            expected_magic = {
                "windows": lambda value: value.startswith(b"MZ"),
                "linux": lambda value: value == b"\x7fELF",
                "macos": lambda value: value
                in {
                    b"\xfe\xed\xfa\xce",
                    b"\xce\xfa\xed\xfe",
                    b"\xfe\xed\xfa\xcf",
                    b"\xcf\xfa\xed\xfe",
                    b"\xca\xfe\xba\xbe",
                    b"\xbe\xba\xfe\xca",
                    b"\xca\xfe\xba\xbf",
                    b"\xbf\xba\xfe\xca",
                },
            }.get(platform)
            if expected_magic is None or not expected_magic(magic):
                raise SystemExit(
                    f"archive {archive.name} contains an executable with the wrong "
                    f"native format for {platform}"
                )
            return
    tops = sorted({name.split("/")[0] for name in names if name})
    raise SystemExit(
        f"archive {archive.name} does not contain {executable!r} at its root "
        f"(top-level entries: {tops[:8]})"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_dir", type=Path)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("src/main/resources/code4me-runtime/manifest.json"),
    )
    parser.add_argument("--version", required=True)
    parser.add_argument("--server-commit", default=None)
    parser.add_argument("--plugin-commit", default=None)
    parser.add_argument("--release-manifest", type=Path)
    parser.add_argument("--ide-version", default="2026.2.2")
    args = parser.parse_args()

    manifest_path = args.manifest.resolve()
    resource_dir = manifest_path.parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("manifest_version") != 1:
        raise SystemExit("unsupported plugin runtime manifest version")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise SystemExit("runtime manifest contains no artifacts")

    release_artifacts: dict[str, str] = {}
    if args.release_manifest:
        release = json.loads(args.release_manifest.read_text(encoding="utf-8"))
        if release.get("manifest_version") != 1:
            raise SystemExit("unsupported runtime release manifest version")
        if str(release.get("runtime_version")) != args.version:
            raise SystemExit("runtime release version does not match the participant release")
        release_commit = str(release.get("server_commit") or "")
        requested_commit = str(args.server_commit or "")
        if requested_commit and not (
            release_commit == requested_commit
            or release_commit.startswith(requested_commit)
            or requested_commit.startswith(release_commit)
        ):
            raise SystemExit(
                f"runtime release server commit {release_commit!r} does not match {requested_commit!r}"
            )
        if str(release.get("managed_protocol_version")) != str(
            manifest.get("managed_protocol_version")
        ):
            raise SystemExit("runtime release managed protocol does not match the plugin manifest")
        for item in release.get("artifacts", []):
            if isinstance(item, dict) and item.get("archive") and item.get("sha256"):
                release_artifacts[Path(str(item["archive"])).name] = str(item["sha256"]).lower()
        expected_release_artifacts = {
            Path(str(item["archive"])).name for item in artifacts
        }
        if set(release_artifacts) != expected_release_artifacts:
            raise SystemExit(
                "runtime release manifest artifacts do not match the plugin manifest"
            )

    staged: list[str] = []
    for artifact in artifacts:
        archive_name = Path(str(artifact["archive"])).name
        source = args.input_dir.resolve() / archive_name
        if not source.is_file():
            raise SystemExit(f"missing runtime archive: {source}")
        source_checksum = sha256(source)
        if release_artifacts and release_artifacts.get(archive_name) != source_checksum:
            raise SystemExit(f"runtime archive does not match release metadata: {archive_name}")
        destination = resource_dir / archive_name
        shutil.copyfile(source, destination)
        assert_executable_present(
            destination,
            str(artifact["executable"]),
            str(artifact["platform"]),
        )
        artifact["version"] = args.version
        artifact["sha256"] = source_checksum
        artifact["managed_protocol"] = manifest.get("managed_protocol_version", "1")
        staged.append(f"{archive_name} {artifact['sha256']}")

    manifest["runtime_version"] = args.version
    manifest["tested_platforms"] = [
        f"{artifact['platform']}-{artifact['architecture']}" for artifact in artifacts
    ]
    if args.server_commit:
        manifest["server_commit"] = args.server_commit
    if args.plugin_commit:
        manifest["plugin_commit"] = args.plugin_commit
    if args.ide_version:
        manifest["tested_ide_version"] = args.ide_version

    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    print(f"staged {len(staged)} runtime archives for {args.version}")
    for entry in staged:
        print(f"  {entry}")


if __name__ == "__main__":
    main()
