#!/usr/bin/env python3
"""One recipe: prepare offline, then build one ZIP.

Run with the server's Python environment. No phase publishes, deploys or
contacts a server; releases are registered through the website's import.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

PLUGIN_ROOT = Path(__file__).resolve().parents[1]


def recipe_platforms(document: dict) -> list[str]:
    """The recipe's platforms in the proxy/host vocabulary (``arm64`` -> ``aarch64``)."""
    platforms = {
        f"{artifact['platform']}-"
        f"{'aarch64' if artifact['architecture'] == 'arm64' else artifact['architecture']}"
        for artifact in document["artifacts"]
    }
    return sorted(platforms)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-source", type=Path, default=PLUGIN_ROOT.parent / "code4me2-server" / "src")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("validate", "prepare"):
        command = sub.add_parser(name)
        command.add_argument("recipe", type=Path)
        command.add_argument("--inputs", type=Path, required=True)
        command.add_argument(
            "--platforms",
            help=(
                "comma-separated native platform subset for a local test release "
                "(development only; a partial set requires CODE4ME_LOCAL_RELEASE=1)"
            ),
        )
        if name == "prepare":
            command.add_argument("--output", type=Path, required=True)
    build = sub.add_parser("build")
    build.add_argument("prepared", type=Path)
    build.add_argument("--server-url", required=True)
    build.add_argument("--managed-only-test", action="store_true", help="localhost prerelease with only the packaged agent")
    args = parser.parse_args()
    sys.path.insert(0, str(args.server_source.resolve()))
    from research.study.agents.participant_release import (
        PLATFORMS, ParticipantRecipe, prepare, file_sha256, load_prepared,
    )

    def requested_platforms():
        if getattr(args, "platforms", None) is None:
            return PLATFORMS
        selected = tuple(token.strip() for token in args.platforms.split(",") if token.strip())
        if set(selected) != set(PLATFORMS) and os.environ.get("CODE4ME_LOCAL_RELEASE") != "1":
            raise ValueError(
                "a partial platform set is a local test release; set CODE4ME_LOCAL_RELEASE=1"
            )
        return selected

    if args.command in {"validate", "prepare"}:
        recipe = ParticipantRecipe.model_validate_json(args.recipe.read_text())
        if args.command == "prepare" and args.output.exists():
            raise ValueError("output already exists; use a new preparation directory")
        parent = args.output.resolve().parent if args.command == "prepare" else None
        if parent:
            parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".participant-release-", dir=parent) as temp:
            prepared = Path(temp) / "prepared"
            document = prepare(recipe, args.inputs.resolve(), prepared, platforms=requested_platforms())
            if args.command == "prepare":
                prepared.rename(args.output.resolve())
        print(json.dumps(document, indent=2))
        return
    prepared = args.prepared.resolve()
    recipe = load_prepared(prepared)
    platforms = recipe_platforms(recipe)
    production_platforms = {platform.replace("-arm64", "-aarch64") for platform in PLATFORMS}
    local_release = set(platforms) != production_platforms
    if local_release and os.environ.get("CODE4ME_LOCAL_RELEASE") != "1":
        raise ValueError("this preparation is a partial local test release; set CODE4ME_LOCAL_RELEASE=1")
    if args.command == "build" and args.managed_only_test:
        origin = urlsplit(args.server_url)
        if (origin.scheme != "http" or origin.hostname not in {"localhost", "127.0.0.1"}
                or origin.port is None or origin.username or origin.password
                or origin.path not in {"", "/"} or origin.query or origin.fragment
                or "-" not in recipe["plugin_version"] or recipe["agents"]):
            raise ValueError("managed-only test builds require a localhost prerelease without external agents")
    for checkout, expected in ((PLUGIN_ROOT, recipe["plugin_commit"]),
                               (args.server_source.resolve().parent, recipe["server_commit"])):
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
        if actual != expected:
            raise ValueError("source checkout does not match the recipe's exact commit")
        changes = subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=checkout, text=True).strip()
        if changes:
            raise ValueError("release builds require committed source inputs; existing local changes were preserved")
        untracked = subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard", "--",
             "src", "scripts", "packaging", "telemetry-acp-proxy"],
            cwd=checkout, text=True,
        ).strip()
        if untracked:
            raise ValueError("release builds require all source inputs to be tracked")
    # A build is always local and registers nothing on a server.
    command = [str(PLUGIN_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")),
               "--no-daemon", "--no-configuration-cache", "buildParticipantPlugin",
               "-PparticipantReleaseDir=" + str(prepared),
               "-PpluginVersion=" + recipe["plugin_version"],
               "-Pcode4me.serverUrl=" + args.server_url,
               "-PresearchProxyPlatforms=" + ",".join(platforms),
               "-PrequireResearchProxyBundles=true"]
    if local_release or args.managed_only_test:
        command.append("-PparticipantLocalRelease=true")
    subprocess.run(command, cwd=PLUGIN_ROOT, check=True)
    # BuildPlugin's archive name is resolved by Gradle into this output manifest.
    artifact = Path((PLUGIN_ROOT / "build" / "participant-artifact-path.txt").read_text().strip())
    verification = [sys.executable, str(PLUGIN_ROOT / "scripts/verify-participant-artifact.py"), str(artifact)]
    verification.append("--allow-partial-platforms" if local_release else "--require-participant-release")
    if args.managed_only_test:
        verification.append("--allow-managed-only")
    subprocess.run(verification, check=True)
    report = {
        "plugin_version": recipe["plugin_version"],
        "plugin_commit": recipe["plugin_commit"],
        "server_commit": recipe["server_commit"],
        "platforms": platforms,
        "recipe_digest": recipe["recipe_digest"],
        "agent_archives": [
            {"archive": item["archive"], "sha256": item["sha256"]}
            for item in recipe["artifacts"]
        ],
        "zip_name": artifact.name,
        "zip_sha256": file_sha256(artifact),
    }
    (prepared / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
