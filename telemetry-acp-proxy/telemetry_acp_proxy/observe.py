"""Transient, in-memory observation of ACP protocol frames.

An :class:`ObservedAcpMessageV1` captures only metadata about one decoded frame:
direction, opaque JSON-RPC id, method, local receive timing, a per-emitter
sequence, the parse status and a ``sha256:`` source digest. The parsed envelope
is kept in memory only long enough to normalize it; raw frames are never
retained, written to disk, or written to stdout.
"""

from __future__ import annotations

import hashlib
import time
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Mapping, Optional

from pydantic import BaseModel, ConfigDict, Field

from .framing import PARSE_FAILED_CODE, Frame, FrameReader

__all__ = [
    "DIRECTION_AGENT_TO_HOST",
    "DIRECTION_HOST_TO_AGENT",
    "AcpDirection",
    "ObservedAcpMessageV1",
    "Observer",
    "source_digest",
]

DIRECTION_HOST_TO_AGENT = "host_to_agent"
DIRECTION_AGENT_TO_HOST = "agent_to_host"

DIGEST_PREFIX = "sha256:"


class AcpDirection(str, Enum):
    """Which way a frame travelled across the proxy."""

    HOST_TO_AGENT = DIRECTION_HOST_TO_AGENT
    AGENT_TO_HOST = DIRECTION_AGENT_TO_HOST


def source_digest(raw: bytes) -> str:
    """Return the ``sha256:``-prefixed digest of an exact frame."""
    return DIGEST_PREFIX + hashlib.sha256(raw).hexdigest()


class ObservedAcpMessageV1(BaseModel):
    """One transient observation of a protocol frame (no raw bytes retained)."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    direction: AcpDirection
    jsonrpc_id: Optional[str] = None
    method: Optional[str] = None
    receive_monotonic_ns: int
    receive_wall_time: datetime
    emitter_sequence: int
    parse_status: str
    source_digest: str
    framing: str
    size: int
    parse_error: Optional[str] = None
    error_code: Optional[str] = None
    # In-memory only; excluded from repr so it is never logged casually.
    payload: Optional[Mapping[str, Any]] = Field(default=None, repr=False)

    @property
    def ok(self) -> bool:
        """Whether the frame parsed as JSON."""
        return self.parse_status == "ok"

    @property
    def is_request(self) -> bool:
        """Whether this is a JSON-RPC request (method + id)."""
        return self.method is not None and self.jsonrpc_id is not None

    @property
    def is_notification(self) -> bool:
        """Whether this is a JSON-RPC notification (method, no id)."""
        return self.method is not None and self.jsonrpc_id is None

    @property
    def is_response(self) -> bool:
        """Whether this is a JSON-RPC response (id, no method)."""
        return self.method is None and self.jsonrpc_id is not None


def _default_clock() -> tuple[int, datetime]:
    return time.monotonic_ns(), datetime.now(timezone.utc)


class Observer:
    """Buffers stream chunks into frames and records observations in memory."""

    def __init__(
        self,
        *,
        emitter_id: str = "acp-proxy",
        clock: Optional[Callable[[], tuple[int, datetime]]] = None,
        on_observation: Optional[Callable[[ObservedAcpMessageV1], Any]] = None,
    ) -> None:
        self.emitter_id = emitter_id
        self._clock = clock or _default_clock
        self.on_observation = on_observation
        self._readers: dict[AcpDirection, FrameReader] = {
            direction: FrameReader() for direction in AcpDirection
        }
        self._sequence = 0
        self.records: list[ObservedAcpMessageV1] = []

    @property
    def buffered_bytes(self) -> int:
        """Bytes buffered across both directions awaiting more input."""
        return sum(reader.buffered_bytes for reader in self._readers.values())

    def observe(
        self, direction: AcpDirection | str, chunk: bytes
    ) -> list[ObservedAcpMessageV1]:
        """Feed ``chunk`` for ``direction`` and return new observations."""
        resolved = AcpDirection(direction)
        reader = self._readers[resolved]
        return self._record_frames(resolved, reader.feed(chunk))

    def close(self) -> list[ObservedAcpMessageV1]:
        """Flush partial trailing frames for every direction."""
        observations: list[ObservedAcpMessageV1] = []
        for direction, reader in self._readers.items():
            observations.extend(self._record_frames(direction, reader.close()))
        return observations

    def _record_frames(
        self, direction: AcpDirection, frames: list[Frame]
    ) -> list[ObservedAcpMessageV1]:
        observations = [self._record(direction, frame) for frame in frames]
        if self.on_observation is not None:
            for observation in observations:
                self.on_observation(observation)
        return observations

    def _record(self, direction: AcpDirection, frame: Frame) -> ObservedAcpMessageV1:
        monotonic_ns, received_at = self._clock()
        self._sequence += 1

        method: Optional[str] = None
        jsonrpc_id: Optional[str] = None
        payload: Optional[Mapping[str, Any]] = None
        if isinstance(frame.payload, Mapping):
            payload = frame.payload
            raw_method = frame.payload.get("method")
            if isinstance(raw_method, str):
                method = raw_method
            raw_id = frame.payload.get("id")
            if raw_id is not None:
                jsonrpc_id = str(raw_id)

        observation = ObservedAcpMessageV1(
            direction=direction,
            jsonrpc_id=jsonrpc_id,
            method=method,
            receive_monotonic_ns=monotonic_ns,
            receive_wall_time=received_at,
            emitter_sequence=self._sequence,
            parse_status="ok" if frame.parse_error is None else "error",
            source_digest=source_digest(frame.raw),
            framing=frame.framing,
            size=len(frame.raw),
            parse_error=frame.parse_error,
            error_code=frame.error_code or (None if frame.ok else PARSE_FAILED_CODE),
            payload=payload,
        )
        self.records.append(observation)
        return observation
