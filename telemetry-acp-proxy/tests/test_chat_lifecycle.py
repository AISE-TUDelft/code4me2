"""Chat lifecycle in the proxy: per-chat attribution, end events, elicitation.

* every event is stamped with the chat it belongs to: a second chat in a reused
  process, interleaved chats and answers to either side's requests never take
  another chat's id;
* the proxy's final event says how the chat's process ended, for each
  ``chat_lifecycle.PROXY_END_REASONS`` value (a real SIGTERM included); on a
  signal it reaches the spool once, before the agent is stopped, so the host's
  SIGKILL moments later cannot lose it;
* ``elicitation/create`` round-trips are forwarded byte for byte and only their
  method and parameter names are recorded.
"""

from __future__ import annotations

import io
import json
import os
import queue
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import pytest

from conftest import (  # type: ignore[import-not-found]
    COMPONENT_DIR,
    fixture_path,
    python_digest,
    sha256_digest,
)

# Importing the proxy entry point first bootstraps the shared server tree onto
# sys.path, so the ``research`` imports below resolve in any collection order.
from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.lifecycle import DEFAULT_TERMINATE_TIMEOUT_SECONDS, ProxyState
from telemetry_acp_proxy.normalize import ProxyNormalizer
from telemetry_acp_proxy.observe import AcpDirection, Observer
from telemetry_acp_proxy.privacy_gate import PrivacyGate, interaction_ended_event
from telemetry_acp_proxy.spool_client import SpoolSendResult

from research.telemetry.chat_lifecycle import (
    ACP_METHOD_KEY,
    END_REASON_AGENT_EXITED,
    END_REASON_HOST_CLOSED,
    END_REASON_KEY,
    END_REASON_SESSION_STALE,
    END_REASON_SIGNAL_TERMINATED,
    PROXY_END_REASONS,
)
from research.telemetry.privacy import PrivacyPolicy

from test_initialize_replay import _MemoryChannel  # type: ignore[import-not-found]

HOST = AcpDirection.HOST_TO_AGENT
AGENT = AcpDirection.AGENT_TO_HOST


def _request(request_id, method, params=None):
    return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}


def _result(request_id, result):
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _prompt(prompt_id, session_id):
    return _request(
        prompt_id,
        "session/prompt",
        {"sessionId": session_id, "prompt": [{"type": "text", "text": "hi"}]},
    )


def _chunk(session_id):
    return {
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {
            "sessionId": session_id,
            "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "hi"}},
        },
    }


def _normalized(entries):
    """Normalize ``[(direction, message), ...]`` as one proxy stream."""
    observer = Observer()
    normalizer = ProxyNormalizer()
    events = []
    for direction, message in entries:
        for observed in observer.observe(direction, encode_message(message)):
            events.extend(normalizer.normalize_observed(observed))
    return normalizer, events


# ---------------------------------------------------------------------------
# Attribution: each event carries its own chat's id
# ---------------------------------------------------------------------------


def test_a_second_chat_in_a_reused_process_gets_its_own_id():
    normalizer, events = _normalized(
        [
            (HOST, _request(1, "initialize", {"protocolVersion": 1})),
            (AGENT, _result(1, {"protocolVersion": 1})),
            (HOST, _request(2, "session/new", {"cwd": "/w"})),
            (AGENT, _result(2, {"sessionId": "chat-1"})),
            (HOST, _prompt(3, "chat-1")),
            (AGENT, _chunk("chat-1")),
            (AGENT, _result(3, {"stopReason": "end_turn"})),
            # The host reuses the process for another chat (a resubmitted prompt).
            (HOST, _request(4, "initialize", {"protocolVersion": 1})),
            (AGENT, _result(4, {"protocolVersion": 1})),
            (HOST, _request(5, "session/new", {"cwd": "/w"})),
            (AGENT, _result(5, {"sessionId": "chat-2"})),
            (HOST, _prompt(6, "chat-2")),
            (AGENT, _result(6, {"stopReason": "end_turn"})),
        ]
    )

    starts = [event for event in events if event.event_type == "interaction.started"]
    assert [(event.payload[ACP_METHOD_KEY], event.payload.get("session_id")) for event in starts] == [
        ("initialize", None),
        ("session/new", "chat-1"),
        ("initialize", None),
        ("session/new", "chat-2"),
    ]
    second_chat = events[events.index(starts[3]) :]
    assert [event.event_type for event in second_chat] == [
        "interaction.started",
        "agent.message.started",
        "agent.message.started",
        "agent.message.completed",
    ]
    assert {event.payload.get("session_id") for event in second_chat} == {"chat-2"}
    assert normalizer.current_session_id == "chat-2"


