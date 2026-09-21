#!/usr/bin/env python3
"""One recipe: prepare offline, build one ZIP, explicitly apply existing records.

Run with the server's Python environment. No phase publishes or deploys.
Only 'apply' contacts a server; credentials come from an environment variable.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

PLUGIN_ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the operator's cookie to a redirect target.
        return None


class Api:
    def __init__(self, origin: str, token: str):
        url = urlsplit(origin)
        if url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}):
            raise ValueError("apply requires HTTPS (HTTP is permitted only on loopback)")
        if not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in {"", "/"}:
            raise ValueError("server URL must be an origin without credentials, path or query")
        if not token or any(c in token for c in "\r\n;"):
            raise ValueError("a valid operator auth token environment variable is required")
        self.origin = origin.rstrip("/")
        self.token = token
        self.opener = build_opener(NoRedirect)

    def request(self, method, path, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        request = Request(self.origin + path, data=body, method=method, headers={
            "Cookie": "auth_token=" + self.token, "Content-Type": "application/json",
        })
        try:
            with self.opener.open(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            if error.code == 404 and method == "GET":
                return None
            # Do not echo response bodies which may contain sensitive server data.
            raise RuntimeError(f"{method} {path} returned HTTP {error.code}") from None


def apply_plan(plan, api, report_path, receipts):
    from research.study.agents.models import AgentReleaseV1
    from research.study.packaging.models import ConformanceReceiptV1
    from research.study.agents.registry import artifact_qualified, byoa_identity_qualified

    def normalized(value):
        return AgentReleaseV1.model_validate(value).model_dump(
            mode="json", exclude={"created_at", "qualification_status"})

    releases = plan["releases"]
    by_id = {r["release_id"]: r for r in releases.values()}
    evidence = []
    for raw in receipts:
        receipt = ConformanceReceiptV1.model_validate(raw)
        release = by_id.get(receipt.release_id)
        if release is None:
            raise ValueError("every receipt must name an exact release from this preparation")
        document = dict(release, conformance=[receipt.model_dump(mode="json")])
        if release["distribution_mode"] == "PACKAGED":
            valid = artifact_qualified(document, os_name=receipt.host.os, arch=receipt.host.arch,
                                       digest=receipt.artifact_digest, adapter_digest=receipt.adapter_digest)
        else:
            valid = byoa_identity_qualified(document)
        if not valid:
            raise ValueError("receipt does not qualify its exact release/platform/adapter/execution")
        evidence.append(receipt.model_dump(mode="json"))

    # Read-only conflict preflight across all records, before the first mutation.
    existing = {}
    for release in releases.values():
        path = "/api/research/agents/releases/" + quote(release["release_id"], safe="")
        existing[release["release_id"]] = api.request("GET", path)
        if existing[release["release_id"]] is not None:
            if normalized(existing[release["release_id"]]["model"]) != normalized(release):
                raise ValueError("registered release conflicts with immutable recipe content")
    # GET is owner scoped for researchers; admin lists can contain duplicate names.
    profiles = (api.request("GET", "/api/agent/profiles") or {}).get("profiles", []) if plan["profiles"] else []

    def profile_matches(wanted, actual):
        actual = dict(actual)
        actual["connection_id"] = (actual.get("connection") or {}).get("connection_id")
        for key, value in wanted.items():
            other = actual.get(key)
            if key == "tools_json":
                if json.loads(value) != json.loads(other or "[]"):
                    return False
            elif other != value:
                return False
        return True

    for wanted in plan["profiles"]:
        matches = [p for p in profiles if p["name"] == wanted["name"]]
        if matches and (len(matches) != 1 or not profile_matches(wanted, matches[0])):
            raise ValueError("existing profile name is ambiguous or has different content; choose a new name")
    report = {"recipe_digest": plan["inventory"]["recipe_digest"], "completed": [], "pending": []}

    def save():
        report_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = report_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n")
        temporary.replace(report_path)

    try:
        for release in releases.values():
            key = release["release_id"]
            if existing[key] is None:
                api.request("POST", "/api/research/agents/releases", {"release": release})
            report["completed"].append({"release_id": key})
            save()
        for receipt in evidence:
            api.request("POST", "/api/research/packages/receipts", receipt)
            report["completed"].append({"receipt_id": receipt["receipt_id"]})
            save()
        qualified = set()
        for release in releases.values():
            key = release["release_id"]
            current = api.request("GET", "/api/research/agents/releases/" + quote(key, safe=""))
            complete = False
            if current["model"]["qualification_status"] == "QUALIFIED":
                digests = ([a["sha256"] for a in release["artifacts"]]
                           if release["distribution_mode"] == "PACKAGED" else [release["source_manifest_digest"]])
                recorded = []
                for digest in digests:
                    response = api.request("GET", "/api/research/packages/receipts?artifact_digest=" + quote(digest, safe=""))
                    recorded.extend(response["receipts"])
                document = dict(release, conformance=recorded)
                complete = (all(artifact_qualified(
                    document, os_name=a["os"], arch=a["arch"], digest=a["sha256"],
                    adapter_digest=release["adapter"]["digest"],
                ) for a in release["artifacts"]) if release["distribution_mode"] == "PACKAGED"
                    else byoa_identity_qualified(document))
            if complete:
                qualified.add(key)
            else:
                report["pending"].append({"release_id": key, "reason": "qualification required"})
        for wanted in plan["profiles"]:
            if wanted["release_id"] not in qualified:
                report["pending"].append({"profile": wanted["name"], "reason": "release not qualified"})
                continue
            matches = [p for p in profiles if p["name"] == wanted["name"]]
            profile = matches[0] if matches else api.request("POST", "/api/agent/profiles", wanted)["profile"]
            report["completed"].append({"profile_id": profile["profile_id"], "release_id": wanted["release_id"]})
            save()
    except Exception:
        report["pending"].append({"reason": "apply interrupted; rerun to reconcile each record"})
        raise
    finally:
        save()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-source", type=Path, default=PLUGIN_ROOT.parent / "code4me2-server" / "src")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("validate", "prepare"):
        command = sub.add_parser(name)
        command.add_argument("recipe", type=Path)
        command.add_argument("--inputs", type=Path, required=True)
        if name == "prepare":
            command.add_argument("--output", type=Path, required=True)
    build = sub.add_parser("build")
    build.add_argument("prepared", type=Path)
    build.add_argument("--server-url", required=True)
    apply = sub.add_parser("apply")
    apply.add_argument("prepared", type=Path)
    apply.add_argument("--server-url", required=True)
    apply.add_argument("--auth-token-env", default="CODE4ME_RELEASE_AUTH_TOKEN")
    apply.add_argument("--receipts", type=Path, help="JSON array of real qualification receipts; never generated by this command")
    args = parser.parse_args()
    sys.path.insert(0, str(args.server_source.resolve()))
    from research.study.agents.participant_release import ParticipantRecipe, prepare, file_sha256, load_prepared
    if args.command in {"validate", "prepare"}:
        recipe = ParticipantRecipe.model_validate_json(args.recipe.read_text())
        if args.command == "prepare" and args.output.exists():
            raise ValueError("output already exists; use a new preparation directory")
        parent = args.output.resolve().parent if args.command == "prepare" else None
        if parent:
            parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".participant-release-", dir=parent) as temp:
            prepared = Path(temp) / "prepared"
            plan = prepare(recipe, args.inputs.resolve(), prepared)
            if args.command == "prepare":
                prepared.rename(args.output.resolve())
        print(json.dumps(plan["inventory"], indent=2))
        return
    prepared = args.prepared.resolve()
    plan = load_prepared(prepared)
    if args.command == "apply":
        receipts = json.loads(args.receipts.read_text()) if args.receipts else []
        report = apply_plan(plan, Api(args.server_url, os.environ.get(args.auth_token_env, "")),
                            prepared / "apply-report.json", receipts)
        print(json.dumps(report, indent=2))
        if report["pending"]:
            raise SystemExit(2)
        return
    inventory = plan["inventory"]
    for checkout, expected in ((PLUGIN_ROOT, inventory["plugin_commit"]),
                               (args.server_source.resolve().parent, inventory["server_commit"])):
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
    # A build is always local and never implicitly applies registration.
    command = [str(PLUGIN_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")),
               "--no-daemon", "--no-configuration-cache", "buildParticipantPlugin",
               "-PparticipantReleaseDir=" + str(prepared),
               "-PpluginVersion=" + inventory["plugin_version"],
               "-Pcode4me.serverUrl=" + args.server_url,
               "-PresearchProxyPlatforms=" + ",".join(inventory["platforms"]),
               "-PrequireResearchProxyBundles=true"]
    subprocess.run(command, cwd=PLUGIN_ROOT, check=True)
    # BuildPlugin's archive name is resolved by Gradle into this output manifest.
    artifact = Path((PLUGIN_ROOT / "build" / "participant-artifact-path.txt").read_text().strip())
    subprocess.run([sys.executable, str(PLUGIN_ROOT / "scripts/verify-participant-artifact.py"),
                    str(artifact), "--require-participant-release"], check=True)
    report = dict(inventory, zip_name=artifact.name, zip_sha256=file_sha256(artifact))
    (prepared / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
