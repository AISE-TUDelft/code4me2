"""Opt-in idempotent ``initialize`` replay; the default path stays byte-preserving.

JetBrains AI Assistant resubmits a failed prompt by creating a new session on an
already-initialized ACP proxy process; its session-creation path sends
``initialize`` a second time on the same connection and a strict agent answers
JSON-RPC ``-32603`` ("Already initialized"), wedging the chat. With
``--compat-idempotent-initialize`` the proxy answers the duplicate from the
cached handshake result instead of forwarding it.
"""

from __future__ import annotations

import collections
import json
import threading
import time

from conftest import fixture_path, sha256_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.framing import (
    CONTENT_LENGTH,
    NEWLINE,
    FrameReader,
    encode_message,
)
from telemetry_acp_proxy.forwarder import AcpForwarder
from telemetry_acp_proxy.initialize_replay import INITIALIZE_METHOD, InitializeReplay
from telemetry_acp_proxy.lifecycle import ProxyState
from telemetry_acp_proxy.observe import AcpDirection, Observer

DIGEST = "sha256:" + "0" * 64
RESULT = {"protocolVersion": 1, "agentInfo": {"name": "code4me-test-agent"}}


def _frames(chunk: bytes):
    return FrameReader().feed(chunk)


def _initialize(message_id, *, framing: str = NEWLINE, params=None) -> bytes:
    message = {"jsonrpc": "2.0", "id": message_id, "method": INITIALIZE_METHOD}
    if params is not None:
        message["params"] = params
    return encode_message(message, framing=framing)


def _initialize_response(message_id, result=RESULT, *, framing: str = NEWLINE) -> bytes:
    return encode_message(
        {"jsonrpc": "2.0", "id": message_id, "result": result}, framing=framing
    )


# ---------------------------------------------------------------------------
# InitializeReplay unit contract
# ---------------------------------------------------------------------------


def test_first_initialize_is_recorded_pending_and_forwarded():
    replay = InitializeReplay()
    request = _initialize(1, params={})

    assert replay.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0) is None


def test_agent_result_is_cached_and_a_duplicate_gets_it_with_the_new_id():
    replay = InitializeReplay()
    request = _initialize(1)
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0) is None

    response = _initialize_response(1)
    assert replay.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0) is None

    duplicate = _initialize(7)
    replacement = replay.intercept(
        AcpDirection.HOST_TO_AGENT, duplicate, _frames(duplicate), 0
    )

    assert replacement is not None
    assert json.loads(replacement) == {"jsonrpc": "2.0", "id": 7, "result": RESULT}
    # The synthesized frame stays on the wire framing of the request it replaces.
    assert replacement == _initialize_response(7)


def test_replay_preserves_content_length_framing():
    replay = InitializeReplay()
    request = _initialize(1, framing=CONTENT_LENGTH)
    replay.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0)
    response = _initialize_response(1, framing=CONTENT_LENGTH)
    replay.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0)

    duplicate = _initialize(2, framing=CONTENT_LENGTH)
    replacement = replay.intercept(
        AcpDirection.HOST_TO_AGENT, duplicate, _frames(duplicate), 0
    )

    assert replacement is not None
    assert replacement == _initialize_response(2, framing=CONTENT_LENGTH)
    decoded = FrameReader().feed(replacement)
    assert len(decoded) == 1
    assert decoded[0].framing == CONTENT_LENGTH
    assert json.loads(decoded[0].body) == {"jsonrpc": "2.0", "id": 2, "result": RESULT}


def test_a_duplicate_while_the_first_response_is_pending_is_still_forwarded():
    replay = InitializeReplay()
    first = _initialize(1)
    second = _initialize(2)

    assert replay.intercept(AcpDirection.HOST_TO_AGENT, first, _frames(first), 0) is None
    # The agent has not answered yet: byte-preserving forwarding wins.
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, second, _frames(second), 0) is None