def test_a_failed_new_chat_is_not_attributed_to_the_previous_chat():
    _, events = _normalized(
        [
            (HOST, _request(2, "session/new")),
            (AGENT, _result(2, {"sessionId": "chat-1"})),
            (HOST, _prompt(3, "chat-1")),
            (AGENT, _result(3, {"stopReason": "end_turn"})),
            (HOST, _request(4, "session/new")),
            (AGENT, {"jsonrpc": "2.0", "id": 4, "error": {"code": -32603, "message": "x"}}),
        ]
    )

    failed = events[-1]
    assert failed.event_type == "agent.error"
    assert "session_id" not in failed.payload


def test_interleaved_chats_keep_their_own_ids():
    _, events = _normalized(
        [
            (HOST, _prompt(3, "chat-a")),
            (HOST, _prompt(9, "chat-b")),
            (AGENT, _chunk("chat-a")),
            (
                AGENT,
                _request(
                    4,
                    "session/request_permission",
                    {
                        "sessionId": "chat-a",
                        "toolCall": {"toolCallId": "call-a"},
                        "options": [{"optionId": "allow", "kind": "allow_once"}],
                    },
                ),
            ),
            (AGENT, _chunk("chat-b")),
            # Answers arrive after the other chat spoke last.
            (HOST, _result(4, {"outcome": {"outcome": "selected", "optionId": "allow"}})),
            (AGENT, _result(9, {"stopReason": "end_turn"})),
            (AGENT, _result(3, {"stopReason": "end_turn"})),
        ]
    )

    chats = {"3": "chat-a", "9": "chat-b"}
    observed = [
        (event.event_type, event.correlations.turn_id, event.payload.get("session_id"))
        for event in events
    ]
    assert len(observed) == 10
    assert all(session_id == chats[turn_id] for _, turn_id, session_id in observed), observed


def test_answers_are_matched_to_their_requests_per_direction():
    # Host and agent number their requests independently: id 7 is an agent
    # request in chat-a and a host request in chat-b at the same time.
    _, events = _normalized(
        [
            (AGENT, _request(7, "fs/read_text_file", {"sessionId": "chat-a", "path": "/w/a.py"})),
            (HOST, _request(7, "session/set_mode", {"sessionId": "chat-b", "modeId": "ask"})),
            (HOST, _result(7, {"content": "CANARY-FILE-TEXT"})),
            (AGENT, _result(7, {})),
        ]
    )

    host_answer, agent_answer = events[-2:]
    assert host_answer.payload["session_id"] == "chat-a"
    assert agent_answer.payload["session_id"] == "chat-b"


# ---------------------------------------------------------------------------
# The end-of-process event
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("end_reason", sorted(PROXY_END_REASONS))
def test_interaction_ended_event_is_a_metadata_only_chat_end(end_reason):
    event = interaction_ended_event(
        end_reason, occurred_at=datetime.now(timezone.utc), session_id="chat-1"
    )

    assert event.event_type == "interaction.completed"
    assert event.source == "acp"
    # ``completed`` is what keeps a chat end out of the cancel count.
    assert event.lifecycle_state == "completed"
    assert event.payload == {END_REASON_KEY: end_reason, "session_id": "chat-1"}
    filtered = PrivacyGate().filter(event)
    assert not filtered.privacy.blocked
    assert filtered.payload == event.payload


