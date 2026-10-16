"""The stale-session guard: a chat outliving its research session is refused."""

from __future__ import annotations

import json

from telemetry_acp_proxy.framing import FrameReader
from telemetry_acp_proxy.observe import AcpDirection
from telemetry_acp_proxy.stale_session_guard import STALE_SESSION_MESSAGE, StaleSessionGuard

HOST = AcpDirection.HOST_TO_AGENT


def _chunk(*messages):
    raw = b"".join(json.dumps(message).encode() + b"\n" for message in messages)
    return raw, FrameReader().feed(raw)


def _guard(path, token="live-token", **kwargs):
    return StaleSessionGuard(str(path), token, sleep=lambda _seconds: None, **kwargs)


def test_live_session_forwards_and_agent_traffic_is_never_touched(tmp_path):
    path = tmp_path / "capability"
    path.write_text("live-token", encoding="utf-8")
    guard = _guard(path)
    raw, frames = _chunk({"jsonrpc": "2.0", "id": 1, "method": "session/prompt"})

    assert guard.intercept(HOST, raw, frames, 0) is None
    path.unlink()
    assert guard.intercept(AcpDirection.AGENT_TO_HOST, raw, frames, 0) is None
    assert guard.stale is False


def test_stale_chat_answers_every_request_and_withholds_everything_else(tmp_path):
    stale_events = []
    guard = _guard(tmp_path / "missing", on_stale=lambda: stale_events.append(1))
    raw, frames = _chunk(
        {"jsonrpc": "2.0", "id": 4, "method": "session/prompt"},
        {"jsonrpc": "2.0", "method": "session/cancel"},  # a notification: no answer
        {"jsonrpc": "2.0", "id": "p-1", "result": {"outcome": "selected"}},  # a reply to the agent
    )

    replacement = guard.intercept(HOST, raw, frames, 0)
    answers = [json.loads(line) for line in replacement.decode().splitlines()]
    assert [answer["id"] for answer in answers] == [4]
    assert answers[0]["error"]["message"] == STALE_SESSION_MESSAGE
    # A later partial chunk (no complete frame) is withheld too, and the chat stays stale.
    assert guard.intercept(HOST, b'{"jsonrpc":"2.0",', [], 17) == b""
    assert guard.stale is True and guard.refused == 1 and stale_events == [1]


def test_an_empty_read_is_retried_before_the_session_counts_as_ended(tmp_path):
    path = tmp_path / "capability"
    path.write_text("", encoding="utf-8")
    reads = []

    def rewrite_during_the_retry(_seconds):
        reads.append(1)
        path.write_text("live-token", encoding="utf-8")

    guard = StaleSessionGuard(str(path), "live-token", sleep=rewrite_during_the_retry)
    raw, frames = _chunk({"jsonrpc": "2.0", "id": 1, "method": "session/prompt"})

    assert guard.intercept(HOST, raw, frames, 0) is None
    assert reads == [1] and guard.stale is False


def test_a_file_that_stays_empty_ends_the_session(tmp_path):
    path = tmp_path / "capability"
    path.write_text("", encoding="utf-8")
    raw, frames = _chunk({"jsonrpc": "2.0", "id": 1, "method": "session/prompt"})
    guard = _guard(path)

    replacement = guard.intercept(HOST, raw, frames, 0)

    assert guard.stale is True
    assert replacement is not None
    [answer] = [json.loads(line) for line in replacement.decode().splitlines()]
    assert answer["id"] == 1 and answer["error"]["message"] == STALE_SESSION_MESSAGE
