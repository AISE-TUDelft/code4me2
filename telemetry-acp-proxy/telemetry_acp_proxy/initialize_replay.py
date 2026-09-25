"""Idempotent ``initialize`` replay for hosts that re-initialize a live process.

JetBrains AI Assistant resubmits a failed prompt by creating a new session on an
already-initialized ACP proxy process. Its session-creation path sends
``initialize`` a second time on the same connection; a strict ACP agent answers
JSON-RPC ``-32603`` ("Already initialized") and the chat wedges. With the
``--compat-idempotent-initialize`` opt-in, the proxy answers the repeated
``initialize`` from the cached handshake result instead of forwarding it.

Interception is deliberately narrow so the byte-preserving default is never
weakened:

* a request is only replaced when the chunk begins and ends on frame boundaries
  and decodes to exactly one complete, parseable frame carrying the
  ``initialize`` method and a non-null id;
* a replacement is only emitted after the agent's response to the *first*
  initialize (matched by its pending id) was observed carrying a ``result``;
* every other chunk - multiple frames, a partial tail, another method, a
  notification - returns ``None`` and is forwarded untouched.
"""

from __future__ import annotations

import threading
from typing import Any, Callable, Mapping, Optional

from .framing import Frame, encode_message
from .observe import AcpDirection

__all__ = ["INITIALIZE_METHOD", "InitializeReplay"]

INITIALIZE_METHOD = "initialize"


def _legal_jsonrpc_id(value: Any) -> bool:
    """Whether ``value`` is a JSON-RPC id (string or number, never a bool)."""
    return isinstance(value, (str, int, float)) and not isinstance(value, bool)


class InitializeReplay:
    """Thread-safe cache of the first ``initialize`` result on one connection.

    Both forwarding pumps call :meth:`intercept`, so the pending id set and the
    cached result are guarded by one lock. ``on_replay`` is an optional no-arg
    callback invoked whenever a duplicate is answered; it runs outside the lock
    and must be cheap and non-raising (an exception would reach the pump).
    """

    def __init__(self, *, on_replay: Optional[Callable[[], None]] = None) -> None:
        self._on_replay = on_replay
        self._lock = threading.Lock()
        self._pending_ids: set[Any] = set()
        # Host bytes preceding an incomplete frame have already been forwarded.
        # Count wire bytes rather than FrameReader.buffered_bytes: Content-Length
        # can consume a header while its body is still incomplete and leave that
        # buffer empty.
        self._pending_host_wire_bytes = 0
        self._result: Any = None
        self._has_result = False

    def intercept(
        self,
        direction: AcpDirection,
        chunk: bytes,
        frames: list[Frame],
        buffered_partial: int,
    ) -> Optional[bytes]:
        """Return replacement bytes for a repeated ``initialize``, else ``None``.

        ``frames`` and ``buffered_partial`` are the decoded frames and the
        reader's undecoded byte count after feeding ``chunk``.
        """
        if direction == AcpDirection.AGENT_TO_HOST:
            self._cache_results(frames)
            return None
        if direction != AcpDirection.HOST_TO_AGENT:
            return None
        with self._lock:
            started_on_boundary = self._pending_host_wire_bytes == 0
            self._pending_host_wire_bytes += len(chunk) - sum(len(frame.raw) for frame in frames)
            ended_on_boundary = self._pending_host_wire_bytes == 0
        # Never drop a chunk that carries more than one frame, leaves a
        # partial tail, or completes a frame whose earlier bytes were already
        # forwarded to the agent. Replacement in any of these cases would
        # silently discard bytes and leave the agent with a truncated request.
        if buffered_partial != 0 or not ended_on_boundary or len(frames) != 1:
            return None
        frame = frames[0]
        if not frame.ok:
            return None
        payload = frame.payload
        if not isinstance(payload, Mapping):
            return None
        if payload.get("method") != INITIALIZE_METHOD:
            return None
        message_id = payload.get("id")
        if not _legal_jsonrpc_id(message_id):
            return None
        replacement: Optional[bytes] = None
        with self._lock:
            if self._has_result and started_on_boundary:
                replacement = encode_message(
                    {"jsonrpc": "2.0", "id": message_id, "result": self._result},
                    framing=frame.framing,
                )
            else:
                self._pending_ids.add(message_id)
        if replacement is not None and self._on_replay is not None:
            self._on_replay()
        return replacement

    def _cache_results(self, frames: list[Frame]) -> None:
        """Cache the result of any agent response matching a pending id."""
        for frame in frames:
            if not frame.ok:
                continue
            payload = frame.payload
            if not isinstance(payload, Mapping):
                continue
            # A JSON-RPC response has an id and no method; the error shape
            # carries no result and must never become a cached answer.
            if payload.get("method") is not None:
                continue
            if "id" not in payload or "result" not in payload:
                continue
            identifier = payload.get("id")
            if not _legal_jsonrpc_id(identifier):
                continue
            with self._lock:
                if identifier in self._pending_ids:
                    self._pending_ids.discard(identifier)
                    self._result = payload.get("result")
                    self._has_result = True