def test_interaction_ended_event_without_a_chat_names_none():
    event = interaction_ended_event(END_REASON_HOST_CLOSED, occurred_at=datetime.now(timezone.utc))

    assert event.payload == {END_REASON_KEY: END_REASON_HOST_CLOSED}


def test_interaction_ended_event_rejects_an_unknown_reason():
    with pytest.raises(ValueError):
        interaction_ended_event("deleted", occurred_at=datetime.now(timezone.utc))


def _capture_events(monkeypatch) -> list:
    """Replace the spool client with one that records the delivered events."""
    sink: list = []

    class CapturingSpool:
        def __init__(self, endpoint, capability, **kwargs):
            pass

        def send(self, events, *, max_attempts=None):
            sink.extend(events)
            return SpoolSendResult(sent=len(events), dropped=0)

    monkeypatch.setattr(proxy_main, "LocalSpoolClient", CapturingSpool)
    return sink


def _record_sends(monkeypatch, *, outcome=None) -> list:
    """Replace the spool client with one that records every ``send`` call.

    Each entry is ``(max_attempts, events)``: ``1`` for a direct report, ``None``
    for a batch from the delivery queue. ``outcome(events, max_attempts)`` may
    block a call or fail it (raise, or return a result); ``None`` acknowledges.
    """
    calls: list = []

    class RecordingSpool:
        def __init__(self, endpoint, capability, **kwargs):
            pass

        def send(self, events, *, max_attempts=None):
            events = list(events)
            calls.append((max_attempts, events))
            result = outcome(events, max_attempts) if outcome is not None else None
            return result or SpoolSendResult(sent=len(events), dropped=0)

    monkeypatch.setattr(proxy_main, "LocalSpoolClient", RecordingSpool)
    return calls


def _ended(events):
    """The one end event. It is reported as soon as the chat ends, so agent
    output still being drained may follow it with higher sequences."""
    ended = [
        event
        for event in events
        if event.event_type == "interaction.completed" and END_REASON_KEY in event.payload
    ]
    assert len(ended) == 1, [event.payload for event in ended]
    return ended[0]


def _stub_process(monkeypatch, agent_input, agent_output, *, returncode=0, on_terminate=None):
    """Run the agent side over in-memory channels; ``terminate`` closes its output."""
    calls = {"terminate": 0}

    class StubProcess:
        def __init__(self, command, env_overrides=None, provider_env=False):
            self.stdin = agent_input
            self.stdout = agent_output
            self.state = ProxyState.STOPPED
            self.returncode = returncode

        def start(self):
            return None

        def terminate(self):
            calls["terminate"] += 1
            if on_terminate is not None:
                on_terminate()
            agent_output.close()

        def wait(self, timeout=None):
            return self.returncode

    monkeypatch.setattr(proxy_main, "ProxyProcess", StubProcess)
    return calls


def _run_stubbed(host_read, host_write, **kwargs) -> int:
    fixture = fixture_path("echo_agent.py")
    return proxy_main.run_proxy(
        agent_cmd=[str(fixture)],
        agent_digest=sha256_digest(fixture),
        spool_endpoint="file:///unused-spool.jsonl",
        capability="cap-1",
        host_read=host_read,
        host_write=host_write,
        diagnostics=lambda _message: None,
        **kwargs,
    )


def _wait_closed(channel, timeout: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and not channel._closed:
        time.sleep(0.02)
    return channel._closed


def test_a_host_that_closes_stdin_ends_the_chat_as_host_closed(monkeypatch):
    sink = _capture_events(monkeypatch)

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
        agent_digest=python_digest(),
        spool_endpoint="file:///unused-spool.jsonl",
        capability="cap-1",
        host_read=io.BytesIO(encode_message(_prompt(3, "chat-1"))),
        host_write=io.BytesIO(),
        diagnostics=lambda _message: None,
    )

    assert exit_code == proxy_main.EXIT_OK
    ended = _ended(sink)
    assert ended.payload == {END_REASON_KEY: END_REASON_HOST_CLOSED, "session_id": "chat-1"}
    assert ended.lifecycle_state == "completed"