def test_the_latest_pending_response_wins():
    replay = InitializeReplay()
    first = _initialize(1)
    second = _initialize(2)
    replay.intercept(AcpDirection.HOST_TO_AGENT, first, _frames(first), 0)
    replay.intercept(AcpDirection.HOST_TO_AGENT, second, _frames(second), 0)

    response_first = _initialize_response(1, {"round": 1})
    response_second = _initialize_response(2, {"round": 2})
    replay.intercept(AcpDirection.AGENT_TO_HOST, response_first, _frames(response_first), 0)
    replay.intercept(AcpDirection.AGENT_TO_HOST, response_second, _frames(response_second), 0)

    duplicate = _initialize(3)
    replacement = replay.intercept(
        AcpDirection.HOST_TO_AGENT, duplicate, _frames(duplicate), 0
    )

    assert replacement is not None
    assert json.loads(replacement)["result"] == {"round": 2}


def test_an_error_response_never_becomes_a_cached_handshake():
    replay = InitializeReplay()
    request = _initialize(1)
    replay.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0)

    error = encode_message(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "error": {"code": -32603, "message": "Already initialized"},
        }
    )
    assert replay.intercept(AcpDirection.AGENT_TO_HOST, error, _frames(error), 0) is None

    duplicate = _initialize(2)
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, duplicate, _frames(duplicate), 0) is None


def test_multi_frame_partial_tail_and_non_initialize_chunks_are_untouched():
    replay = InitializeReplay()
    request = _initialize(1)
    replay.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0)
    response = _initialize_response(1)
    replay.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0)

    multi = _initialize(2) + _initialize(3)
    assert len(_frames(multi)) == 2
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, multi, _frames(multi), 0) is None

    partial = b'{"jsonrpc":"2.0","id":4,"method":"init'
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, partial, [], len(partial)) is None

    other_method = encode_message(
        {"jsonrpc": "2.0", "id": 5, "method": "session/new", "params": {}}
    )
    assert (
        replay.intercept(
            AcpDirection.HOST_TO_AGENT, other_method, _frames(other_method), 0
        )
        is None
    )

    notification = encode_message({"jsonrpc": "2.0", "method": INITIALIZE_METHOD})
    assert (
        replay.intercept(
            AcpDirection.HOST_TO_AGENT, notification, _frames(notification), 0
        )
        is None
    )

    malformed = b"not json\n"
    assert (
        replay.intercept(AcpDirection.HOST_TO_AGENT, malformed, _frames(malformed), 0)
        is None
    )


def test_on_replay_fires_only_when_a_duplicate_is_answered():
    replayed: list[int] = []
    replay = InitializeReplay(on_replay=lambda: replayed.append(1))
    request = _initialize(1)
    replay.intercept(AcpDirection.HOST_TO_AGENT, request, _frames(request), 0)

    duplicate = _initialize(2)
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, duplicate, _frames(duplicate), 0) is None
    assert replayed == [], "an unanswered duplicate is not a replay"

    response = _initialize_response(1)
    replay.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0)
    assert replay.intercept(AcpDirection.HOST_TO_AGENT, duplicate, _frames(duplicate), 0) is not None
    assert replayed == [1]


# ---------------------------------------------------------------------------
# CLI contract
# ---------------------------------------------------------------------------


def _parse(*extra: str):
    return proxy_main.build_parser().parse_args(
        [*extra, "--agent-digest", DIGEST, "--agent-cmd", "agent"]
    )


def test_compat_flag_parses_and_defaults_off():
    assert _parse().compat_idempotent_initialize is False
    assert _parse("--compat-idempotent-initialize").compat_idempotent_initialize is True


def test_compat_flag_reaches_run_proxy(monkeypatch):
    captured: dict = {}
    monkeypatch.setattr(
        proxy_main, "run_proxy", lambda **kwargs: captured.update(kwargs) or 0
    )

    code = proxy_main.main(
        ["--agent-digest", DIGEST, "--compat-idempotent-initialize", "--agent-cmd", "agent"]
    )

    assert code == 0
    assert captured["compat_idempotent_initialize"] is True


def test_compat_flag_is_off_by_default_in_run_proxy(monkeypatch):
    captured: dict = {}
    monkeypatch.setattr(
        proxy_main, "run_proxy", lambda **kwargs: captured.update(kwargs) or 0
    )

    code = proxy_main.main(["--agent-digest", DIGEST, "--agent-cmd", "agent"])

    assert code == 0
    assert captured["compat_idempotent_initialize"] is False


