"""Branch, manual and release-tag contracts for the participant release workflow."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/resolve-participant-workflow-inputs.py"
SHA = "a" * 40
SERVER_SHA = "b" * 40


def invoke(tmp_path: Path, **overrides: str) -> tuple[subprocess.CompletedProcess[str], dict[str, str]]:
    output = tmp_path / "github-output.txt"
    env = {
        "PATH": os.environ["PATH"],
        "GITHUB_OUTPUT": str(output),
        "GITHUB_EVENT_NAME": "push",
        "GITHUB_REF": "refs/heads/feat/plugin",
        "GITHUB_SHA": SHA,
    }
    env.update(overrides)
    result = subprocess.run(
        [sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True, timeout=10
    )
    values = dict(line.split("=", 1) for line in output.read_text().splitlines()) if output.exists() else {}
    return result, values


def test_branch_push_builds_a_localhost_test_zip_and_ignores_dispatch_inputs(tmp_path: Path) -> None:
    result, values = invoke(
        tmp_path,
        DISPATCH_SERVER_TAG="ignored-release",
        DISPATCH_VERSION="9.9.9-test1",
    )
    assert result.returncode == 0, result.stderr
    assert values == {
        "server_url": "http://localhost:18080",
        "version": "0.0.1-branch.gaaaaaaaaaaaa",
        "server_repository": "AISE-TUDelft/code4me2-server",
        "server_ref": "feat/plugin",
        "local_test": "true",
    }


def test_branch_push_uses_repository_variables(tmp_path: Path) -> None:
    result, values = invoke(
        tmp_path,
        BRANCH_SERVER_REPOSITORY="someone/code4me2-server",
        BRANCH_SERVER_REF="release/v0.0.4+build.7",
    )
    assert result.returncode == 0, result.stderr
    assert values["server_repository"] == "someone/code4me2-server"
    assert values["server_ref"] == "release/v0.0.4+build.7"


def test_branch_push_with_https_and_server_commit_is_a_candidate(tmp_path: Path) -> None:
    result, values = invoke(
        tmp_path, BRANCH_SERVER_URL="https://study.example.org", BRANCH_SERVER_REF=SERVER_SHA,
    )
    assert result.returncode == 0, result.stderr
    assert values["local_test"] == "false"
    assert values["version"] == "0.0.1-branch.gaaaaaaaaaaaa"


@pytest.mark.parametrize("server_tag", ["runtime-v0.0.3", "runtime-v2.1.0"])
def test_manual_https_candidate_accepts_a_selected_tag(tmp_path: Path, server_tag: str) -> None:
    result, values = invoke(
        tmp_path, GITHUB_EVENT_NAME="workflow_dispatch",
        DISPATCH_SERVER_URL="https://study.example.org",
        DISPATCH_VERSION="1.2.3",
        DISPATCH_SERVER_REPOSITORY="AISE-TUDelft/code4me2-server",
        DISPATCH_SERVER_TAG=server_tag,
    )
    assert result.returncode == 0, result.stderr
    assert values["local_test"] == "false"
    assert values["server_ref"] == f"refs/tags/{server_tag}"


@pytest.mark.parametrize("server_tag", ["runtime-v0.0.4", "runtime-v2.1.0"])
def test_release_tag_sets_version_and_uses_the_selected_server_tag(tmp_path: Path, server_tag: str) -> None:
    result, values = invoke(
        tmp_path, GITHUB_REF="refs/tags/v1.2.3",
        RELEASE_SERVER_URL="https://study.example.org",
        RELEASE_SERVER_TAG=server_tag,
        DISPATCH_SERVER_TAG="ignored-release", BRANCH_SERVER_REF="ignored-branch",
        BRANCH_SERVER_URL="http://localhost:18080", DISPATCH_VERSION="9.9.9-test1",
    )
    assert result.returncode == 0, result.stderr
    assert values == {
        "server_url": "https://study.example.org",
        "version": "1.2.3",
        "server_repository": "AISE-TUDelft/code4me2-server",
        "server_ref": f"refs/tags/{server_tag}",
        "local_test": "false",
    }


@pytest.mark.parametrize(
    ("overrides", "error"),
    [
        ({"GITHUB_REF": "refs/tags/v1.2.3-beta"}, "release tag must be vX.Y.Z"),
        ({"GITHUB_REF": "refs/tags/v01.2.3"}, "release tag must be vX.Y.Z"),
        ({"RELEASE_SERVER_TAG": ""}, "server release tag must be"),
        ({"RELEASE_SERVER_TAG": "refs/tags/runtime-v0.0.4"}, "server release tag must be"),
        ({"RELEASE_SERVER_TAG": "../main"}, "server release tag must be"),
        ({"RELEASE_SERVER_TAG": "runtime-v0.0.4\nforged=true"}, "must not contain line breaks"),
        ({"RELEASE_SERVER_URL": ""}, "public HTTPS origin"),
        ({"RELEASE_SERVER_URL": "localhost:8008"}, "localhost HTTP or public HTTPS origin"),
        ({"RELEASE_SERVER_URL": "http://example.org:8008"}, "localhost HTTP or public HTTPS origin"),
    ],
)
def test_invalid_release_configuration_fails_before_build(
    tmp_path: Path, overrides: dict[str, str], error: str
) -> None:
    inputs = {
        "GITHUB_REF": "refs/tags/v1.2.3",
        "RELEASE_SERVER_URL": "https://study.example.org",
        "RELEASE_SERVER_TAG": "runtime-v0.0.4",
    }
    inputs.update(overrides)
    result, values = invoke(tmp_path, **inputs)
    assert result.returncode != 0
    assert error in result.stderr
    assert values == {}


@pytest.mark.parametrize("host", ["localhost", "127.0.0.1"])
def test_release_tag_accepts_a_local_backend(tmp_path: Path, host: str) -> None:
    result, values = invoke(
        tmp_path, GITHUB_REF="refs/tags/v0.0.1",
        RELEASE_SERVER_URL=f"http://{host}:8008",
        RELEASE_SERVER_TAG="runtime-v0.0.4",
    )
    assert result.returncode == 0, result.stderr
    assert values["server_url"] == f"http://{host}:8008"
    assert values["version"] == "0.0.1"
    assert values["local_test"] == "true"


def test_manual_localhost_dispatch_keeps_explicit_inputs(tmp_path: Path) -> None:
    result, values = invoke(
        tmp_path,
        GITHUB_EVENT_NAME="workflow_dispatch",
        GITHUB_REF="refs/tags/v1.2.3",
        DISPATCH_SERVER_URL="http://127.0.0.1:18080",
        DISPATCH_VERSION="0.0.5-study-test1",
        DISPATCH_SERVER_REPOSITORY="AISE-TUDelft/code4me2-server",
        DISPATCH_SERVER_TAG="runtime-v0.0.4",
        BRANCH_SERVER_REF="ignored",
    )
    assert result.returncode == 0, result.stderr
    assert values["version"] == "0.0.5-study-test1"
    assert values["server_ref"] == "refs/tags/runtime-v0.0.4"
    assert values["local_test"] == "true"


def test_numeric_sha_prefix_still_produces_semver_prerelease(tmp_path: Path) -> None:
    result, values = invoke(tmp_path, GITHUB_SHA="012345678901" + "a" * 28)
    assert result.returncode == 0, result.stderr
    assert values["version"] == "0.0.1-branch.g012345678901"


@pytest.mark.parametrize(
    ("overrides", "error"),
    [
        ({"BRANCH_SERVER_URL": "https://example.org"}, "full commit SHA as server ref"),
        ({"BRANCH_SERVER_REF": "feat/plugin\nforged=true"}, "must not contain line breaks"),
        ({"BRANCH_SERVER_URL": "http://localhost:18080\rforged=true"}, "must not contain line breaks"),
        ({"BRANCH_SERVER_REF": "../main"}, "server ref must be"),
        ({"BRANCH_SERVER_REF": "-main"}, "server ref must be"),
        ({"BRANCH_SERVER_REF": "main.lock"}, "server ref must be"),
        ({"BRANCH_SERVER_REPOSITORY": "code4me2-server"}, "owner/name"),
        ({"BRANCH_SERVER_URL": "http://example.org"}, "public HTTPS origin"),
        ({"BRANCH_SERVER_URL": "https://study.example.org/api", "BRANCH_SERVER_REF": SERVER_SHA}, "public HTTPS origin"),
        ({"GITHUB_REF": "refs/heads/main"}, "restricted to feat/plugin"),
        ({"GITHUB_SHA": "short"}, "full plugin commit SHA"),
    ],
)
def test_invalid_branch_configuration_fails_before_build(
    tmp_path: Path, overrides: dict[str, str], error: str
) -> None:
    result, values = invoke(tmp_path, **overrides)
    assert result.returncode != 0
    assert error in result.stderr
    assert values == {}


@pytest.mark.parametrize(
    ("overrides", "error"),
    [
        ({"DISPATCH_SERVER_TAG": ""}, "server release tag must be"),
        ({"DISPATCH_SERVER_TAG": "refs/heads/main"}, "server release tag must be"),
        ({"DISPATCH_VERSION": "0.0.5"}, "prerelease version"),
        ({"DISPATCH_VERSION": "five"}, "SemVer"),
        ({"DISPATCH_SERVER_REPOSITORY": ""}, "owner/name"),
    ],
)
def test_invalid_manual_inputs_fail_before_build(
    tmp_path: Path, overrides: dict[str, str], error: str
) -> None:
    inputs = {
        "GITHUB_EVENT_NAME": "workflow_dispatch",
        "DISPATCH_SERVER_URL": "http://localhost:8008",
        "DISPATCH_VERSION": "0.0.5-study-test1",
        "DISPATCH_SERVER_REPOSITORY": "AISE-TUDelft/code4me2-server",
        "DISPATCH_SERVER_TAG": "runtime-v0.0.4",
    }
    inputs.update(overrides)
    result, values = invoke(tmp_path, **inputs)
    assert result.returncode != 0
    assert error in result.stderr
    assert values == {}