def test_an_agent_that_exits_first_ends_the_chat_as_agent_exited(monkeypatch):
    sink = _capture_events(monkeypatch)
    host_input, host_output = _MemoryChannel(), _MemoryChannel()
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    _stub_process(monkeypatch, agent_input, agent_output)
    result: dict = {}

    thread = threading.Thread(
        target=lambda: result.update(exit=_run_stubbed(host_input, host_output)), daemon=True
    )
    thread.start()
    try:
        host_input.write(encode_message(_prompt(3, "chat-1")))
        agent_input.read_line()  # the prompt reached the agent
        agent_output.close()  # the agent exits on its own; the host stays open
        assert _wait_closed(host_output)
    finally:
        host_input.close()
        thread.join(timeout=10.0)

    assert result["exit"] == proxy_main.EXIT_OK
    ended = _ended(sink)
    assert ended.payload == {END_REASON_KEY: END_REASON_AGENT_EXITED, "session_id": "chat-1"}


def test_a_stale_research_session_ends_the_chat_as_session_stale(monkeypatch, tmp_path):
    sink = _capture_events(monkeypatch)
    monkeypatch.setattr(proxy_main, "STALE_SESSION_POLL_SECONDS", 0.05)
    capability_file = tmp_path / "capability"
    capability_file.write_text("cap-1", encoding="utf-8")
    host_input, host_output = _MemoryChannel(), _MemoryChannel()
    result: dict = {}

    def run():
        result["exit"] = proxy_main.run_proxy(
            agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
            agent_digest=python_digest(),
            spool_endpoint="file:///unused-spool.jsonl",
            capability="cap-1",
            capability_file=str(capability_file),
            host_read=host_input,
            host_write=host_output,
            diagnostics=lambda _message: None,
        )

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    try:
        frame = encode_message(_prompt(3, "chat-1"))
        host_input.write(frame)
        assert host_output.read_line() == frame  # echoed: the chat is live
        capability_file.unlink()  # the plugin tore the research session down
        assert _wait_closed(host_output)
    finally:
        host_input.close()
        thread.join(timeout=10.0)

    assert result["exit"] == proxy_main.EXIT_SPOOL_REJECTED
    ended = _ended(sink)
    assert ended.payload == {END_REASON_KEY: END_REASON_SESSION_STALE, "session_id": "chat-1"}


def _signal_during_forwarding(monkeypatch) -> None:
    """Replace forwarding: it sees one host prompt, then ``main``'s handler fires."""

    class SignalledForwarder:
        def __init__(self, *, observer, **_kwargs):
            self.observer = observer

        def run(self, *_args, **_kwargs):
            self.observer.observe(HOST, encode_message(_prompt(3, "chat-1")))
            raise proxy_main.ProxyTerminated(signal.SIGTERM)

    monkeypatch.setattr(proxy_main, "AcpForwarder", SignalledForwarder)


def test_a_termination_signal_stops_the_agent_and_ends_the_chat(monkeypatch):
    sink = _capture_events(monkeypatch)
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    # The stopped agent reports a signal exit status; it must not read as a crash.
    calls = _stub_process(monkeypatch, agent_input, agent_output, returncode=-15)
    _signal_during_forwarding(monkeypatch)

    exit_code = _run_stubbed(io.BytesIO(), io.BytesIO())

    assert exit_code == proxy_main.EXIT_OK
    assert calls["terminate"] == 1
    ended = _ended(sink)
    assert ended.payload == {END_REASON_KEY: END_REASON_SIGNAL_TERMINATED, "session_id": "chat-1"}
    assert not any(event.event_type == "system.agent.crashed" for event in sink)


