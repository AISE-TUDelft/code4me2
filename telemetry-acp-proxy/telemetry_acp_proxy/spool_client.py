"""Local authenticated spool client.

The proxy only ever hands **canonical, privacy-filtered events** to the
plugin-owned spool. It never buffers raw ACP frames: if the receiver is
unavailable, bounded retry/backoff is attempted and then the batch is dropped
with explicit accounting (a diagnostic + counts), never queued indefinitely.

Delivery is *acknowledged*: an event id counts as delivered only when the spool
response lists it under ``accepted`` or ``duplicate``. A timeout, a non-2xx
status, or a malformed/missing acknowledgement is retryable and deletes nothing.

The local capability is one-time: :class:`OneTimeCapability` can be consumed
exactly once, so a replayed launch cannot reuse it.
"""

from __future__ import annotations

import json
import random
import secrets
import time
from dataclasses import dataclass, field
from typing import Callable, Mapping, Optional, Sequence
from urllib import request as urllib_request
from urllib.parse import urlparse

from research.telemetry.models import CanonicalEventV1

__all__ = [
    "CapabilityAlreadyConsumed",
    "EMITTER_ID_PREFIX",
    "LocalSpoolClient",
    "OneTimeCapability",
    "SpoolSendResult",
    "generate_emitter_id",
]

SCHEMA_VERSION = "1"

#: Stable emitter-family prefix. Every generated proxy emitter id starts with
#: it, so analytics can still group by family while each proxy process stays
#: uniquely addressable.
EMITTER_ID_PREFIX = "acp-proxy"

#: Historical constant emitter id. Retained for callers that explicitly want it
#: (and for backwards-compatible tests); it is NOT the process default anymore.
DEFAULT_EMITTER_ID = EMITTER_ID_PREFIX


def generate_emitter_id(prefix: str = EMITTER_ID_PREFIX) -> str:
    """Return a process-unique emitter id of the form ``<prefix>:<8 hex>``.

    The IDE collector already mints a per-instance opaque emitter
    (``ide:ide-<hash>``). The proxy mirrors that: it runs one process per ACP
    chat, and its sequence allocator is in-memory (it restarts at 1 every
    launch), so a constant emitter id makes two proxy processes in the same
    research session collide on ``(research_session_id, emitter_id,
    emitter_sequence)``. A per-process id keeps each process's monotonic
    sequence in its own namespace while the shared ``acp-proxy`` prefix
    preserves emitter-family grouping.
    """
    return f"{prefix}:{secrets.token_hex(4)}"
DEFAULT_MAX_ATTEMPTS = 3
DEFAULT_BASE_DELAY_SECONDS = 0.05
DEFAULT_MAX_DELAY_SECONDS = 5.0
DEFAULT_JITTER_RATIO = 0.25

Transport = Callable[[str, str, dict], Optional[dict]]


class CapabilityAlreadyConsumed(RuntimeError):
    """Raised when a one-time IPC capability is consumed more than once."""


class OneTimeCapability:
    """An opaque, process-local IPC capability consumable exactly once."""

    def __init__(self, value: str) -> None:
        if not value or not value.strip():
            raise ValueError("capability must not be blank")
        self._value = value
        self._consumed = False

    @property
    def consumed(self) -> bool:
        """Whether this capability has already been consumed."""
        return self._consumed

    def consume(self) -> str:
        """Return the capability value once; a second call raises."""
        if self._consumed:
            raise CapabilityAlreadyConsumed("IPC capability has already been consumed")
        self._consumed = True
        return self._value

    def __repr__(self) -> str:
        return f"OneTimeCapability(value=<redacted>, consumed={self._consumed})"


@dataclass
class SpoolSendResult:
    """Outcome of one spool handoff attempt (bounded, explicit, acknowledged)."""

    sent: int = 0
    dropped: int = 0
    attempts: int = 0
    error: Optional[str] = None
    delivered_ids: list[str] = field(default_factory=list)
    dropped_ids: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """Whether every event was delivered."""
        return self.dropped == 0 and self.error is None


def _event_ids(payload: Mapping) -> list[str]:
    events = payload.get("events")
    if not isinstance(events, list):
        return []
    return [str(event.get("event_id")) for event in events if isinstance(event, Mapping)]


def _file_transport(endpoint: str, capability: str, payload: dict) -> dict:
    """Append the batch to a ``file://`` sink (test/dev handoff).

    A file sink has no server-side acknowledgement, so every event in the written
    batch is reported as accepted. The HTTP path is the only one the plugin
    actually spools through in production.
    """
    path = endpoint[len("file://") :]
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n")
    return {"accepted": _event_ids(payload), "duplicate": [], "rejected": []}


def _http_transport(endpoint: str, capability: str, payload: dict) -> dict:
    body = json.dumps(payload).encode("utf-8")
    request = urllib_request.Request(
        endpoint,
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Research-Capability": capability,
        },
        method="POST",
    )
    with urllib_request.urlopen(request, timeout=5.0) as response:  # noqa: S310
        status = getattr(response, "status", 200)
        raw = response.read()
        if not 200 <= status < 300:
            raise OSError(f"spool endpoint returned status {status}")
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as error:
            raise OSError(f"spool endpoint returned malformed json: {error}") from error
        if not isinstance(parsed, dict):
            raise OSError("spool endpoint returned a non-object acknowledgement")
        return parsed