# ---------------------------------------------------------------------------
# Forwarder-level and run_proxy-level behavior
# ---------------------------------------------------------------------------


class _MemoryChannel:
    """Blocking in-memory byte channel used as an ACP host/agent stream."""

    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._chunks: collections.deque[bytes] = collections.deque()
        self._closed = False
        self.written = bytearray()

    # -- producer side (a pump or the fake agent) --------------------------
    def write(self, chunk: bytes) -> int:
        with self._condition:
            if self._closed:
                raise ValueError("write to a closed channel")
            self._chunks.append(bytes(chunk))
            self.written.extend(chunk)
            self._condition.notify_all()
        return len(chunk)

    def flush(self) -> None:
        return None

    def close(self) -> None:
        with self._condition:
            self._closed = True
            self._condition.notify_all()

    # -- consumer side (the pumps use read1) -------------------------------
    def read1(self, size: int) -> bytes:
        with self._condition:
            while not self._chunks and not self._closed:
                self._condition.wait(timeout=0.05)
            if not self._chunks:
                return b""
            chunk = self._chunks.popleft()
            if len(chunk) > size:
                self._chunks.appendleft(chunk[size:])
                return chunk[:size]
            return chunk

    # -- consumer side (the test reads one newline frame) ------------------
    def read_line(self, timeout: float = 5.0) -> bytes:
        deadline = time.monotonic() + timeout
        buffer = b""
        while b"\n" not in buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError(f"timed out waiting for a frame; buffered={buffer!r}")
            with self._condition:
                if not self._chunks and not self._closed:
                    self._condition.wait(timeout=min(remaining, 0.05))
                if self._chunks:
                    buffer += self._chunks.popleft()
        line, _, remainder = buffer.partition(b"\n")
        if remainder:
            with self._condition:
                self._chunks.appendleft(remainder)
        return line + b"\n"


def _fake_agent(
    agent_input: _MemoryChannel,
    agent_output: _MemoryChannel,
    received: list[bytes],
    duplicates: list[bytes],
    errors: list[BaseException],
) -> None:
    """Answer the first ``initialize``; a second one is the failure being tested."""
    buffer = b""
    initialize_count = 0
    try:
        while True:
            chunk = agent_input.read1(64 * 1024)
            if not chunk:
                break
            received.append(chunk)
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                message = json.loads(line.decode("utf-8"))
                if message.get("method") != INITIALIZE_METHOD:
                    continue
                initialize_count += 1
                if initialize_count > 1:
                    duplicates.append(line)
                    response = {
                        "jsonrpc": "2.0",
                        "id": message.get("id"),
                        "error": {"code": -32603, "message": "Already initialized"},
                    }
                else:
                    response = {"jsonrpc": "2.0", "id": message.get("id"), "result": RESULT}
                agent_output.write(encode_message(response))
    except BaseException as error:  # pragma: no cover - surfaced by the test
        errors.append(error)
    finally:
        agent_output.close()


