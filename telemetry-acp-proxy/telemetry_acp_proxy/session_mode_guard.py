"""Refuse host ``session/set_mode`` requests: the study fixes the approval mode.

JetBrains AI Chat shows the agent's mode picker (for example Goose's
``approve``/``auto``). A participant who switches it silently leaves the arm's
per-step approval policy, without any telemetry of the change. The proxy answers
the request itself with a JSON-RPC error and never forwards it, so the agent
keeps the mode its release configured. The refused request is still observed by
the telemetry pipeline like any other host frame.

Interception is as narrow as the ``initialize`` replay: only a chunk that
begins and ends on frame boundaries and decodes to exactly one complete request
carrying the method and a legal id is answered; everything else is forwarded
byte for byte.
"""

from __future__ import annotations

import threading
from typing import Any, Callable, Mapping, Optional

from .framing import Frame, encode_message
from .observe import AcpDirection

__all__ = ["SET_MODE_METHOD", "SET_MODE_REFUSED_CODE", "SET_MODE_REFUSED_MESSAGE", "SessionModeGuard"]

SET_MODE_METHOD = "session/set_mode"
SET_MODE_REFUSED_CODE = -32000
SET_MODE_REFUSED_MESSAGE = (
    "The agent's approval mode is fixed by the research study and cannot be changed here."
)


def _legal_jsonrpc_id(value: Any) -> bool:
    return isinstance(value, (str, int, float)) and not isinstance(value, bool)


class SessionModeGuard:
    """Thread-safe interceptor answering ``session/set_mode`` with an error.

    ``on_refusal`` is an optional no-arg callback invoked for every refused
    request; it runs outside the lock and must be cheap and non-raising.
    """

    def __init__(self, *, on_refusal: Optional[Callable[[], None]] = None) -> None:
        self._on_refusal = on_refusal
        self._lock = threading.Lock()
        # Host bytes preceding an incomplete frame have already been forwarded;
        # a frame completed by a later chunk must never be replaced.
        self._pending_host_wire_bytes = 0
        self.refused = 0

    def intercept(
        self,
        direction: AcpDirection,
        chunk: bytes,
        frames: list[Frame],
        buffered_partial: int,
    ) -> Optional[bytes]:
        if direction != AcpDirection.HOST_TO_AGENT:
            return None
        with self._lock:
            started_on_boundary = self._pending_host_wire_bytes == 0
            self._pending_host_wire_bytes += len(chunk) - sum(len(frame.raw) for frame in frames)
            ended_on_boundary = self._pending_host_wire_bytes == 0
        if buffered_partial != 0 or not ended_on_boundary or not started_on_boundary or len(frames) != 1:
            return None
        frame = frames[0]
        if not frame.ok:
            return None
        payload = frame.payload
        if not isinstance(payload, Mapping) or payload.get("method") != SET_MODE_METHOD:
            return None
        message_id = payload.get("id")
        if not _legal_jsonrpc_id(message_id):
            # A notification cannot be answered; it is forwarded untouched.
            return None
        replacement = encode_message(
            {
                "jsonrpc": "2.0",
                "id": message_id,
                "error": {"code": SET_MODE_REFUSED_CODE, "message": SET_MODE_REFUSED_MESSAGE},
            },
            framing=frame.framing,
        )
        with self._lock:
            self.refused += 1
        if self._on_refusal is not None:
            self._on_refusal()
        return replacement
