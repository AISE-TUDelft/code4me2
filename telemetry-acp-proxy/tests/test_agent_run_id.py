"""D-2/TA-01: the activation run id reaches the proxy and is stamped on events.

The proxy receives ``--agent-run-id`` (or ``CODE4ME_RESEARCH_RUN_ID`` from the
entry env) and stamps ``agent_run_id`` centrally on every canonical event it
delivers: normalized ACP observations and proxy-owned lifecycle events alike.
"""

from __future__ import annotations

import io
import sys
from datetime import datetime, timezone

from conftest import fixture_path, python_digest  # type: ignore[import-not-found]

# Importing the proxy entry point first bootstraps the shared server tree onto
# sys.path (``_bootstrap.ensure_research_on_path``), so the ``research`` imports
# below resolve regardless of pytest collection order.
from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.spool_client import SpoolSendResult

from research.telemetry.builder import EventBuilder
from research.telemetry.enums import CanonicalEventType, EventSource

INITIALIZE_FRAME = encode_message(
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
)


def _capture_events(monkeypatch) -> dict:
    """Replace the spool client with one that records the delivered events."""
    sink: dict = {}

    class CapturingSpool:
        def __init__(self, endpoint, capability, **kwargs):
            pass

        def send(self, events):
            sink.setdefault("events", []).extend(events)
            return SpoolSendResult(sent=len(events), dropped=0)

    monkeypatch.setattr(proxy_main, "LocalSpoolClient", CapturingSpool)
    return sink


def _run(agent_fixture: str, sink: dict, *, agent_run_id, host_write=None) -> int:
    return proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path(agent_fixture))],
        agent_digest=python_digest(),
        spool_endpoint="file:///tmp/agent-run-id-spool.jsonl",
        capability="cap-1",
        agent_run_id=agent_run_id,
        host_read=io.BytesIO(INITIALIZE_FRAME),
        host_write=host_write if host_write is not None else io.BytesIO(),
        diagnostics=lambda _message: None,
    )


def test_parser_accepts_the_agent_run_id_flag():
    args = proxy_main.build_parser().parse_args(
        ["--agent-run-id", "run-9", "--agent-digest", "d", "--agent-cmd", "agent"]
    )
    assert args.agent_run_id == "run-9"


def test_resolve_agent_run_id_prefers_the_flag_then_the_environment():
    env = {proxy_main.AGENT_RUN_ID_ENV_VAR: "env-run"}
    assert proxy_main.resolve_agent_run_id("flag-run", env) == "flag-run"
    assert proxy_main.resolve_agent_run_id(None, env) == "env-run"
    assert proxy_main.resolve_agent_run_id("   ", env) == "env-run"
    assert proxy_main.resolve_agent_run_id(None, {}) is None
    assert (
        proxy_main.resolve_agent_run_id(
            None, {proxy_main.AGENT_RUN_ID_ENV_VAR: "   "}
        )
        is None
    )


def test_cli_flag_reaches_run_proxy(monkeypatch):
    captured: dict = {}
    monkeypatch.setattr(
        proxy_main, "run_proxy", lambda **kwargs: captured.update(kwargs) or 0
    )

    code = proxy_main.main(
        [
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--capability",
            "cap",
            "--agent-run-id",
            "run-cli",
            "--agent-cmd",
            "agent",
        ]
    )

    assert code == 0
    assert captured["agent_run_id"] == "run-cli"


def test_cli_falls_back_to_the_entry_environment(monkeypatch):
    captured: dict = {}
    monkeypatch.setattr(
        proxy_main, "run_proxy", lambda **kwargs: captured.update(kwargs) or 0
    )
    monkeypatch.setenv(proxy_main.AGENT_RUN_ID_ENV_VAR, "run-env")

    code = proxy_main.main(
        [
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--capability",
            "cap",
            "--agent-cmd",
            "agent",
        ]
    )

    assert code == 0
    assert captured["agent_run_id"] == "run-env"


def test_normalized_events_carry_the_activation_run_id(monkeypatch):
    sink = _capture_events(monkeypatch)

    exit_code = _run("echo_agent.py", sink, agent_run_id="run-42")

    assert exit_code == proxy_main.EXIT_OK
    assert sink["events"], "the activation must produce canonical events"
    assert all(event.agent_run_id == "run-42" for event in sink["events"])


def test_parse_failure_lifecycle_event_carries_the_activation_run_id(monkeypatch):
    sink = _capture_events(monkeypatch)

    exit_code = _run("malformed_agent.py", sink, agent_run_id="run-life")

    assert exit_code == proxy_main.EXIT_PARSE_FAILURE
    lifecycle = [event for event in sink["events"] if event.event_type.startswith("system.")]
    assert lifecycle, "the malformed frame must produce a proxy lifecycle event"
    assert all(event.agent_run_id == "run-life" for event in lifecycle)


def test_agent_crash_lifecycle_event_carries_the_activation_run_id(monkeypatch):
    sink = _capture_events(monkeypatch)

    exit_code = _run("crash_agent.py", sink, agent_run_id="run-crash")

    assert exit_code == proxy_main.EXIT_AGENT_CRASH
    lifecycle = [event for event in sink["events"] if event.event_type.startswith("system.")]
    assert lifecycle, "the crash must produce a proxy lifecycle event"
    assert all(event.agent_run_id == "run-crash" for event in lifecycle)


def test_events_stay_null_without_an_activation_run_id(monkeypatch):
    sink = _capture_events(monkeypatch)

    exit_code = _run("echo_agent.py", sink, agent_run_id=None)

    assert exit_code == proxy_main.EXIT_OK
    assert sink["events"]
    assert all(event.agent_run_id is None for event in sink["events"])


def test_stamping_is_additive_and_preserves_an_existing_run_id():
    event = EventBuilder().build(
        emitter_id="acp-proxy",
        event_type=CanonicalEventType.AGENT_MESSAGE_STARTED,
        source=EventSource.ACP,
        occurred_at=datetime.now(timezone.utc),
        normalizer_version="test",
        emitter_sequence=1,
    )
    assert event.agent_run_id is None

    stamped = proxy_main._stamp_agent_run_id(event, "run-42")
    assert stamped.agent_run_id == "run-42"
    # The original frozen event is untouched.
    assert event.agent_run_id is None

    already = event.model_copy(update={"agent_run_id": "other-run"})
    assert proxy_main._stamp_agent_run_id(already, "run-42").agent_run_id == "other-run"
    assert proxy_main._stamp_agent_run_id(event, None).agent_run_id is None
