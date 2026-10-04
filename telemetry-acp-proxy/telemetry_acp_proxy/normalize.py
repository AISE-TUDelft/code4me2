"""Proxy normalization: shared generic ACP mapping plus optional adapter enrichment.

The base pipeline works with **no adapter**. An adapter (``AdapterV1``) may merge
additive labels into a generic candidate, but the shared
:func:`research.telemetry.normalization.generic_acp.enrich_with_adapter` keeps the
generic payload authoritative: an adapter can never delete or replace a generic
field, event type, source, or fidelity.

Capability snapshots are built directly from the observed ``initialize``
request/response so declared capabilities and observed behavior stay separate.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping, Optional

from research.study.agents.enums import SnapshotCapabilityState
from research.study.agents.models import (
    CapabilityEntry,
    CapabilitySnapshotV1,
    EnvironmentRef,
)
from research.telemetry.builder import EventBuilder, SequenceAllocator
from research.telemetry.models import CanonicalEventV1
from research.telemetry.normalization import materialize_candidate
from research.telemetry.normalization.generic_acp import (
    GENERIC_ACP_NORMALIZER_VERSION,
    AgentAdapter,
    GenericAcpNormalizer,
    enrich_with_adapter,
)

from .observe import AcpDirection, ObservedAcpMessageV1

__all__ = [
    "GENERIC_ACP_NORMALIZER_VERSION",
    "SESSION_ID_KEY",
    "ProxyNormalizer",
    "build_capability_snapshot",
]

#: Payload key carrying the ACP session id. It is the generic, agent-independent
#: join handle the backend uses to attribute proxy-observed events back to the
#: agent run that produced them (``agent_task.agent_session_id``). The privacy
#: classifier keeps it (BEHAVIORAL: a key token matches ``session``), so it
#: survives filtering intact. One ACP session hosts many runs, so the backend
#: additionally bounds the match by the run's start/complete window.
SESSION_ID_KEY = "session_id"


def _session_id_from(payload: Any) -> Optional[str]:
    """Extract the ACP session id from an observed envelope, if present.

    ACP carries it as ``params.sessionId`` on session-scoped requests
    (``session/prompt``, ``session/cancel``, ``session/update``) and as
    ``result.sessionId`` on the ``session/new`` response.
    """
    if not isinstance(payload, Mapping):
        return None
    for container in ("params", "result"):
        section = payload.get(container)
        if isinstance(section, Mapping):
            candidate = section.get("sessionId")
            if isinstance(candidate, str) and candidate:
                return candidate
    return None


#: Marks a response whose request this stream never observed.
_UNTRACKED = object()


def _other_side(direction: str) -> str:
    """The opposite direction: a request and its response travel opposite ways."""
    if direction == AcpDirection.HOST_TO_AGENT.value:
        return AcpDirection.AGENT_TO_HOST.value
    return AcpDirection.HOST_TO_AGENT.value


def _flatten_names(prefix: str, value: Any, out: dict[str, Any]) -> None:
    if not isinstance(value, Mapping):
        return
    for key, item in value.items():
        name = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(item, Mapping):
            out[name] = None
            _flatten_names(name, item, out)
        else:
            out[name] = item


class ProxyNormalizer:
    """Maps observed ACP messages to canonical events via the shared contract."""

    def __init__(
        self,
        *,
        adapter: Optional[AgentAdapter] = None,
        emitter_id: str = "acp-proxy",
        allocator: Optional[SequenceAllocator] = None,
    ) -> None:
        self.adapter = adapter
        self.emitter_id = emitter_id
        self._generic = GenericAcpNormalizer()
        self._builder = EventBuilder(allocator or SequenceAllocator())
        # The chat this stream serves now: the last ACP session id observed on
        # a session-scoped frame. Only a notification that names no chat falls
        # back to it, so telemetry stays attributable without agent cooperation.
        self._acp_session_id: Optional[str] = None
        # In-flight requests, (direction, JSON-RPC id) -> the chat each named
        # (``None`` for ``initialize`` or ``session/new``), so a response is
        # attributed to its request's chat rather than whichever spoke last.
        self._request_sessions: dict[tuple[str, str], Optional[str]] = {}

    @property
    def current_session_id(self) -> Optional[str]:
        """The ACP session id of the chat this stream serves now, if any."""
        return self._acp_session_id

    def normalize_observed(
        self, observed: ObservedAcpMessageV1
    ) -> list[CanonicalEventV1]:
        """Return the canonical events for one observed message."""
        if not observed.ok or observed.payload is None:
            return []

        session_id = self._session_of(observed)

        result = self._generic.normalize(
            observed.payload,
            source_event_id=observed.jsonrpc_id,
            direction=observed.direction.value,
        )
        events: list[CanonicalEventV1] = []
        for candidate in result.candidates:
            enriched = candidate
            if self.adapter is not None:
                enriched = enrich_with_adapter(candidate, self.adapter)
            event = materialize_candidate(
                self._builder,
                enriched,
                result,
                emitter_id=self.emitter_id,
                occurred_at=observed.receive_wall_time,
                monotonic_ns=observed.receive_monotonic_ns,
            )
            events.append(self._stamp_session_id(event, session_id))
        return events

    def _session_of(self, observed: ObservedAcpMessageV1) -> Optional[str]:
        """The chat an observed frame belongs to, learning ids on the way.

        A frame naming a chat belongs to it. A response belongs to the chat its
        request named, or to none when the request named none: the answer to a
        ``session/new`` (or a failed one) is never given the previous chat's
        id. A request naming no chat belongs to none; only a notification
        naming no chat falls back to the current one.
        """
        direction = observed.direction.value
        answered: Any = _UNTRACKED
        if observed.is_response:
            answered = self._request_sessions.pop(
                (_other_side(direction), str(observed.jsonrpc_id)), _UNTRACKED
            )
        discovered = _session_id_from(observed.payload)
        if discovered is not None:
            self._acp_session_id = discovered
        if observed.is_request:
            self._request_sessions[(direction, str(observed.jsonrpc_id))] = discovered
            return discovered
        if discovered is not None:
            return discovered
        if answered is not _UNTRACKED:
            return answered
        return self._acp_session_id

    @staticmethod
    def _stamp_session_id(
        event: CanonicalEventV1, session_id: Optional[str]
    ) -> CanonicalEventV1:
        """Attach the frame's chat id to an event that does not carry one.

        Additive only: an existing payload value always wins, so the id the
        normalizer mapped itself (a new chat's, a prompt's) is never overwritten.
        """
        if session_id is None or SESSION_ID_KEY in event.payload:
            return event
        return event.model_copy(
            update={"payload": {**event.payload, SESSION_ID_KEY: session_id}}
        )


def _initialize_declared(
    observations: Iterable[ObservedAcpMessageV1],
) -> dict[str, CapabilityEntry]:
    declared: dict[str, CapabilityEntry] = {}
    for observed in observations:
        if observed.method != "initialize" or not isinstance(observed.payload, Mapping):
            continue
        params = observed.payload.get("params")
        capabilities: dict[str, Any] = {}
        if isinstance(params, Mapping):
            _flatten_names("client", params.get("clientCapabilities"), capabilities)
        result = observed.payload.get("result")
        if isinstance(result, Mapping):
            _flatten_names("agent", result.get("agentCapabilities"), capabilities)
        for name in capabilities:
            declared[name] = CapabilityEntry(
                state=SnapshotCapabilityState.DECLARED,
                value=None,
                source="initialize",
            )
    return declared


def _observed_capabilities(
    observations: Iterable[ObservedAcpMessageV1],
) -> dict[str, CapabilityEntry]:
    observed_map: dict[str, CapabilityEntry] = {}
    saw_initialize = False
    for observed in observations:
        if observed.method == "initialize":
            saw_initialize = True
        if observed.method == "session/request_permission":
            observed_map["PERMISSION_REQUEST"] = CapabilityEntry(
                state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
            )
        if (
            observed.direction.value == "host_to_agent"
            and observed.is_response
            and isinstance(observed.payload, Mapping)
            and isinstance(observed.payload.get("result"), Mapping)
            and "outcome" in observed.payload["result"]
        ):
            observed_map["PERMISSION_DECISION"] = CapabilityEntry(
                state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
            )
        if observed.method in {
            "session/update",
        } and isinstance(observed.payload, Mapping):
            params = observed.payload.get("params")
            update = params.get("update") if isinstance(params, Mapping) else None
            kind = update.get("sessionUpdate") if isinstance(update, Mapping) else None
            if kind in {"tool_call", "tool_call_update"}:
                observed_map["TOOL_LIFECYCLE"] = CapabilityEntry(
                    state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
                )
            if kind in {"agent_message_chunk", "agent_thought_chunk"}:
                observed_map["MESSAGE_STREAM"] = CapabilityEntry(
                    state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
                )
            if kind == "usage_update":
                observed_map["USAGE"] = CapabilityEntry(
                    state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
                )
            if kind == "plan":
                observed_map["PLAN"] = CapabilityEntry(
                    state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
                )
    if saw_initialize:
        observed_map["INITIALIZE"] = CapabilityEntry(
            state=SnapshotCapabilityState.OBSERVED, value=True, source="proxy"
        )
    return observed_map


def build_capability_snapshot(
    observations: Iterable[ObservedAcpMessageV1],
    *,
    agent_id: str,
    release_id: str,
    protocol_version: str,
    environment: Optional[EnvironmentRef] = None,
    adapter_id: Optional[str] = None,
    adapter_version: Optional[str] = None,
    captured_at: Optional[datetime] = None,
    evidence_refs: Optional[list[str]] = None,
) -> CapabilitySnapshotV1:
    """Build a declared-vs-observed snapshot from the transcript so far.

    Declared capabilities are preserved even when never observed; a declared
    capability with no observation stays ``DECLARED`` and is never synthesized
    into an observed one.
    """
    items = list(observations)
    declared = _initialize_declared(items)
    observed = _observed_capabilities(items)
    evidence = list(evidence_refs or [])
    evidence.extend(
        observed_message.source_digest
        for observed_message in items
        if observed_message.direction == "host_to_agent" and observed_message.ok
    )
    return CapabilitySnapshotV1(
        snapshot_id=uuid.uuid4(),
        release_id=release_id,
        agent_id=agent_id,
        adapter_id=adapter_id,
        adapter_version=adapter_version,
        environment=environment or EnvironmentRef(),
        protocol_version=protocol_version,
        declared=declared,
        observed=observed,
        evidence_refs=evidence[:64],
        captured_at=captured_at or datetime.now(timezone.utc),
    )
