"""Branch and manual event contract for the participant release workflow."""

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
        DISPATCH_SERVER_REF="main",
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


def test_manual_https_candidate_accepts_stable_semver(tmp_path: Path) -> None:
    result, values = invoke(
        tmp_path, GITHUB_EVENT_NAME="workflow_dispatch",
        DISPATCH_SERVER_URL="https://study.example.org",
        DISPATCH_VERSION="1.2.3",
        DISPATCH_SERVER_REPOSITORY="AISE-TUDelft/code4me2-server",
        DISPATCH_SERVER_REF=SERVER_SHA,
    )
    assert result.returncode == 0, result.stderr
    assert values["local_test"] == "false"
    assert values["server_ref"] == SERVER_SHA


def test_manual_localhost_dispatch_keeps_explicit_inputs(tmp_path: Path) -> None:
    result, values = invoke(
        tmp_path,
        GITHUB_EVENT_NAME="workflow_dispatch",
        DISPATCH_SERVER_URL="http://127.0.0.1:18080",
        DISPATCH_VERSION="0.0.5-study-test1",
        DISPATCH_SERVER_REPOSITORY="AISE-TUDelft/code4me2-server",
        DISPATCH_SERVER_REF="feat/plugin",
        BRANCH_SERVER_REF="ignored",
    )
    assert result.returncode == 0, result.stderr
    assert values["version"] == "0.0.5-study-test1"
    assert values["server_ref"] == "feat/plugin"
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
        ({"DISPATCH_SERVER_REF": ""}, "server ref must be"),
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
        "DISPATCH_SERVER_REF": "feat/plugin",
    }
    inputs.update(overrides)
    result, values = invoke(tmp_path, **inputs)
    assert result.returncode != 0
    assert error in result.stderr
    assert values == {}