def test_forwarder_answers_the_duplicate_without_reaching_the_agent():
    observer = Observer()
    replay = InitializeReplay()
    forwarder = AcpForwarder(observer=observer, intercept=replay.intercept)

    host_input = _MemoryChannel()
    host_output = _MemoryChannel()
    agent_input = _MemoryChannel()
    agent_output = _MemoryChannel()
    received: list[bytes] = []
    duplicates: list[bytes] = []
    errors: list[BaseException] = []

    agent = threading.Thread(
        target=_fake_agent,
        args=(agent_input, agent_output, received, duplicates, errors),
        name="fake-agent",
        daemon=True,
    )
    agent.start()
    forwarder_thread = threading.Thread(
        target=lambda: forwarder.run(
            host_input, host_output, agent_output, agent_input, grace_seconds=2.0
        ),
        name="forwarder",
        daemon=True,
    )
    forwarder_thread.start()
    try:
        first = _initialize(1, params={})
        host_input.write(first)
        first_response = json.loads(host_output.read_line())
        assert first_response == {"jsonrpc": "2.0", "id": 1, "result": RESULT}

        duplicate = _initialize(2, params={})
        host_input.write(duplicate)
        second_response = json.loads(host_output.read_line())
        assert second_response == {"jsonrpc": "2.0", "id": 2, "result": RESULT}
    finally:
        host_input.close()
        forwarder_thread.join(timeout=10)
        agent.join(timeout=10)

    assert not forwarder_thread.is_alive(), "the forwarder did not stop"
    assert not agent.is_alive(), "the fake agent did not stop"
    assert not errors, f"the fake agent failed: {errors}"
    assert not duplicates, f"a duplicate initialize reached the agent: {duplicates!r}"
    assert b"".join(received) == first, "the agent must see exactly one initialize"
    # The synthesized response travelled the AGENT_TO_HOST telemetry path.
    agent_frames = [
        record for record in observer.records if record.direction == AcpDirection.AGENT_TO_HOST
    ]
    assert [record.jsonrpc_id for record in agent_frames] == ["1", "2"]


def test_forwarder_never_intercepts_the_remainder_of_a_split_duplicate():
    observer = Observer()
    forwarder = AcpForwarder(observer=observer, intercept=InitializeReplay().intercept)
    host_input = _MemoryChannel()
    host_output = _MemoryChannel()
    agent_input = _MemoryChannel()
    agent_output = _MemoryChannel()
    received: list[bytes] = []
    duplicates: list[bytes] = []
    errors: list[BaseException] = []

    agent = threading.Thread(
        target=_fake_agent,
        args=(agent_input, agent_output, received, duplicates, errors),
        daemon=True,
    )
    agent.start()
    forwarder_thread = threading.Thread(
        target=lambda: forwarder.run(
            host_input, host_output, agent_output, agent_input, grace_seconds=2.0
        ),
        daemon=True,
    )
    forwarder_thread.start()
    try:
        first = _initialize(1)
        host_input.write(first)
        assert json.loads(host_output.read_line())["result"] == RESULT

        split = _initialize(2)
        cut = len(split) // 2
        host_input.write(split[:cut])
        host_input.write(split[cut:])
        # The split request has already begun reaching the agent, so its
        # remainder must reach the agent too, even though replay is available.
        response = json.loads(host_output.read_line())
        assert response["id"] == 2
        assert response["error"]["code"] == -32603

        complete = _initialize(3)
        host_input.write(complete)
        assert json.loads(host_output.read_line()) == {
            "jsonrpc": "2.0", "id": 3, "result": RESULT
        }
    finally:
        host_input.close()
        forwarder_thread.join(timeout=10)
        agent.join(timeout=10)

    assert not forwarder_thread.is_alive()
    assert not agent.is_alive()
    assert not errors
    assert len(duplicates) == 1
    assert b"".join(received) == first + split


def test_forwarder_preserves_split_content_length_header_and_body():
    replay = InitializeReplay()
    first = _initialize(1, framing=CONTENT_LENGTH)
    replay.intercept(AcpDirection.HOST_TO_AGENT, first, _frames(first), 0)
    response = _initialize_response(1, framing=CONTENT_LENGTH)
    replay.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0)

    duplicate = _initialize(2, framing=CONTENT_LENGTH)
    header_end = duplicate.index(b"\r\n\r\n") + 4
    host_input = _MemoryChannel()
    host_output = _MemoryChannel()
    agent_input = _MemoryChannel()
    agent_output = _MemoryChannel()
    host_input.write(duplicate[:header_end])
    host_input.write(duplicate[header_end:])
    host_input.close()
    agent_output.close()

    AcpForwarder(observer=Observer(), intercept=replay.intercept).run(
        host_input, host_output, agent_output, agent_input, grace_seconds=0.1
    )

    assert bytes(agent_input.written) == duplicate
    assert bytes(host_output.written) == b""


