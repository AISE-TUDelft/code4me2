"""Fault-injection: crash, malformed output, revoked capability, spool outage."""

from __future__ import annotations

import io
import json
import os
import sys
from pathlib import Path

import pytest

from conftest import fixture_path, python_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.lifecycle import ProxyProcess, ProxyState
from telemetry_acp_proxy.normalize import ProxyNormalizer
from telemetry_acp_proxy.observe import AcpDirection, Observer
from telemetry_acp_proxy.spool_client import LocalSpoolClient, OneTimeCapability

INITIALIZE_FRAME = encode_message(
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
)


def _spool_events(path: Path) -> list[dict]:
    events: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            events.extend(json.loads(line)["events"])
    return events


def _one_event():
    observed = Observer().observe(AcpDirection.HOST_TO_AGENT, INITIALIZE_FRAME)[0]
    return ProxyNormalizer().normalize_observed(observed)[0]


class _RecordingSink:
    """Binary sink that records forwarded bytes and ignores close."""

    def __init__(self) -> None:
        self.data = bytearray()

    def write(self, chunk: bytes) -> int:
        self.data.extend(chunk)
        return len(chunk)

    def flush(self) -> None:
        return None

    def close(self) -> None:
        return None


def test_agent_crash_emits_system_event_and_cleans_up(tmp_path):
    agent = fixture_path("crash_agent.py")
    spool = tmp_path / "spool.jsonl"
    diagnostics: list[str] = []

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(agent)],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{spool}",
        capability="cap-1",
        host_read=io.BytesIO(INITIALIZE_FRAME),
        host_write=io.BytesIO(),
        diagnostics=diagnostics.append,
    )

    assert exit_code == proxy_main.EXIT_AGENT_CRASH
    events = _spool_events(spool)
    assert any(event["event_type"] == "system.agent.crashed" for event in events)
    assert any(
        event["payload"].get("failure_kind") == "system_agent_crashed"
        for event in events
    )
    assert any("unexpectedly" in message for message in diagnostics)


def test_malformed_agent_output_is_metadata_only(tmp_path):
    agent = fixture_path("malformed_agent.py")
    spool = tmp_path / "spool.jsonl"
    diagnostics: list[str] = []
    host_out = _RecordingSink()

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(agent)],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{spool}",
        capability="cap-1",
        host_read=io.BytesIO(INITIALIZE_FRAME),
        host_write=host_out,
        diagnostics=diagnostics.append,
    )

    assert exit_code == proxy_main.EXIT_PARSE_FAILURE
    events = _spool_events(spool)
    parse_events = [
        event
        for event in events
        if event["payload"].get("failure_kind") == "acp_parse_failed"
    ]
    assert parse_events
    serialized = json.dumps(events)
    assert "not valid json" not in serialized
    # The malformed frame is still forwarded verbatim (protocol-preserving).
    assert b"not valid json" in bytes(host_out.data)


def test_spool_unavailable_is_bounded_and_accounted_not_buffered():
    attempts: list[int] = []
    slept: list[float] = []

    def failing_transport(endpoint: str, capability: str, payload: dict) -> None:
        attempts.append(1)
        raise OSError("spool receiver unavailable")

    client = LocalSpoolClient(
        "file:///unused",
        OneTimeCapability("cap-1"),
        transport=failing_transport,
        max_attempts=3,
        base_delay_seconds=0.01,
        jitter=lambda: 0.0,
        sleep=slept.append,
    )
    result = client.send([_one_event()])

    assert result.dropped == 1
    assert result.sent == 0
    assert result.attempts == 3
    assert len(attempts) == 3
    assert slept == [0.01, 0.02]  # capped exponential backoff, no unbounded queue
    assert result.error


def test_agent_process_tree_torn_down_on_revocation():
    process = ProxyProcess([sys.executable, "-c", "import time; time.sleep(30)"])
    process.start()
    pid = process.pid
    assert pid is not None

    process.terminate(timeout=5)

    assert process.state == ProxyState.STOPPED
    assert process.returncode is not None
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


def test_capability_rejection_is_reported_and_event_dropped():
    diagnostics: list[str] = []

    def rejecting_transport(endpoint: str, capability: str, payload: dict) -> None:
        raise OSError("capability revoked (403)")

    client = LocalSpoolClient(
        "file:///unused",
        OneTimeCapability("revoked-cap"),
        transport=rejecting_transport,
        max_attempts=2,
        base_delay_seconds=0.0,
        sleep=lambda _seconds: None,
        diagnostics=diagnostics.append,
    )
    result = client.send([_one_event()])

    assert result.dropped == 1
    assert any("dropping 1 canonical event" in message for message in diagnostics)