def test_a_termination_signal_reports_the_chat_end_before_stopping_the_agent(monkeypatch):
    order: list = []
    agent_stopped = threading.Event()

    def outcome(events, max_attempts):
        if max_attempts is None:
            # A queued batch is held until the agent stops: the end report must
            # not wait behind it.
            agent_stopped.wait(timeout=10.0)
        order.append(("spool", max_attempts, events))

    def stop_agent():
        order.append(("terminate",))
        agent_stopped.set()

    _record_sends(monkeypatch, outcome=outcome)
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    _stub_process(monkeypatch, agent_input, agent_output, returncode=-15, on_terminate=stop_agent)
    _signal_during_forwarding(monkeypatch)

    exit_code = _run_stubbed(io.BytesIO(), io.BytesIO())

    assert exit_code == proxy_main.EXIT_OK
    # The end report reached the spool directly, in one attempt, ahead of the
    # prompt batch still queued, and only then was the agent stopped.
    assert [entry[:2] for entry in order] == [("spool", 1), ("terminate",), ("spool", None)]
    assert [event.payload for event in order[0][2]] == [
        {END_REASON_KEY: END_REASON_SIGNAL_TERMINATED, "session_id": "chat-1"}
    ]


def test_a_termination_signal_reports_the_chat_end_only_once(monkeypatch):
    calls = _record_sends(monkeypatch)
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    _stub_process(monkeypatch, agent_input, agent_output, returncode=-15)
    _signal_during_forwarding(monkeypatch)

    exit_code = _run_stubbed(io.BytesIO(), io.BytesIO())

    assert exit_code == proxy_main.EXIT_OK
    ended = _ended([event for _, events in calls for event in events])
    # Delivered directly; the shutdown flush did not queue it a second time.
    carrying_the_end = [
        max_attempts
        for max_attempts, events in calls
        if any(event.event_id == ended.event_id for event in events)
    ]
    assert carrying_the_end == [1]


@pytest.mark.parametrize("failure", ["unacknowledged", "raises"])
def test_a_failed_direct_end_report_is_queued_instead(monkeypatch, failure):
    def outcome(events, max_attempts):
        if max_attempts == 1:
            if failure == "raises":
                raise OSError("spool endpoint unavailable")
            return SpoolSendResult(sent=0, dropped=len(events), error="spool unavailable")
        return None

    calls = _record_sends(monkeypatch, outcome=outcome)
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    stub = _stub_process(monkeypatch, agent_input, agent_output, returncode=-15)
    _signal_during_forwarding(monkeypatch)

    exit_code = _run_stubbed(io.BytesIO(), io.BytesIO())

    assert exit_code == proxy_main.EXIT_OK
    assert stub["terminate"] == 1
    direct = [events for max_attempts, events in calls if max_attempts == 1]
    queued = [event for max_attempts, events in calls if max_attempts is None for event in events]
    ended = _ended(queued)
    assert ended.payload == {END_REASON_KEY: END_REASON_SIGNAL_TERMINATED, "session_id": "chat-1"}
    # The very event that failed to go out directly is queued, once.
    assert [[event.event_id for event in events] for events in direct] == [[ended.event_id]]


def test_a_termination_signal_without_a_spool_still_stops_the_agent(monkeypatch):
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    stub = _stub_process(monkeypatch, agent_input, agent_output, returncode=-15)
    _signal_during_forwarding(monkeypatch)
    fixture = fixture_path("echo_agent.py")

    exit_code = proxy_main.run_proxy(
        agent_cmd=[str(fixture)],
        agent_digest=sha256_digest(fixture),
        host_read=io.BytesIO(),
        host_write=io.BytesIO(),
        diagnostics=lambda _message: None,
    )

    assert exit_code == proxy_main.EXIT_OK
    assert stub["terminate"] == 1


def _spool_events(path: Path) -> list[dict]:
    events: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            events.extend(json.loads(line)["events"])
    return events


