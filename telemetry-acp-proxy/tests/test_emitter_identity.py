"""Per-process emitter identity: uniqueness, family prefix, explicit override.

The proxy runs one process per ACP chat and its sequence allocator is in-memory
(it restarts at 1 on every launch). A constant emitter id therefore collides on
the server's ``(research_session_id, emitter_id, emitter_sequence)`` unique
constraint across processes in the same research session. These tests pin the
per-process identity that prevents it.
"""

from __future__ import annotations

import io
import json
import re
import sys
from pathlib import Path

from conftest import fixture_path, python_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.spool_client import (
    EMITTER_ID_PREFIX,
    SpoolSendResult,
    generate_emitter_id,
)

INITIALIZE_FRAME = encode_message(
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
)
EMITTER_ID_PATTERN = re.compile(r"^acp-proxy:[0-9a-f]{8}$")


def _spool_events(path: Path) -> list[dict]:
    events: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            events.extend(json.loads(line)["events"])
    return events


def _run_proxy_once(spool: Path) -> int:
    return proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{spool}",
        capability="cap-1",
        host_read=io.BytesIO(INITIALIZE_FRAME),
        host_write=io.BytesIO(),
        diagnostics=lambda _message: None,
    )


def _capture_spool(monkeypatch, sink: dict) -> None:
    """Replace the spool client with one that records its constructor kwargs."""

    class CapturingSpool:
        def __init__(self, endpoint, capability, **kwargs):
            sink["kwargs"] = kwargs

        def send(self, events):
            sink.setdefault("batches", []).append(list(events))
            sink["events"] = [event for batch in sink["batches"] for event in batch]
            return SpoolSendResult(sent=len(events), dropped=0)

    monkeypatch.setattr(proxy_main, "LocalSpoolClient", CapturingSpool)


def test_generated_emitter_ids_are_family_prefixed_and_unique():
    ids = [generate_emitter_id() for _ in range(2000)]

    assert all(EMITTER_ID_PATTERN.match(emitter_id) for emitter_id in ids)
    assert all(emitter_id.startswith(f"{EMITTER_ID_PREFIX}:") for emitter_id in ids)
    assert len(set(ids)) == len(ids), "two generated ids must never collide"


def test_two_simulated_processes_never_collide_on_session_emitter_sequence(tmp_path):
    spool = tmp_path / "spool.jsonl"

    assert _run_proxy_once(spool) == proxy_main.EXIT_OK
    assert _run_proxy_once(spool) == proxy_main.EXIT_OK

    events = _spool_events(spool)
    assert events, "each proxy process must have emitted events"

    pairs = [(event["emitter_id"], event["emitter_sequence"]) for event in events]
    assert len(set(pairs)) == len(pairs), (
        "two proxy processes in one session must not reuse "
        "(emitter_id, emitter_sequence)"
    )

    emitters = sorted({event["emitter_id"] for event in events})
    assert len(emitters) == 2, "two processes must use two distinct emitter ids"
    assert all(EMITTER_ID_PATTERN.match(emitter_id) for emitter_id in emitters)

    # Within one process the per-emitter sequence stays strictly monotonic.
    for emitter_id in emitters:
        sequences = [
            event["emitter_sequence"]
            for event in events
            if event["emitter_id"] == emitter_id
        ]
        assert sequences == sorted(sequences)
        assert len(set(sequences)) == len(sequences)


def test_run_proxy_mints_one_emitter_id_per_process(monkeypatch, tmp_path):
    sink: dict = {}
    _capture_spool(monkeypatch, sink)
    monkeypatch.setattr(proxy_main, "generate_emitter_id", lambda: "acp-proxy:deadbeef")

    exit_code = _run_proxy_once(tmp_path / "spool.jsonl")

    assert exit_code == proxy_main.EXIT_OK
    assert sink["kwargs"]["emitter_id"] == "acp-proxy:deadbeef"
    assert sink["events"], "the captured batch must carry events"
    assert all(event.emitter_id == "acp-proxy:deadbeef" for event in sink["events"])


def test_explicit_emitter_id_wins_over_the_generated_default(monkeypatch, tmp_path):
    sink: dict = {}
    _capture_spool(monkeypatch, sink)
    generated = {"called": False}

    def _generated_default():
        generated["called"] = True
        return "acp-proxy:00000000"

    monkeypatch.setattr(proxy_main, "generate_emitter_id", _generated_default)

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{tmp_path / 'spool.jsonl'}",
        capability="cap-1",
        emitter_id="acp-proxy:cafebabe",
        host_read=io.BytesIO(INITIALIZE_FRAME),
        host_write=io.BytesIO(),
        diagnostics=lambda _message: None,
    )

    assert exit_code == proxy_main.EXIT_OK
    assert sink["kwargs"]["emitter_id"] == "acp-proxy:cafebabe"
    assert generated["called"] is False, "an explicit id must not be overridden"
    assert all(event.emitter_id == "acp-proxy:cafebabe" for event in sink["events"])


def test_lifecycle_and_normalized_events_never_reuse_a_sequence(monkeypatch, tmp_path):
    """Proxy-owned lifecycle events share the process's per-emitter allocator."""
    sink: dict = {}
    _capture_spool(monkeypatch, sink)
    monkeypatch.setattr(proxy_main, "generate_emitter_id", lambda: "acp-proxy:5e900000")

    class _Sink:
        def write(self, chunk: bytes) -> int:
            return len(chunk)

        def flush(self) -> None:
            return None

        def close(self) -> None:
            return None

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path("malformed_agent.py"))],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{tmp_path / 'spool.jsonl'}",
        capability="cap-1",
        host_read=io.BytesIO(INITIALIZE_FRAME),
        host_write=_Sink(),
        diagnostics=lambda _message: None,
    )

    assert exit_code == proxy_main.EXIT_PARSE_FAILURE
    events = sink["events"]
    assert any(event.event_type.startswith("system.") for event in events)
    sequences = [event.emitter_sequence for event in events]
    assert sequences == sorted(sequences)
    assert len(set(sequences)) == len(sequences), (
        "a lifecycle event must not reuse a sequence already spent by the process"
    )


def test_cli_emitter_id_flag_reaches_run_proxy(monkeypatch):
    captured: dict = {}
    monkeypatch.setattr(
        proxy_main, "run_proxy", lambda **kwargs: captured.update(kwargs) or 0
    )

    code = proxy_main.main(
        [
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--capability",
            "cap-inline",
            "--emitter-id",
            "acp-proxy:cafebabe",
            "--agent-cmd",
            "agent",
        ]
    )

    assert code == 0
    assert captured["emitter_id"] == "acp-proxy:cafebabe"


def test_cli_without_emitter_id_defers_to_run_proxy(monkeypatch):
    captured: dict = {}
    monkeypatch.setattr(
        proxy_main, "run_proxy", lambda **kwargs: captured.update(kwargs) or 0
    )

    code = proxy_main.main(
        [
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--capability",
            "cap-inline",
            "--agent-cmd",
            "agent",
        ]
    )

    assert code == 0
    assert captured["emitter_id"] is None, (
        "run_proxy must mint the per-process default"
    )
