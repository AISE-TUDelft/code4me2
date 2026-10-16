"""Interactive ACP handshake regression: stdin stays open while waiting.

The bug this guards against: a buffered ``read(size)`` on an interactive pipe
blocks until *size* bytes or EOF. The ACP host writes one small frame
(``initialize``) and waits for the response without closing stdin, so the frame
was never forwarded and the host hung forever on "starting agent".

``communicate()`` (used by the byte-equivalence test) closes stdin after writing,
which lets a blocking read return and hid the defect. These tests drive a real
proxy subprocess with an *open* stdin and a hard deadline so a regression fails
instead of hanging the suite.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading

from conftest import COMPONENT_DIR, fixture_path, python_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy.forwarder import read_available

DEADLINE_SECONDS = 10.0


class _Read1OnlyStream:
    """A stream exposing only ``read1`` (like a buffered pipe)."""

    def __init__(self, payload: bytes) -> None:
        self._payload = payload

    def read1(self, _size: int) -> bytes:
        payload, self._payload = self._payload, b""
        return payload

    def read(self, _size: int) -> bytes:  # pragma: no cover - read1 must win
        raise AssertionError("read() must not be used when read1 is available")


class _PlainStream:
    """A raw stream with only ``read``."""

    def __init__(self, payload: bytes) -> None:
        self._payload = payload

    def read(self, _size: int) -> bytes:
        payload, self._payload = self._payload, b""
        return payload


def test_read_available_prefers_read1_over_blocking_read():
    assert (
        read_available(_Read1OnlyStream(b'{"jsonrpc":"2.0","id":1}\n'), 64 * 1024)
        == b'{"jsonrpc":"2.0","id":1}\n'
    )
    # A raw stream without read1 still falls back to read().
    assert (
        read_available(_PlainStream(b'{"jsonrpc":"2.0","id":2}\n'), 64 * 1024)
        == b'{"jsonrpc":"2.0","id":2}\n'
    )


def _proxy_argv(agent: str) -> list[str]:
    return [
        sys.executable,
        "-m",
        "telemetry_acp_proxy.main",
        "--agent-digest",
        python_digest(),
        "--agent-cmd",
        sys.executable,
        agent,
    ]


def _spawn_proxy(agent: str) -> subprocess.Popen:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(COMPONENT_DIR)
    return subprocess.Popen(
        _proxy_argv(agent),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(COMPONENT_DIR),
        env=env,
    )


def _read_line_with_deadline(stream, deadline: float) -> bytes:
    box: dict[str, bytes] = {}

    def _read() -> None:
        box["line"] = stream.readline()

    thread = threading.Thread(target=_read, daemon=True)
    thread.start()
    thread.join(deadline)
    return box.get("line", b"")


def test_proxied_initialize_round_trips_with_open_stdin():
    process = _spawn_proxy(str(fixture_path("echo_agent.py")))
    try:
        frame = b'{"jsonrpc":"2.0","id":1,"method":"initialize"}\n'
        process.stdin.write(frame)
        process.stdin.flush()

        # stdin stays OPEN: the proxy must forward and echo back promptly.
        assert _read_line_with_deadline(process.stdout, DEADLINE_SECONDS) == frame
    finally:
        process.kill()
        process.wait(timeout=DEADLINE_SECONDS)


def test_proxied_transcript_round_trips_interactively():
    """Multiple request/response exchanges over one open session."""
    process = _spawn_proxy(str(fixture_path("echo_agent.py")))
    try:
        for request_id in (1, 2, 3):
            frame = (
                json.dumps({"jsonrpc": "2.0", "id": request_id, "method": "initialize"}) + "\n"
            ).encode()
            process.stdin.write(frame)
            process.stdin.flush()
            assert _read_line_with_deadline(process.stdout, DEADLINE_SECONDS) == frame
    finally:
        process.kill()
        process.wait(timeout=DEADLINE_SECONDS)
