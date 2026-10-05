#!/usr/bin/env python3
"""Resolve participant plugin build inputs for manual dispatch, branch pushes or release tags.

The plugin bundles no agent. Manual builds and plugin releases select a published
server source tag for the research proxy, independently of each study's agent.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from urllib.parse import urlsplit

DEFAULT_SERVER_REPOSITORY = "AISE-TUDelft/code4me2-server"


def valid_ref(ref: str) -> bool:
    return (
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+/-]{0,199}", ref) is not None
        and ".." not in ref and "//" not in ref
        and not ref.endswith(("/", ".", ".lock"))
    )


def resolve(env: dict[str, str]) -> dict[str, str]:
    event = env.get("GITHUB_EVENT_NAME", "")
    release_tag = event == "push" and env.get("GITHUB_REF", "").startswith("refs/tags/")
    if release_tag:
        tag = env["GITHUB_REF"].removeprefix("refs/tags/")
        if not re.fullmatch(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", tag):
            raise ValueError("Marketplace release tag must be vX.Y.Z without leading zeros")
        values = {
            "server_url": env.get("RELEASE_SERVER_URL", ""),
            "version": tag[1:],
            "server_repository": env.get("RELEASE_SERVER_REPOSITORY") or DEFAULT_SERVER_REPOSITORY,
            "server_ref": env.get("RELEASE_SERVER_TAG", ""),
        }
    elif event == "push":
        if env.get("GITHUB_REF") != "refs/heads/feat/plugin":
            raise ValueError("branch build is restricted to feat/plugin")
        sha = env.get("GITHUB_SHA", "")
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("branch build requires a full plugin commit SHA")
        values = {
            "server_url": env.get("BRANCH_SERVER_URL") or "http://localhost:18080",
            "version": f"0.0.1-branch.g{sha[:12]}",
            "server_repository": env.get("BRANCH_SERVER_REPOSITORY") or DEFAULT_SERVER_REPOSITORY,
            "server_ref": env.get("BRANCH_SERVER_REF") or "feat/plugin",
        }
    elif event == "workflow_dispatch":
        values = {
            "server_url": env.get("DISPATCH_SERVER_URL", ""),
            "version": env.get("DISPATCH_VERSION", ""),
            "server_repository": env.get("DISPATCH_SERVER_REPOSITORY", ""),
            "server_ref": env.get("DISPATCH_SERVER_TAG", ""),
        }
    else:
        raise ValueError(f"unsupported workflow event: {event}")

    # Every value becomes a `key=value` line of $GITHUB_OUTPUT.
    if any("\n" in value or "\r" in value for value in values.values()):
        raise ValueError("workflow inputs must not contain line breaks")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", values["server_repository"]):
        raise ValueError("server repository must be owner/name")
    if release_tag or event == "workflow_dispatch":
        if not valid_ref(values["server_ref"]) or values["server_ref"].startswith("refs/"):
            raise ValueError("server release tag must be a tag name, for example runtime-v0.0.4")
        values["server_ref"] = f"refs/tags/{values['server_ref']}"
    elif not valid_ref(values["server_ref"]):
        raise ValueError("server ref must be a branch, tag or commit SHA")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?", values["version"]):
        raise ValueError("plugin version must be SemVer")
    origin = urlsplit(values["server_url"])
    clean_origin = (
        bool(origin.hostname)
        and not origin.username and not origin.password
        and origin.path in {"", "/"}
        and not origin.query and not origin.fragment
    )
    localhost_test = (
        clean_origin and origin.scheme == "http"
        and origin.hostname in {"localhost", "127.0.0.1"}
        and origin.port is not None
    )
    public_candidate = (
        clean_origin and origin.scheme == "https"
        and origin.hostname not in {"localhost", "127.0.0.1", "::1"}
    )
    if localhost_test:
        if not release_tag and "-" not in values["version"].split("+", 1)[0]:
            raise ValueError("localhost test ZIPs require a SemVer prerelease version")
        values["local_test"] = "true"
    elif public_candidate:
        # Branch builds retain their existing deployed-server check; release tags
        # are resolved to one source commit by the workflow.
        if event == "push" and not release_tag and not re.fullmatch(r"[0-9a-f]{40}", values["server_ref"]):
            raise ValueError("HTTPS Marketplace candidates require the deployed server's full commit SHA as server ref")
        values["local_test"] = "false"
    else:
        raise ValueError("server URL must be a localhost HTTP or public HTTPS origin")
    return values


def main() -> None:
    values = resolve(dict(os.environ))
    output = os.environ.get("GITHUB_OUTPUT")
    if not output:
        raise ValueError("GITHUB_OUTPUT is required")
    with Path(output).open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")
    print(f"Resolved {os.environ['GITHUB_EVENT_NAME']} participant build: {values['version']}")


if __name__ == "__main__":
    main()