def _acknowledged_ids(response: object) -> list[str]:
    """Ids the spool durably holds (``accepted`` + ``duplicate``).

    Raises when the response is not a usable acknowledgement so the caller treats
    it as a retryable outcome instead of a false delivery.
    """
    if not isinstance(response, Mapping):
        raise ValueError("spool acknowledgement must be a json object")
    if not any(key in response for key in ("accepted", "duplicate", "rejected")):
        raise ValueError("spool acknowledgement has no dispositions")
    acknowledged: list[str] = []
    for key in ("accepted", "duplicate"):
        value = response.get(key)
        if value is None:
            continue
        if not isinstance(value, list):
            raise ValueError(f"spool acknowledgement '{key}' must be a list")
        for item in value:
            if isinstance(item, str):
                acknowledged.append(item)
            elif isinstance(item, Mapping) and isinstance(item.get("event_id"), str):
                acknowledged.append(item["event_id"])
            else:
                raise ValueError(f"spool acknowledgement '{key}' has an invalid entry")
    return list(dict.fromkeys(acknowledged))


class LocalSpoolClient:
    """Bounded-retry, acknowledgement-checked client for the plugin-owned spool."""

    def __init__(
        self,
        endpoint: str,
        capability: OneTimeCapability | str,
        *,
        proxy_digest: str = "",
        emitter_id: str = DEFAULT_EMITTER_ID,
        transport: Optional[Transport] = None,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
        base_delay_seconds: float = DEFAULT_BASE_DELAY_SECONDS,
        max_delay_seconds: float = DEFAULT_MAX_DELAY_SECONDS,
        jitter: Callable[[], float] = random.random,
        jitter_ratio: float = DEFAULT_JITTER_RATIO,
        sleep: Callable[[float], None] = time.sleep,
        diagnostics: Optional[Callable[[str], None]] = None,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        if base_delay_seconds < 0 or max_delay_seconds < 0:
            raise ValueError("backoff delays must not be negative")
        if not 0.0 <= jitter_ratio <= 1.0:
            raise ValueError("jitter_ratio must be within [0, 1]")
        self.endpoint = endpoint
        self.proxy_digest = proxy_digest
        self.emitter_id = emitter_id
        self._capability = (
            capability
            if isinstance(capability, OneTimeCapability)
            else OneTimeCapability(capability)
        )
        self._scheme = urlparse(endpoint).scheme
        self.transport = transport or self._default_transport
        self.max_attempts = max_attempts
        self.base_delay_seconds = base_delay_seconds
        self.max_delay_seconds = max_delay_seconds
        self._jitter = jitter
        self._jitter_ratio = jitter_ratio
        self._sleep = sleep
        self._diagnostics = diagnostics
        self._capability_value: Optional[str] = None

    def _default_transport(self, endpoint: str, capability: str, payload: dict) -> dict:
        if self._scheme == "file":
            return _file_transport(endpoint, capability, payload)
        if self._scheme in {"http", "https"}:
            return _http_transport(endpoint, capability, payload)
        raise ValueError(f"unsupported spool endpoint scheme: {self._scheme!r}")

    def _capability_token(self) -> str:
        if self._capability_value is None:
            self._capability_value = self._capability.consume()
        return self._capability_value

    def _emit(self, message: str) -> None:
        if self._diagnostics is not None:
            self._diagnostics(message)

    def _backoff_seconds(self, attempt: int) -> float:
        """Capped exponential backoff with bounded jitter (attempt is 1-based)."""
        full = min(self.max_delay_seconds, self.base_delay_seconds * (2 ** (attempt - 1)))
        jitter = self._jitter()
        jitter = min(1.0, max(0.0, jitter))
        return max(0.0, full * (1.0 + self._jitter_ratio * jitter))

    def send(self, events: Sequence[CanonicalEventV1]) -> SpoolSendResult:
        """Send canonical events with bounded retry and acknowledged accounting."""
        batch = list(events)
        if not batch:
            return SpoolSendResult(sent=0, dropped=0, attempts=0)

        ids = [str(event.event_id) for event in batch]
        payload = {
            "schema_version": SCHEMA_VERSION,
            "proxy_digest": self.proxy_digest,
            "emitter_id": self.emitter_id,
            "events": [event.model_dump(mode="json") for event in batch],
        }
        capability = self._capability_token()
        last_error: Optional[str] = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                response = self.transport(self.endpoint, capability, payload)
                acknowledged = set(_acknowledged_ids(response))
            except Exception as error:  # noqa: BLE001 - report any transport failure
                last_error = str(error)
                self._emit(
                    f"proxy: spool send failed (attempt {attempt}/{self.max_attempts}): "
                    f"{last_error}"
                )
                if attempt < self.max_attempts:
                    self._sleep(self._backoff_seconds(attempt))
                continue

            delivered = [event_id for event_id in ids if event_id in acknowledged]
            dropped = [event_id for event_id in ids if event_id not in acknowledged]
            if dropped:
                self._emit(
                    f"proxy: spool did not acknowledge {len(dropped)} canonical event(s)"
                )
            return SpoolSendResult(
                sent=len(delivered),
                dropped=len(dropped),
                attempts=attempt,
                delivered_ids=delivered,
                dropped_ids=dropped,
            )

        self._emit(
            f"proxy: dropping {len(batch)} canonical event(s); spool unavailable"
        )
        return SpoolSendResult(
            sent=0,
            dropped=len(batch),
            attempts=self.max_attempts,
            delivered_ids=[],
            dropped_ids=ids,
            error=last_error,
        )