def test_forwarder_preserves_complete_duplicate_with_header_only_tail():
    replay = InitializeReplay()
    first = _initialize(1)
    replay.intercept(AcpDirection.HOST_TO_AGENT, first, _frames(first), 0)
    response = _initialize_response(1)
    replay.intercept(AcpDirection.AGENT_TO_HOST, response, _frames(response), 0)

    duplicate = _initialize(2)
    next_request = encode_message(
        {"jsonrpc": "2.0", "id": 3, "method": "session/new"},
        framing=CONTENT_LENGTH,
    )
    header_end = next_request.index(b"\r\n\r\n") + 4
    host_input = _MemoryChannel()
    host_output = _MemoryChannel()
    agent_input = _MemoryChannel()
    agent_output = _MemoryChannel()
    host_input.write(duplicate + next_request[:header_end])
    host_input.write(next_request[header_end:])
    host_input.close()
    agent_output.close()

    AcpForwarder(observer=Observer(), intercept=replay.intercept).run(
        host_input, host_output, agent_output, agent_input, grace_seconds=0.1
    )

    assert bytes(agent_input.written) == duplicate + next_request
    assert bytes(host_output.written) == b""


class _GatedHostInput:
    """Serve the duplicate only after the host received the first response."""

    def __init__(self, first: bytes, duplicate: bytes, gate: threading.Event) -> None:
        self._first = first
        self._duplicate = duplicate
        self._gate = gate
        self._stage = 0

    def read1(self, _size: int) -> bytes:
        if self._stage == 0:
            self._stage = 1
            return self._first
        if self._stage == 1:
            assert self._gate.wait(timeout=5.0), "the first response never reached the host"
            self._stage = 2
            return self._duplicate
        return b""


class _GatedHostOutput:
    """Record host output and release the duplicate as soon as a reply arrives."""

    def __init__(self, gate: threading.Event) -> None:
        self._gate = gate
        self._lock = threading.Lock()
        self.data = bytearray()

    def write(self, chunk: bytes) -> int:
        with self._lock:
            self.data.extend(chunk)
        self._gate.set()
        return len(chunk)

    def flush(self) -> None:
        return None

    def close(self) -> None:
        return None


def test_run_proxy_with_the_flag_answers_the_duplicate_and_logs_it(monkeypatch):
    first = _initialize(1, params={})
    duplicate = _initialize(2, params={})
    gate = threading.Event()
    host_read = _GatedHostInput(first, duplicate, gate)
    host_write = _GatedHostOutput(gate)

    agent_input = _MemoryChannel()
    agent_output = _MemoryChannel()
    received: list[bytes] = []
    duplicates: list[bytes] = []
    errors: list[BaseException] = []

    class StubProcess:
        def __init__(self, command, env_overrides=None):
            self.stdin = agent_input
            self.stdout = agent_output
            self.state = ProxyState.STOPPED
            self.returncode = 0

        def start(self):
            return None

        def terminate(self):
            return None

        def wait(self, timeout=None):
            return self.returncode

    monkeypatch.setattr(proxy_main, "ProxyProcess", StubProcess)
    agent = threading.Thread(
        target=_fake_agent,
        args=(agent_input, agent_output, received, duplicates, errors),
        name="fake-agent",
        daemon=True,
    )
    agent.start()
    diagnostics: list[str] = []
    fixture = fixture_path("echo_agent.py")

    try:
        exit_code = proxy_main.run_proxy(
            agent_cmd=[str(fixture)],
            agent_digest=sha256_digest(fixture),
            compat_idempotent_initialize=True,
            host_read=host_read,
            host_write=host_write,
            diagnostics=diagnostics.append,
        )
    finally:
        agent.join(timeout=10)

    assert exit_code == proxy_main.EXIT_OK
    assert not errors, f"the fake agent failed: {errors}"
    assert not duplicates, f"a duplicate initialize reached the agent: {duplicates!r}"
    responses = [json.loads(line) for line in bytes(host_write.data).splitlines()]
    assert responses == [
        {"jsonrpc": "2.0", "id": 1, "result": RESULT},
        {"jsonrpc": "2.0", "id": 2, "result": RESULT},
    ]
    assert (
        sum("answered repeated initialize" in message for message in diagnostics) == 1
    ), diagnostics
