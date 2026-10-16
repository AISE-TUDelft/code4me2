"""Exercise the publishing workflow's actual scripts without contacting either service."""

import hashlib
import json
import os
import re
import subprocess
import textwrap
from pathlib import Path

import pytest

WORKFLOW = (Path(__file__).resolve().parents[2] / ".github/workflows/build-participant-plugin.yml").read_text()


def run_script(name):
    step = WORKFLOW.split(f"      - name: {name}\n", 1)[1]
    body = step.split("        run: |\n", 1)[1]
    return textwrap.dedent(re.match(r"(?: {10,}.*\n|\n)+", body)[0])


@pytest.mark.parametrize(
    ("token", "tags", "api_status", "allowed"),
    [
        ("token", "v0.0.1\nv0.0.3", 0, True),
        ("token", "v0.0.1\nv0.0.2\nv0.0.3", 0, False),
        ("token", "", 1, False),
        ("", "", 0, False),
    ],
)
def test_publication_preflight(tmp_path, token, tags, api_status, allowed):
    gh = tmp_path / "gh"
    gh.write_text('#!/bin/sh\nprintf "%s\\n" "$FAKE_TAGS"\nexit "$FAKE_STATUS"\n')
    gh.chmod(0o755)
    result = subprocess.run(
        ["bash", "-c", run_script("Check publishing prerequisites before uploading")],
        env={**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}",
             "JETBRAINS_MARKETPLACE_TOKEN": token, "RELEASE_TAG": "v0.0.2",
             "GITHUB_REPOSITORY": "owner/plugin", "FAKE_TAGS": tags, "FAKE_STATUS": str(api_status)},
        capture_output=True, text=True, timeout=10,
    )
    assert (result.returncode == 0) == allowed, result.stderr


@pytest.mark.parametrize("damage", [None, "bytes", "plugin_commit", "plugin_version", "extra_zip", "missing_zip"])
def test_downloaded_zip_must_match_build_evidence(tmp_path, damage):
    distribution = tmp_path / "build/distributions"
    distribution.mkdir(parents=True)
    archive = distribution / "plugin.zip"
    archive.write_bytes(b"verified plugin archive")
    report = {"zip_name": archive.name, "zip_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
              "plugin_commit": "a" * 40, "plugin_version": "0.0.2"}
    evidence = tmp_path / "participant-release-evidence"
    evidence.mkdir()
    if damage == "bytes":
        archive.write_bytes(b"different archive")
    elif damage in {"plugin_commit", "plugin_version"}:
        report[damage] = "wrong"
    elif damage == "extra_zip":
        (distribution / "extra.zip").write_bytes(b"extra")
    elif damage == "missing_zip":
        archive.unlink()
    (evidence / "build-report.json").write_text(json.dumps(report))
    result = subprocess.run(
        ["bash", "-c", run_script("Check the downloaded ZIP against build evidence")],
        cwd=tmp_path, env={**os.environ, "RUNNER_TEMP": str(tmp_path),
                          "GITHUB_SHA": "a" * 40, "PLUGIN_VERSION": "0.0.2"},
        capture_output=True, text=True, timeout=10,
    )
    assert (result.returncode == 0) == (damage is None), result.stderr


def test_distribution_tasks_cannot_select_the_integration_test_subproject():
    tasks = "buildPlugin|verifyPluginStructure|verifyPlugin|publishPlugin|signPlugin"
    assert not re.search(rf"(?<!:)\b(?:{tasks})\b", WORKFLOW)


def test_plugin_tests_and_successful_build_gate_publishing_without_host_switches():
    build = WORKFLOW.split("  build:\n", 1)[1].split("  publish:\n", 1)[0]
    tests = build.split("      - name: Test, build and verify the agent-free participant ZIP\n", 1)[1].split("      - name:", 1)[0]
    publish = WORKFLOW.split("  publish:\n", 1)[1].split("  publish-github-release:\n", 1)[0]
    github_release = WORKFLOW.split("  publish-github-release:\n", 1)[1]
    assert "        if:" not in tests
    assert " :test :buildPlugin" in tests
    assert "    needs: [prepare-proxy-matrix, build]" in publish
    assert "    needs: [prepare-proxy-matrix, publish]" in github_release
    assert "fullHostSmoke" not in WORKFLOW
    assert "CODE4ME_RELEASE_FULL_HOST_SMOKE" not in WORKFLOW
    assert "host-smoke" not in WORKFLOW