def _readline(stream, timeout: float = 15.0) -> bytes:
    lines: queue.Queue = queue.Queue()
    threading.Thread(target=lambda: lines.put(stream.readline()), daemon=True).start()
    return lines.get(timeout=timeout)


def _launch_proxy(
    spool: Path, agent_fixture: str, stderr=subprocess.PIPE, endpoint: str | None = None
) -> subprocess.Popen:
    """Start the proxy CLI around a Python fixture agent, with a file spool
    unless [endpoint] names another."""
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(COMPONENT_DIR)
    return subprocess.Popen(
        [
            sys.executable,
            "-m",
            "telemetry_acp_proxy.main",
            "--agent-digest",
            python_digest(),
            "--spool-endpoint",
            endpoint or f"file://{spool}",
            "--capability",
            "cap-1",
            "--agent-cmd",
            sys.executable,
            str(fixture_path(agent_fixture)),
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=stderr,
        cwd=str(COMPONENT_DIR),
        env=environment,
    )


def _spool_holds_an_end(path: Path) -> bool:
    """Whether the file spool already holds a complete chat end event."""
    try:
        return any(END_REASON_KEY in event["payload"] for event in _spool_events(path))
    except (OSError, ValueError):
        return False  # not written yet, or a line still being written


@pytest.mark.skipif(os.name != "posix", reason="SIGTERM delivery to a handler is POSIX-only")
def test_sigterm_to_the_proxy_process_reports_signal_terminated(tmp_path):
    spool = tmp_path / "spool.jsonl"
    proxy = _launch_proxy(spool, "echo_agent.py")
    try:
        frame = encode_message(_prompt(3, "chat-sig"))
        proxy.stdin.write(frame)
        proxy.stdin.flush()
        assert _readline(proxy.stdout) == frame  # forwarding is under way
        proxy.send_signal(signal.SIGTERM)
        proxy.wait(timeout=30)
    finally:
        if proxy.poll() is None:
            proxy.kill()
            proxy.wait(timeout=10)
        for stream in (proxy.stdin, proxy.stdout, proxy.stderr):
            stream.close()

    assert proxy.returncode == proxy_main.EXIT_OK
    events = _spool_events(spool)
    assert [event["payload"] for event in events if END_REASON_KEY in event["payload"]] == [
        {END_REASON_KEY: END_REASON_SIGNAL_TERMINATED, "session_id": "chat-sig"}
    ]
    assert not any(event["event_type"] == "system.agent.crashed" for event in events)


@pytest.mark.skipif(os.name != "posix", reason="SIGTERM/SIGKILL delivery is POSIX-only")
def test_the_chat_end_survives_a_sigkill_soon_after_sigterm(tmp_path):
    # IntelliJ kills a chat's proxy 25-38 ms after SIGTERM. This agent ignores
    # SIGTERM, so the proxy needs its whole terminate timeout to stop it: the
    # end event must reach the spool while the agent is still being stopped.
    spool = tmp_path / "spool.jsonl"
    log = tmp_path / "proxy.stderr"
    with log.open("wb") as stderr:
        proxy = _launch_proxy(spool, "slow_stop_agent.py", stderr=stderr)
    try:
        frame = encode_message(_prompt(3, "chat-kill"))
        proxy.stdin.write(frame)
        proxy.stdin.flush()
        assert _readline(proxy.stdout) == frame  # forwarding is under way
        proxy.send_signal(signal.SIGTERM)
        deadline = time.monotonic() + 0.8 * DEFAULT_TERMINATE_TIMEOUT_SECONDS
        while time.monotonic() < deadline and not _spool_holds_an_end(spool):
            time.sleep(0.01)
        proxy.kill()  # as the host does, long before the agent has stopped
        proxy.wait(timeout=30)
    finally:
        if proxy.poll() is None:
            proxy.kill()
            proxy.wait(timeout=10)
        for stream in (proxy.stdin, proxy.stdout):
            stream.close()

    diagnostics = log.read_text(encoding="utf-8", errors="replace")
    # Killed, not exited: the proxy was still stopping the agent.
    assert proxy.returncode == -signal.SIGKILL, diagnostics
    events = _spool_events(spool)
    assert [event["payload"] for event in events if END_REASON_KEY in event["payload"]] == [
        {END_REASON_KEY: END_REASON_SIGNAL_TERMINATED, "session_id": "chat-kill"}
    ], diagnostics


@pytest.mark.skipif(os.name != "posix", reason="SIGKILL delivery is POSIX-only")
def test_the_chat_end_survives_a_sigkill_soon_after_the_host_closes_stdin(tmp_path):
    # IntelliJ 2026.2 deletes a chat by closing the agent's stdin and killing the
    # process ~70 ms later; its SIGTERM may not reach the proxy at all (the group
    # signal fails). This agent saves its state before exiting, so the end must
    # be reported as soon as stdin closes, not once the agent has exited.
    spool = tmp_path / "spool.jsonl"
    log = tmp_path / "proxy.stderr"
    with log.open("wb") as stderr:
        proxy = _launch_proxy(spool, "slow_exit_agent.py", stderr=stderr)
    try:
        frame = encode_message(_prompt(3, "chat-close"))
        proxy.stdin.write(frame)
        proxy.stdin.flush()
        assert _readline(proxy.stdout) == frame  # forwarding is under way
        proxy.stdin.close()
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline and not _spool_holds_an_end(spool):
            time.sleep(0.01)
        proxy.kill()  # as the host does, long before the agent has exited
        proxy.wait(timeout=30)
    finally:
        if proxy.poll() is None:
            proxy.kill()
            proxy.wait(timeout=10)
        proxy.stdout.close()

    diagnostics = log.read_text(encoding="utf-8", errors="replace")
    # Killed, not exited: the proxy was still waiting for the agent.
    assert proxy.returncode == -signal.SIGKILL, diagnostics
    events = _spool_events(spool)
    assert [event["payload"] for event in events if END_REASON_KEY in event["payload"]] == [
        {END_REASON_KEY: END_REASON_HOST_CLOSED, "session_id": "chat-close"}
    ], diagnostics


@pytest.mark.skipif(os.name != "posix", reason="SIGTERM delivery to a handler is POSIX-only")
def test_a_sigterm_while_the_host_closed_report_is_in_flight_waits_for_it(tmp_path):
    # IntelliJ quits by closing the agent's stdin and sending SIGTERM 10-40 ms
    # later, often before its busy spool has acknowledged the end report. The
    # signal must wait for the report instead of aborting it (the report then
    # only survives as a queued retry that the host's SIGKILL cuts short).
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    end_posts: list[str] = []

    class SlowSpool(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            events = body.get("events", [])
            end_posts.extend(e["event_id"] for e in events if END_REASON_KEY in e["payload"])
            time.sleep(0.3)  # a busy IDE: stored at once, acknowledged late
            reply = json.dumps(
                {"accepted": [e["event_id"] for e in events], "duplicate": [], "rejected": []}
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(reply)))
            self.end_headers()
            self.wfile.write(reply)

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), SlowSpool)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log = tmp_path / "proxy.stderr"
    try:
        with log.open("wb") as stderr:
            proxy = _launch_proxy(
                tmp_path / "unused.jsonl",
                "slow_exit_agent.py",
                stderr=stderr,
                endpoint=f"http://127.0.0.1:{server.server_address[1]}/spool",
            )
        try:
            frame = encode_message(_prompt(3, "chat-quit"))
            proxy.stdin.write(frame)
            proxy.stdin.flush()
            assert _readline(proxy.stdout) == frame
            proxy.stdin.close()
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline and not end_posts:
                time.sleep(0.005)
            assert end_posts, "the host's stdin close was never reported"
            proxy.send_signal(signal.SIGTERM)  # while the report awaits its answer
            proxy.wait(timeout=30)
        finally:
            if proxy.poll() is None:
                proxy.kill()
                proxy.wait(timeout=10)
            proxy.stdout.close()
    finally:
        server.shutdown()
        server.server_close()

    diagnostics = log.read_text(encoding="utf-8", errors="replace")
    assert "direct spool delivery failed" not in diagnostics, diagnostics
    # Sent once and acknowledged, never resent from the queue.
    assert len(end_posts) == 1, diagnostics
    # The held signal was honoured afterwards, not dropped.
    assert "received a termination signal" in diagnostics, diagnostics
    assert proxy.returncode == proxy_main.EXIT_OK, diagnostics


