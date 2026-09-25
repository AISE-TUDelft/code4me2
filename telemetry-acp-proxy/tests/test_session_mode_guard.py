"""``session/set_mode`` from the host is refused: the study fixes the approval mode.

A participant could switch a BYOA agent (Goose) from ``approve`` to ``auto`` in
AI Chat and write files without any permission prompt under a per-step study
policy. The proxy now answers the request with a JSON-RPC error instead of
forwarding it; ``--allow-session-mode-changes`` restores forwarding.
"""

from __future__ import annotations

import json
import threading

from test_initialize_replay import _MemoryChannel  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.forwarder import AcpForwarder
from telemetry_acp_proxy.framing import CONTENT_LENGTH, NEWLINE, FrameReader, encode_message
from telemetry_acp_proxy.observe import AcpDirection, Observer
from telemetry_acp_proxy.session_mode_guard import (
    SET_MODE_METHOD,
    SET_MODE_REFUSED_CODE,
    SessionModeGuard,
)


def _frames(chunk: bytes):
    return FrameReader().feed(chunk)


def _set_mode(message_id, *, framing: str = NEWLINE, mode: str = "auto") -> bytes:
    return encode_message(
        {"jsonrpc": "2.0", "id": message_id, "method": SET_MODE_METHOD,
         "params": {"sessionId": "s-1", "modeId": mode}},
        framing=framing,
    )


def test_a_set_mode_request_is_answered_with_an_error_and_never_forwarded():
    refusals: list[int] = []
    guard = SessionModeGuard(on_refusal=lambda: refusals.append(1))
    request = _set_mode(7)

    replacement = guard.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0)

    assert replacement is not None
    answer = json.loads(replacement.decode("utf-8"))
    assert answer["id"] == 7
    assert answer["error"]["code"] == SET_MODE_REFUSED_CODE
    assert "fixed by the research study" in answer["error"]["message"]
    assert refusals == [1] and guard.refused == 1


def test_content_length_framing_is_answered_in_kind():
    guard = SessionModeGuard()
    request = _set_mode("abc", framing=CONTENT_LENGTH)

    replacement = guard.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0)

    assert replacement is not None and replacement.startswith(b"Content-Length:")
    assert json.loads(FrameReader().feed(replacement)[0].raw.split(b"\r\n\r\n", 1)[1])["id"] == "abc"


def test_other_methods_notifications_and_agent_frames_are_forwarded():
    guard = SessionModeGuard()
    prompt = encode_message({"jsonrpc": "2.0", "id": 1, "method": "session/prompt", "params": {}})
    assert guard.intercept(AcpDirection.HOST_TO_AGENT, prompt, _frames(prompt), 0) is None
    notification = encode_message({"jsonrpc": "2.0", "method": SET_MODE_METHOD, "params": {}})
    assert guard.intercept(AcpDirection.HOST_TO_AGENT, notification, _frames(notification), 0) is None
    response = encode_message({"jsonrpc": "2.0", "id": 3, "result": {}})
    assert guard.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0) is None
    assert guard.refused == 0


def test_a_set_mode_split_across_chunks_is_never_replaced():
    """Bytes already forwarded cannot be taken back: forward the remainder too."""
    guard = SessionModeGuard()
    request = _set_mode(9)
    head, tail = request[:10], request[10:]
    reader = FrameReader()
    assert guard.intercept(AcpDirection.HOST_TO_AGENT, head, reader.feed(head), reader.buffered_bytes) is None
    frames = reader.feed(tail)
    assert len(frames) == 1
    assert guard.intercept(AcpDirection.HOST_TO_AGENT, tail, frames, reader.buffered_bytes) is None
    # Accounting is back on a boundary: the next complete request is refused.
    again = _set_mode(10)
    assert guard.intercept(AcpDirection.HOST_TO_AGENT, again, _frames(again), 0) is not None


def _fake_agent(agent_input: _MemoryChannel, agent_output: _MemoryChannel, received: list[dict]) -> None:
    buffer = b""
    try:
        while True:
            chunk = agent_input.read1(64 * 1024)
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                message = json.loads(line.decode("utf-8"))
                received.append(message)
                if "id" in message:
                    agent_output.write(encode_message({"jsonrpc": "2.0", "id": message["id"], "result": {}}))
    finally:
        agent_output.close()


def test_forwarder_refuses_set_mode_without_reaching_the_agent():
    guard = SessionModeGuard()
    forwarder = AcpForwarder(observer=Observer(), intercept=guard.intercept)
    host_input, host_output = _MemoryChannel(), _MemoryChannel()
    agent_input, agent_output = _MemoryChannel(), _MemoryChannel()
    received: list[dict] = []
    threading.Thread(target=_fake_agent, args=(agent_input, agent_output, received), daemon=True).start()
    forwarder_thread = threading.Thread(
        target=lambda: forwarder.run(host_input, host_output, agent_output, agent_input, grace_seconds=2.0),
        daemon=True,
    )
    forwarder_thread.start()
    try:
        host_input.write(encode_message({"jsonrpc": "2.0", "id": 1, "method": "session/prompt", "params": {}}))
        assert json.loads(host_output.read_line()) == {"jsonrpc": "2.0", "id": 1, "result": {}}
        host_input.write(_set_mode(2))
        refused = json.loads(host_output.read_line())
        assert refused["id"] == 2 and refused["error"]["code"] == SET_MODE_REFUSED_CODE
        host_input.write(encode_message({"jsonrpc": "2.0", "id": 3, "method": "session/prompt", "params": {}}))
        assert json.loads(host_output.read_line()) == {"jsonrpc": "2.0", "id": 3, "result": {}}
    finally:
        host_input.close()
        forwarder_thread.join(timeout=5.0)
    assert [message["method"] for message in received] == ["session/prompt", "session/prompt"]


def test_parser_accepts_the_allow_flag_and_defaults_to_refusing():
    parser = proxy_main.build_parser()
    default = parser.parse_args(["--agent-digest", "sha256:" + "0" * 64, "--agent-cmd", "agent"])
    assert default.allow_session_mode_changes is False
    allowed = parser.parse_args(
        ["--agent-digest", "sha256:" + "0" * 64, "--allow-session-mode-changes", "--agent-cmd", "agent"]
    )
    assert allowed.allow_session_mode_changes is True


def test_composed_interceptors_all_see_every_chunk_and_the_first_answer_wins():
    seen: list[str] = []

    def first(direction, chunk, frames, partial):
        seen.append("first")
        return None

    def second(direction, chunk, frames, partial):
        seen.append("second")
        return b"answer"

    def third(direction, chunk, frames, partial):
        seen.append("third")
        return b"late"

    composed = proxy_main._compose_interceptors([first, second, third])
    assert composed(AcpDirection.HOST_TO_AGENT, b"", [], 0) == b"answer"
    assert seen == ["first", "second", "third"]
