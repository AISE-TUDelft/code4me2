"""Artifact verification, capability one-time use, and process lifecycle."""

from __future__ import annotations

import io
import os
import sys

import pytest

from conftest import fixture_path, sha256_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.lifecycle import (
    ArtifactVerificationError,
    ProxyProcess,
    ProxyState,
    UnsafeAgentPathError,
    build_environment,
    verify_artifact,
)
from telemetry_acp_proxy.spool_client import (
    CapabilityAlreadyConsumed,
    LocalSpoolClient,
    OneTimeCapability,
)


def test_one_time_capability_consumed_once_and_reuse_rejected():
    capability = OneTimeCapability("local-cap-123")
    assert capability.consumed is False
    assert capability.consume() == "local-cap-123"
    assert capability.consumed is True
    with pytest.raises(CapabilityAlreadyConsumed):
        capability.consume()


def test_artifact_digest_mismatch_refuses_launch_and_is_typed():
    agent = fixture_path("echo_agent.py")
    with pytest.raises(ArtifactVerificationError):
        verify_artifact(agent, "sha256:" + "0" * 64)

    diagnostics: list[str] = []
    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(agent)],
        agent_digest="sha256:" + "0" * 64,
        host_read=io.BytesIO(b""),
        host_write=io.BytesIO(),
        diagnostics=diagnostics.append,
    )
    assert exit_code == proxy_main.EXIT_ARTIFACT
    assert any("refusing to launch" in message for message in diagnostics)


def test_artifact_verification_rejects_unsafe_paths():
    with pytest.raises(UnsafeAgentPathError):
        verify_artifact("../agents/codex", sha256_digest(fixture_path("echo_agent.py")))


def test_artifact_verification_accepts_matching_digest():
    agent = fixture_path("echo_agent.py")
    resolved = verify_artifact(agent, sha256_digest(agent))
    assert resolved == agent.resolve()


def test_build_environment_allowlists_and_overrides():
    source = {"PATH": "/bin", "HOME": "/home/x", "SECRET_TOKEN": "nope"}
    environment = build_environment(["PATH", "HOME"], source=source, overrides={"LANG": "C"})
    assert environment == {"PATH": "/bin", "HOME": "/home/x", "LANG": "C"}
    assert "SECRET_TOKEN" not in environment


def test_proxy_process_terminates_child_process_group():
    process = ProxyProcess([sys.executable, "-c", "import time; time.sleep(30)"])
    process.start()
    assert process.state == ProxyState.RUNNING
    pid = process.pid
    assert pid is not None

    process.terminate(timeout=5)

    assert process.state == ProxyState.STOPPED
    assert process.returncode is not None
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


def test_spool_client_consumes_capability_and_uses_it():
    from telemetry_acp_proxy.framing import encode_message
    from telemetry_acp_proxy.normalize import ProxyNormalizer
    from telemetry_acp_proxy.observe import AcpDirection, Observer

    captured: list[tuple[str, str]] = []

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        captured.append((endpoint, capability))
        return {
            "accepted": [event["event_id"] for event in payload["events"]],
            "duplicate": [],
            "rejected": [],
        }

    observed = Observer().observe(
        AcpDirection.HOST_TO_AGENT,
        encode_message({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}),
    )[0]
    event = ProxyNormalizer().normalize_observed(observed)[0]

    capability = OneTimeCapability("cap-xyz")
    client = LocalSpoolClient(
        "file:///tmp/unused", capability, transport=transport, max_attempts=2
    )
    result = client.send([event])

    assert result.sent == 1
    assert capability.consumed is True
    assert captured == [("file:///tmp/unused", "cap-xyz")]
    with pytest.raises(CapabilityAlreadyConsumed):
        capability.consume()
