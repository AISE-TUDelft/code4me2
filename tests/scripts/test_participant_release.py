"""Exercise server-owned preparation against the plugin's final-artifact verifier."""
import copy
import importlib.util
import json
import sys
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[2]
SERVER = PLUGIN.parent / "code4me2-server"
sys.path.insert(0, str(SERVER / "src"))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


cli = module("participant_release_cli", PLUGIN / "scripts/participant-release.py")
verifier = module("participant_verifier", PLUGIN / "scripts/verify-participant-artifact.py")
fixtures = module("participant_inputs", SERVER / "tests/test_participant_release.py")
artifact_fixtures = module("artifact_fixtures", PLUGIN / "tests/scripts/test_verify_participant_artifact.py")

from research.study.agents.participant_release import prepare


def test_generated_catalog_passes_verifier_and_missing_agent_or_dependency_fails(tmp_path):
    recipe = fixtures.make_inputs(tmp_path / "inputs")
    prepare(recipe, tmp_path / "inputs", tmp_path / "prepared")
    catalog = json.loads((tmp_path / "prepared/catalog.json").read_text())
    payloads = {
        path.relative_to(tmp_path / "prepared/research-agents").as_posix(): path.read_bytes()
        for path in (tmp_path / "prepared/research-agents").rglob("*") if path.is_file()
    }
    for platform in catalog["platforms"]:
        proxy = f"platforms/{platform['os']}-{platform['arch']}/telemetry-acp-proxy"
        platform.update({
            "self_contained": True, "entrypoint": [proxy],
            "files": [artifact_fixtures.file_record(proxy, b"proxy", executable=True)],
        })
        payloads[proxy] = b"proxy"
    archive = artifact_fixtures.write_plugin_zip(tmp_path / "participant.zip", catalog, payloads)
    zip_findings = []
    assert verifier.inspect_zip("participant.zip", archive, zip_findings, require_participant_release=True) == 1
    assert zip_findings == []
    # Removing a dependency from the real nested ZIP must fail even when all
    # surviving members match their declared hashes.
    incomplete = dict(payloads)
    incomplete.pop(next(path for path in incomplete if path.endswith("_internal/library")))
    archive = artifact_fixtures.write_plugin_zip(tmp_path / "missing.zip", catalog, incomplete)
    zip_findings = []
    verifier.inspect_zip("missing.zip", archive, zip_findings, require_participant_release=True)
    assert any("missing" in message for message in zip_findings)
    findings = []
    verifier.verify_release_catalog(catalog, findings)
    assert findings == []
    bad = copy.deepcopy(catalog)
    bad["platforms"][0]["agents"] = []
    verifier.verify_release_catalog(bad, findings)
    assert any("exact managed release" in message for message in findings)
    bad = copy.deepcopy(catalog)
    bad["platforms"][0]["agents"][0]["files"].pop()
    findings = []
    verifier.verify_release_catalog(bad, findings)
    assert any("dependency inventory" in message for message in findings)
    bad = copy.deepcopy(catalog)
    bad["platforms"][0]["agents"][0]["execution"]["entrypoint"].append("--wrong")
    findings = []
    verifier.verify_release_catalog(bad, findings)
    assert any("digest mismatch" in message for message in findings)


def test_partial_local_catalog_requires_the_explicit_verification_mode(tmp_path):
    recipe = fixtures.make_inputs(tmp_path / "inputs")
    manifest_path = tmp_path / "inputs/runtime.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["artifacts"] = [
        artifact for artifact in manifest["artifacts"]
        if artifact["archive"] == "code4me-agent-macos-arm64.zip"
    ]
    fixtures.write_json(manifest_path, manifest)
    recipe.runtime.sha256 = fixtures.file_sha256(manifest_path)
    prepare(recipe, tmp_path / "inputs", tmp_path / "prepared", platforms=("macos-aarch64",))
    catalog = json.loads((tmp_path / "prepared/catalog.json").read_text())
    findings = []
    verifier.verify_release_catalog(catalog, findings)
    assert any("four native platforms" in message for message in findings)
    findings = []
    verifier.verify_release_catalog(catalog, findings, allow_partial_platforms=True)
    assert findings == []
    # A platform the inventory does not declare is still rejected.
    bad = copy.deepcopy(catalog)
    bad["platforms"] = bad["platforms"] + copy.deepcopy(bad["platforms"])
    findings = []
    verifier.verify_release_catalog(bad, findings, allow_partial_platforms=True)
    assert any("must match its inventory exactly" in message for message in findings)


class MemoryApi:
    def __init__(self):
        self.releases = {}
        self.posts = []
        self.fail_on = None

    def request(self, method, path, payload=None):
        if method == "GET":
            if path.endswith("/profiles"):
                return {"profiles": []}
            release = self.releases.get(path.rsplit("/", 1)[1])
            return {"model": release} if release else None
        if len(self.posts) == self.fail_on:
            raise RuntimeError("connection lost")
        self.posts.append((path, payload))
        if path == "/api/research/agents/releases":
            release = dict(payload["release"], qualification_status="UNQUALIFIED")
            self.releases[release["release_id"]] = release
            return {"accepted": True}
        raise AssertionError(path)


def test_apply_resumes_without_reposting_or_overwriting_records(tmp_path):
    recipe = fixtures.make_inputs(tmp_path / "inputs")
    plan = prepare(recipe, tmp_path / "inputs", tmp_path / "prepared")
    api = MemoryApi()
    api.fail_on = 1
    report = tmp_path / "report.json"
    with pytest.raises(RuntimeError, match="connection lost"):
        cli.apply_plan(plan, api, report, [])
    assert len(api.releases) == 1
    assert len(json.loads(report.read_text())["completed"]) == 1
    api.fail_on = None
    result = cli.apply_plan(plan, api, report, [])
    assert len(api.posts) == 3
    assert len(result["completed"]) == 3
    assert len(result["pending"]) == 3  # no invented qualification
    cli.apply_plan(plan, api, report, [])
    assert len(api.posts) == 3
    existing = next(iter(api.releases.values()))
    existing["version"] = "conflicting-version"
    with pytest.raises(ValueError, match="conflicts"):
        cli.apply_plan(plan, api, report, [])
    assert len(api.posts) == 3


def test_preflight_detects_later_conflict_before_registering_first_record(tmp_path):
    recipe = fixtures.make_inputs(tmp_path / "inputs")
    plan = prepare(recipe, tmp_path / "inputs", tmp_path / "prepared")
    api = MemoryApi()
    codex = dict(plan["releases"]["codex"], agent_command="wrong-agent")
    api.releases[codex["release_id"]] = codex
    with pytest.raises(ValueError, match="conflicts"):
        cli.apply_plan(plan, api, tmp_path / "report.json", [])
    assert api.posts == []


def test_release_mode_and_framework_must_be_complete(tmp_path):
    recipe = fixtures.make_inputs(tmp_path / "inputs").model_dump()
    recipe["agents"].pop()
    with pytest.raises(ValueError, match="exactly once"):
        fixtures.ParticipantRecipe.model_validate(recipe)