@pytest.mark.skipif(os.name != "posix", reason="raises a real SIGTERM in this process")
def test_main_turns_a_signal_into_a_clean_stop_and_restores_handlers(monkeypatch):
    before = (signal.getsignal(signal.SIGTERM), signal.getsignal(signal.SIGINT))
    seen: dict = {}

    def fake_run_proxy(**_kwargs):
        seen["handlers"] = (signal.getsignal(signal.SIGTERM), signal.getsignal(signal.SIGINT))
        signal.raise_signal(signal.SIGTERM)
        return 99  # never reached: the handler raises ProxyTerminated

    monkeypatch.setattr(proxy_main, "run_proxy", fake_run_proxy)

    code = proxy_main.main(["--agent-digest", "sha256:" + "0" * 64, "--agent-cmd", "agent"])

    assert code == proxy_main.EXIT_OK
    assert seen["handlers"][0] is seen["handlers"][1]
    assert seen["handlers"][0] not in before
    assert (signal.getsignal(signal.SIGTERM), signal.getsignal(signal.SIGINT)) == before


# ---------------------------------------------------------------------------
# Elicitation round-trips
# ---------------------------------------------------------------------------


def test_elicitation_frames_are_forwarded_byte_for_byte_without_values(monkeypatch):
    sink = _capture_events(monkeypatch)
    host_input, host_output = _MemoryChannel(), _MemoryChannel()
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    _stub_process(monkeypatch, agent_input, agent_output)
    ask = encode_message(
        _request(
            12,
            "elicitation/create",
            {
                "sessionId": "chat-1",
                "mode": "form",
                "message": "CANARY-ELICITATION-MESSAGE",
                "requestedSchema": {
                    "type": "object",
                    "properties": {"instructions": {"type": "string"}},
                    "required": ["instructions"],
                },
            },
        )
    )
    answer = encode_message(
        _result(12, {"action": "accept", "content": {"instructions": "CANARY-ELICITATION-ANSWER"}})
    )
    result: dict = {}

    # Even a study that captures content records no elicitation values.
    policy = PrivacyPolicy(content_allowed=True, consent_active=True)
    thread = threading.Thread(
        target=lambda: result.update(exit=_run_stubbed(host_input, host_output, policy=policy)),
        daemon=True,
    )
    thread.start()
    try:
        agent_output.write(ask)
        assert host_output.read_line() == ask
        host_input.write(answer)
        assert agent_input.read_line() == answer
        agent_output.close()
        assert _wait_closed(host_output)
    finally:
        host_input.close()
        thread.join(timeout=10.0)

    assert result["exit"] == proxy_main.EXIT_OK
    asked = [event for event in sink if event.payload.get("unknown_method") == "elicitation/create"]
    assert len(asked) == 1
    assert asked[0].payload["param_names"] == ["message", "mode", "requestedSchema", "sessionId"]
    answered = [
        event.payload
        for event in sink
        if event.payload.get("source_event_id") == "12" and "unknown_method" not in event.payload
    ]
    assert answered == [{"source_event_id": "12", "session_id": "chat-1"}]
    serialized = "\n".join(event.model_dump_json() for event in sink)
    assert "CANARY" not in serialized
