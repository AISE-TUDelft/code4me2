#!/usr/bin/env python3
"""Reject participant plugin archives that are unsafe or ship a broken runtime.

The verifier performs independent passes over the built plugin ZIP:

1. The supply-chain scan rejects developer-only paths, credentials, and
   project-local absolute paths anywhere in the archive (including nested jars).
2. The research-proxy pass locates ``research-runtime/proxy-manifest.json``
   (inside the plugin jar) and re-verifies the packaged ACP proxy contract:
   every declared platform must be self-contained, declare a resolvable
   entrypoint, and every declared file record must match the actual member size
   and SHA-256. A source fallback (``run.py`` entrypoint or
   ``self_contained == false``) is a hard failure, so a release can never
   silently degrade to ``NOT_SELF_CONTAINED`` at launch.
3. The packaged-agent pass verifies the single managed identity from the plugin
   resources: ``code4me-runtime/manifest.json`` and the exact
   ``code4me-runtime/<archive>`` it declares, with the declared sha256/size
   matching the actual member bytes and exactly one artifact for the release's
   platform matrix. The recipe is the only agent identity: the proxy manifest
   carries a simplified inventory derived from it, with no release-keyed
   ``agents`` payload and no server-derived ``release_id``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import BinaryIO

FORBIDDEN_NAMES = (".env", "local-dev", ".venv")
FORBIDDEN_TEXT = (
    re.compile(rb"(?:/|\\)(?:local-dev|code4me2-server)(?:/|\\)", re.IGNORECASE),
    re.compile(rb"(?:file:/{2,3}|[A-Za-z]:\\Users\\)[^\r\n\x00]+code4me", re.IGNORECASE),
    # OpenAI-style keys: `sk-` plus a long opaque body (legacy keys are 48
    # characters; project/service/admin keys are longer and prefixed). The
    # previous 20-character floor matched bundled library CSS identifiers such
    # as `.sk-toggleable__label-arrow` in the packaged scikit-learn assets.
    re.compile(rb"\bsk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{32,}\b"),
    re.compile(rb"\bgh[oprsu]_[A-Za-z0-9]{20,}\b"),
    re.compile(
        rb"\b(?:OPENAI|OPENROUTER|GROQ)_API_KEY\s*=\s*[\"']?(?:sk-|gsk_)[A-Za-z0-9_-]{16,}"
    ),
)
MAX_NESTED_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_TEXT_SCAN_BYTES = 50 * 1024 * 1024
RUNTIME_MANIFEST_SUFFIX = "research-runtime/proxy-manifest.json"
AGENT_MANIFEST_SUFFIX = "code4me-runtime/manifest.json"
PROXY_PRODUCTION_PLATFORMS = {"macos-aarch64", "macos-x64", "linux-x64", "windows-x64"}
AGENT_PRODUCTION_PLATFORMS = {"macos-arm64", "macos-x64", "linux-x64", "windows-x64"}
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")

# Mirror of the resolver's `pathSafety` (RuntimeManifestV2.kt): a declared path
# the resolver would refuse must fail verification instead of shipping.
SHELL_METACHARACTERS = ";&|$`<>(){}[]*?!#~\"'\\\n\r\t\u0000"


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


def sha256_stream(source: BinaryIO) -> str:
    digest = hashlib.sha256()
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def safe_relative_path(value: object) -> str | None:
    """Return a normalized relative POSIX path, or None when it is unsafe."""
    if not isinstance(value, str) or not value:
        return None
    if any(character in SHELL_METACHARACTERS for character in value):
        return None
    if len(value) >= 2 and value[0].isalpha() and value[1] == ":":
        return None
    candidate = PurePosixPath(value)
    if candidate.is_absolute():
        return None
    if any(part == ".." for part in candidate.parts):
        return None
    if candidate.as_posix() != value:
        return None
    return candidate.as_posix()


def verify_file_records(
    label: str,
    prefix: str,
    archive: zipfile.ZipFile,
    members: dict[str, zipfile.ZipInfo],
    records: object,
    findings: list[str],
) -> dict[str, str]:
    """Verify one `files` array; returns declared path -> declared sha256."""
    declared: dict[str, str] = {}
    if not isinstance(records, list) or not records:
        findings.append(f"{label}: 'files' must be a non-empty array")
        return declared
    for index, record in enumerate(records):
        field = f"{label}.files[{index}]"
        if not isinstance(record, dict):
            findings.append(f"{field}: file record must be an object")
            continue
        relative = safe_relative_path(record.get("path"))
        if relative is None:
            findings.append(
                f"{field}: path must be a normalized relative path without shell "
                f"metacharacters; got {record.get('path')!r}"
            )
            continue
        declared[relative] = ""
        member = members.get(prefix + relative)
        if member is None or member.is_dir():
            findings.append(f"{field}: declared file is missing from the plugin archive: {relative}")
            continue
        size = record.get("size")
        if type(size) is not int or size < 0:
            findings.append(f"{field}: size must be a non-negative integer; got {size!r}")
        elif size != member.file_size:
            findings.append(
                f"{field}: size mismatch for {relative}: declared {size}, archive {member.file_size}"
            )
        expected = record.get("sha256")
        if not isinstance(expected, str) or not SHA256_PATTERN.fullmatch(expected):
            findings.append(f"{field}: sha256 must be 64 lowercase hex; got {expected!r}")
            continue
        declared[relative] = expected
        with archive.open(member) as payload:
            actual = sha256_stream(payload)
        if actual != expected:
            findings.append(
                f"{field}: sha256 mismatch for {relative}: "
                f"declared {expected[:12]}..., archive {actual[:12]}..."
            )
    return declared


def verify_platform(
    label: str,
    platform: object,
    prefix: str,
    archive: zipfile.ZipFile,
    members: dict[str, zipfile.ZipInfo],
    findings: list[str],
) -> None:
    if not isinstance(platform, dict):
        findings.append(f"{label}: platform entry must be an object")
        return
    os_name = platform.get("os")
    arch = platform.get("arch")
    if not isinstance(os_name, str) or not isinstance(arch, str):
        findings.append(f"{label}: platform os and arch must be strings")
        platform_id = label
    else:
        platform_id = f"{os_name}-{arch}"
    if platform.get("self_contained") is not True:
        findings.append(
            f"{label} ({platform_id}): self_contained must be true; participant "
            "releases must not ship the proxy source fallback"
        )
    entrypoint = platform.get("entrypoint")
    entry_name: str | None = None
    if (
        not isinstance(entrypoint, list)
        or not entrypoint
        or not all(isinstance(item, str) and item for item in entrypoint)
    ):
        findings.append(f"{label} ({platform_id}): entrypoint must be a non-empty array of strings")
    else:
        entry_name = safe_relative_path(entrypoint[0])
        if entry_name is None:
            findings.append(
                f"{label} ({platform_id}): entrypoint[0] must be a normalized relative "
                f"path; got {entrypoint[0]!r}"
            )
        elif PurePosixPath(entry_name).name == "run.py":
            findings.append(
                f"{label} ({platform_id}): entrypoint {entry_name!r} is the source "
                "fallback (run.py)"
            )
    declared = verify_file_records(label, prefix, archive, members, platform.get("files"), findings)
    if entry_name is not None and entry_name not in declared:
        findings.append(
            f"{label} ({platform_id}): entrypoint {entry_name!r} is not declared in files"
        )
    for legacy in ("agent", "agents"):
        if legacy in platform:
            findings.append(
                f"{label} ({platform_id}): proxy manifests must not declare a release-keyed "
                f"'{legacy}' block; the agent is the single code4me-runtime recipe"
            )


def verify_proxy_manifest(
    label: str,
    member_name: str,
    archive: zipfile.ZipFile,
    members: dict[str, zipfile.ZipInfo],
    findings: list[str],
    require_participant_release: bool = False,
    allow_partial_platforms: bool = False,
) -> None:
    prefix = member_name[: len(member_name) - len("proxy-manifest.json")]
    try:
        raw = archive.read(member_name)
    except KeyError:
        findings.append(f"{label}!{member_name}: manifest could not be read")
        return
    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        findings.append(f"{label}!{member_name}: manifest is not valid UTF-8 JSON: {error}")
        return
    if not isinstance(document, dict):
        findings.append(f"{label}!{member_name}: manifest must be a JSON object")
        return
    if document.get("schema_version") != "1":
        findings.append(
            f"{label}!{member_name}: unsupported schema_version {document.get('schema_version')!r}"
        )
    platforms = document.get("platforms")
    if not isinstance(platforms, list) or not platforms:
        findings.append(f"{label}!{member_name}: manifest declares no platforms")
        return
    for index, platform in enumerate(platforms):
        verify_platform(
            f"{member_name}#platforms[{index}]", platform, prefix, archive, members, findings
        )
    if require_participant_release or allow_partial_platforms or "participant_release" in document:
        verify_release_catalog(
            label, archive, members, document, findings,
            allow_partial_platforms=allow_partial_platforms,
        )


def verify_agent_recipe(
    label: str,
    archive: zipfile.ZipFile,
    members: dict[str, zipfile.ZipInfo],
    findings: list[str],
    *,
    managed_version: object = None,
    allow_partial_platforms: bool = False,
) -> None:
    """Verify the single managed agent identity shipped in the plugin resources.

    The plugin ships exactly one agent recipe (``code4me-runtime/manifest.json``)
    and the archive it declares: one artifact entry for the release's platform
    matrix, whose sha256/size must match the actual archive member bytes. There is
    no release-keyed ``agents`` payload anywhere, so the managed ``version`` from
    the simplified inventory must match the recipe's own ``runtime_version``.
    """
    manifest_member = next(
        (name for name in members if name.endswith(AGENT_MANIFEST_SUFFIX)),
        None,
    )
    if manifest_member is None:
        findings.append(f"{label}: the packaged agent recipe {AGENT_MANIFEST_SUFFIX} is missing")
        return
    try:
        document = json.loads(archive.read(manifest_member).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        findings.append(f"{label}!{manifest_member}: agent recipe is not valid UTF-8 JSON: {error}")
        return
    if not isinstance(document, dict) or document.get("manifest_version") != 1:
        findings.append(f"{label}!{manifest_member}: unsupported agent manifest_version")
        return
    if str(document.get("managed_protocol_version")) != "1":
        findings.append(f"{label}!{manifest_member}: unsupported managed_protocol_version")
    runtime_version = str(document.get("runtime_version") or "")
    artifacts = document.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        findings.append(f"{label}!{manifest_member}: the agent recipe declares no artifacts")
        return
    if not allow_partial_platforms:
        actual_platforms = {
            f"{a.get('platform')}-{a.get('architecture')}" for a in artifacts if isinstance(a, dict)
        }
        if len(artifacts) != len(actual_platforms) or actual_platforms != AGENT_PRODUCTION_PLATFORMS:
            findings.append(
                f"{label}!{manifest_member}: the agent recipe must cover exactly "
                f"{sorted(AGENT_PRODUCTION_PLATFORMS)} once; got {sorted(actual_platforms)}"
            )
    if runtime_version and managed_version is not None and str(managed_version) != runtime_version:
        findings.append(
            f"{label}!{manifest_member}: the inventory's managed version "
            f"{managed_version!r} does not match the recipe's runtime_version {runtime_version!r}"
        )
    for index, artifact in enumerate(artifacts):
        field = f"{label}!{manifest_member}#artifacts[{index}]"
        if not isinstance(artifact, dict):
            findings.append(f"{field}: artifact must be an object")
            continue
        relative = safe_relative_path(artifact.get("archive"))
        if relative is None:
            findings.append(f"{field}: archive must be a normalized relative path; got {artifact.get('archive')!r}")
            continue
        basename = PurePosixPath(relative).name
        declared = [
            info
            for name, info in members.items()
            if name == relative or name.endswith("/" + relative) or name.rsplit("/", 1)[-1] == basename
        ]
        if len(declared) != 1:
            findings.append(f"{field}: declared agent archive is missing from the plugin archive: {relative}")
            continue
        member = declared[0]
        with archive.open(member) as payload:
            size = sha256_stream(payload)
        expected_digest = artifact.get("sha256")
        if not isinstance(expected_digest, str) or not SHA256_PATTERN.fullmatch(expected_digest):
            findings.append(f"{field}: sha256 must be 64 lowercase hex; got {expected_digest!r}")
        if artifact.get("size") != member.file_size:
            findings.append(
                f"{field}: size mismatch for {relative}: declared {artifact.get('size')}, archive {member.file_size}"
            )
        if isinstance(expected_digest, str) and expected_digest != size:
            findings.append(
                f"{field}: sha256 mismatch for {relative}: "
                f"declared {expected_digest[:12]}..., archive {size[:12]}..."
            )


def verify_release_catalog(
    label: str,
    archive: zipfile.ZipFile,
    members: dict[str, zipfile.ZipInfo],
    document: dict,
    findings: list[str],
    *,
    allow_partial_platforms: bool = False,
) -> None:
    """Verify the simplified release inventory and the single managed agent identity.

    The plugin derives ``participant_release`` from the shipped recipe: the
    declared platforms plus the managed and optional external framework identities. The recipe
    (``code4me-runtime/manifest.json``) is the actual agent identity, so the
    managed entry's ``version`` must equal the recipe's ``runtime_version``; there
    is no server-derived ``release_id`` or execution inventory to compare.

    ``allow_partial_platforms`` is the explicit local-test-release mode: the
    proxy artifact must declare exactly the platform subset its own inventory
    names instead of the production four-platform matrix. It is never implied.
    """
    inventory = document.get("participant_release")
    if not isinstance(inventory, dict) or inventory.get("schema_version") != "1":
        findings.append("participant release inventory is missing or unsupported")
        return
    platforms = document.get("platforms", [])
    actual = [f"{p.get('os')}-{p.get('arch')}" for p in platforms if isinstance(p, dict)]
    if allow_partial_platforms:
        declared = inventory.get("platforms", [])
        if (
            not isinstance(declared, list)
            or not declared
            or len(actual) != len(set(actual))
            or set(actual) != set(declared)
        ):
            findings.append("local test release platforms must match its inventory exactly")
    elif (
        len(actual) != 4
        or set(actual) != PROXY_PRODUCTION_PLATFORMS
        or set(inventory.get("platforms", [])) != PROXY_PRODUCTION_PLATFORMS
    ):
        findings.append("participant release must cover all four native platforms exactly once")
    releases = inventory.get("releases", [])
    if not isinstance(releases, list) or not releases or not all(
        isinstance(r, dict) and isinstance(r.get("framework"), str) for r in releases
    ):
        findings.append("participant inventory must contain a managed agent")
        return
    by_framework = {r.get("framework"): r for r in releases}
    if len(by_framework) != len(releases) or "code4me2-agent" not in by_framework or set(by_framework) - {"code4me2-agent", "goose", "codex"}:
        findings.append("participant inventory has missing, duplicate or unsupported agent definitions")
        return
    managed = by_framework["code4me2-agent"]
    if managed.get("distribution_mode") != "PACKAGED" or any(
        agent.get("distribution_mode") != "BYOA_EXTERNAL"
        for framework, agent in by_framework.items() if framework != "code4me2-agent"
    ):
        findings.append("participant inventory must bundle Code4Me and mark external agents as BYOA")
    verify_agent_recipe(
        label,
        archive,
        members,
        findings,
        managed_version=managed.get("version"),
        allow_partial_platforms=allow_partial_platforms,
    )


def inspect_zip(
    label: str,
    source: Path | BinaryIO,
    findings: list[str],
    *,
    depth: int = 0,
    require_participant_release: bool = False,
    allow_partial_platforms: bool = False,
) -> tuple[int, int]:
    """Scan one archive; returns the (proxy manifest, agent recipe) counts."""
    if depth > 4:
        findings.append(f"{label}: archive nesting exceeds verification limit")
        return 0, 0
    manifests = 0
    recipes = 0
    with zipfile.ZipFile(source) as archive:
        members = {info.filename: info for info in archive.infolist()}
        for member in archive.infolist():
            lowered = member.filename.lower()
            if any(marker in lowered for marker in FORBIDDEN_NAMES):
                findings.append(f"{label}!{member.filename}: forbidden path")
            if member.is_dir():
                continue
            if lowered.endswith((".jar", ".zip")) and not member.filename.endswith(AGENT_MANIFEST_SUFFIX):
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
                        nested_manifests, nested_recipes = inspect_zip(
                            f"{label}!{member.filename}", nested, findings, depth=depth + 1,
                            require_participant_release=require_participant_release,
                            allow_partial_platforms=allow_partial_platforms,
                        )
                    except zipfile.BadZipFile:
                        findings.append(f"{label}!{member.filename}: invalid nested archive")
                    else:
                        manifests += nested_manifests
                        recipes += nested_recipes
                continue
            if lowered.endswith(RUNTIME_MANIFEST_SUFFIX):
                manifests += 1
                verify_proxy_manifest(
                    label, member.filename, archive, members, findings,
                    require_participant_release, allow_partial_platforms,
                )
            if member.filename.endswith(AGENT_MANIFEST_SUFFIX):
                recipes += 1
                if require_participant_release or allow_partial_platforms:
                    verify_agent_recipe(label, archive, members, findings, allow_partial_platforms=allow_partial_platforms)
            if member.file_size > MAX_TEXT_SCAN_BYTES:
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
    return manifests, recipes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--require-participant-release", action="store_true")
    parser.add_argument(
        "--allow-partial-platforms",
        action="store_true",
        help=(
            "development-only local test release: require exactly the platform "
            "subset the inventory declares instead of the production four-platform matrix"
        ),
    )
    args = parser.parse_args()
    findings: list[str] = []
    try:
        manifests, recipes = inspect_zip(
            str(args.archive), args.archive, findings,
            require_participant_release=args.require_participant_release,
            allow_partial_platforms=args.allow_partial_platforms,
        )
    except zipfile.BadZipFile as error:
        raise SystemExit(
            f"participant artifact verification failed:\n{args.archive}: not a valid ZIP archive: {error}"
        )
    if manifests == 0:
        findings.append(
            "research-runtime/proxy-manifest.json was not found in the plugin archive; "
            "refusing an artifact whose proxy runtime cannot be verified"
        )
    if findings:
        raise SystemExit("participant artifact verification failed:\n" + "\n".join(findings))
    if recipes == 0:
        raise SystemExit(
            "participant artifact verification failed:\n"
            f"the packaged agent recipe {AGENT_MANIFEST_SUFFIX} was not found in the plugin archive; "
            "refusing an artifact whose agent identity cannot be verified"
        )
    print(f"participant artifact verified: {args.archive}")


if __name__ == "__main__":
    main()
