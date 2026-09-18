"""Outbound privacy gate and proxy-owned lifecycle event builders.

Every canonical event is passed through the **shared** privacy engine
(``research.telemetry.privacy``) immediately before it can leave the process.
The gate never writes raw payloads to logs: parse failures, agent crashes, and
proxy errors are built here as metadata-only events and filtered like any other.

The lifecycle builders stay proxy-owned because they describe the proxy's own
failures, which no source normalizer can observe. They are built on the shared
:class:`research.telemetry.builder.EventBuilder` /
:class:`research.telemetry.models.CanonicalEventV1` contract and remain
metadata-only: a kind, an optional source digest, and counts. Raw frame bytes,
prompts, tool arguments, and credentials are never carried.
"""

from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Optional

from research.telemetry.builder import EventBuilder, SequenceAllocator
from research.telemetry.enums import (
    CanonicalEventType,
    CanonicalFidelity,
    CoverageState,
    EventSource,
)
from research.telemetry.models import (
    CanonicalEventV1,
    Coverage,
    EventMetrics,
    PrivacySummary,
)
from research.telemetry.privacy import PrivacyPolicy, filter_event

from .observe import ObservedAcpMessageV1

if TYPE_CHECKING:
    from uuid import UUID

__all__ = [
    "AGENT_CRASHED_KIND",
    "PROXY_ERROR_KIND",
    "PROXY_LIFECYCLE_NORMALIZER_VERSION",
    "PROXY_PARSE_FAILED_KIND",
    "PrivacyGate",
    "agent_crashed_event",
    "default_policy",
    "parse_failed_event",
    "proxy_error_event",
]

#: Normalizer version stamped on proxy-owned lifecycle events.
PROXY_LIFECYCLE_NORMALIZER_VERSION = "proxy-lifecycle-v1"

#: Failure kind for a malformed ACP frame (content is never captured).
PROXY_PARSE_FAILED_KIND = "acp_parse_failed"

#: Failure kind for an agent process that terminated unexpectedly.
AGENT_CRASHED_KIND = "system_agent_crashed"

#: Failure kind for a proxy-side lifecycle/config error.
PROXY_ERROR_KIND = "system_proxy_error"


def _failure_coverage(reason: str) -> Coverage:
    """Coverage for a proxy-owned failure: present but needing human review."""
    return Coverage(
        state=CoverageState.NEEDS_REVIEW, reason=reason, capability="proxy"
    )


def parse_failed_event(
    parse_error_kind: str,
    *,
    occurred_at: datetime,
    emitter_id: str = "acp-proxy",
    framing: Optional[str] = None,
    evidence_digest: Optional[str] = None,
    monotonic_ns: Optional[int] = None,
    event_id: Optional[UUID] = None,
    emitter_sequence: Optional[int] = None,
    allocator: Optional[SequenceAllocator] = None,
) -> CanonicalEventV1:
    """Build a metadata-only ``system.proxy.error`` event for a parse failure.

    The malformed frame is classified by kind and represented by an optional
    digest; its bytes are never included. ``allocator`` lets the caller share the
    same per-emitter sequence counter as the normalizer, so proxy-owned lifecycle
    events stay monotonic with the rest of the process's events.
    """
    payload = {
        "failure_kind": PROXY_PARSE_FAILED_KIND,
        "parse_error_kind": str(parse_error_kind),
        "error_code": PROXY_PARSE_FAILED_KIND,
    }
    if framing is not None:
        payload["framing_kind"] = str(framing)
    return EventBuilder(allocator).build(
        emitter_id=emitter_id,
        event_type=CanonicalEventType.SYSTEM_PROXY_ERROR,
        source=EventSource.ACP,
        occurred_at=occurred_at,
        normalizer_version=PROXY_LIFECYCLE_NORMALIZER_VERSION,
        payload=payload,
        fidelity=CanonicalFidelity.EXACT,
        lifecycle_state="failed",
        metrics=EventMetrics(),
        coverage=_failure_coverage("malformed ACP frame; metadata only"),
        evidence_digest=evidence_digest,
        monotonic_ns=monotonic_ns,
        event_id=event_id,
        emitter_sequence=emitter_sequence,
    )


def agent_crashed_event(
    *,
    occurred_at: datetime,
    exit_status: Optional[int] = None,
    emitter_id: str = "acp-proxy",
    evidence_digest: Optional[str] = None,
    monotonic_ns: Optional[int] = None,
    event_id: Optional[UUID] = None,
    emitter_sequence: Optional[int] = None,
    allocator: Optional[SequenceAllocator] = None,
) -> CanonicalEventV1:
    """Build a metadata-only ``system.agent.crashed`` event.

    ``allocator`` shares the process's per-emitter sequence counter with the
    normalizer so the crash event does not reuse a sequence already spent.
    """
    payload = {
        "failure_kind": AGENT_CRASHED_KIND,
        "error_code": AGENT_CRASHED_KIND,
    }
    if exit_status is not None:
        payload["exit_status"] = int(exit_status)
    return EventBuilder(allocator).build(
        emitter_id=emitter_id,
        event_type=CanonicalEventType.SYSTEM_AGENT_CRASHED,
        source=EventSource.ACP,
        occurred_at=occurred_at,
        normalizer_version=PROXY_LIFECYCLE_NORMALIZER_VERSION,
        payload=payload,
        fidelity=CanonicalFidelity.EXACT,
        lifecycle_state="failed",
        metrics=EventMetrics(),
        coverage=_failure_coverage("agent process terminated unexpectedly"),
        evidence_digest=evidence_digest,
        monotonic_ns=monotonic_ns,
        event_id=event_id,
        emitter_sequence=emitter_sequence,
    )


def proxy_error_event(
    error_kind: str,
    *,
    occurred_at: datetime,
    emitter_id: str = "acp-proxy",
    evidence_digest: Optional[str] = None,
    monotonic_ns: Optional[int] = None,
    event_id: Optional[UUID] = None,
    emitter_sequence: Optional[int] = None,
    allocator: Optional[SequenceAllocator] = None,
) -> CanonicalEventV1:
    """Build a metadata-only ``system.proxy.error`` event for a proxy error."""
    payload = {
        "failure_kind": PROXY_ERROR_KIND,
        "error_kind": str(error_kind),
        "error_code": PROXY_ERROR_KIND,
    }
    return EventBuilder(allocator).build(
        emitter_id=emitter_id,
        event_type=CanonicalEventType.SYSTEM_PROXY_ERROR,
        source=EventSource.ACP,
        occurred_at=occurred_at,
        normalizer_version=PROXY_LIFECYCLE_NORMALIZER_VERSION,
        payload=payload,
        fidelity=CanonicalFidelity.EXACT,
        lifecycle_state="failed",
        metrics=EventMetrics(),
        coverage=_failure_coverage("proxy lifecycle failure"),
        evidence_digest=evidence_digest,
        monotonic_ns=monotonic_ns,
        event_id=event_id,
        emitter_sequence=emitter_sequence,
    )


def default_policy() -> PrivacyPolicy:
    """The deny-by-default metadata-only policy."""
    return PrivacyPolicy.default()


class PrivacyGate:
    """Applies the shared privacy policy to every outbound canonical event."""

    def __init__(self, policy: Optional[PrivacyPolicy] = None) -> None:
        self.policy = policy or default_policy()

    def filter(self, event: CanonicalEventV1) -> CanonicalEventV1:
        """Return the privacy-filtered event (summary attached)."""
        return filter_event(event, self.policy).event

    def parse_failed(
        self,
        observed: ObservedAcpMessageV1,
        *,
        parse_error_kind: str,
        occurred_at: datetime,
        emitter_id: str = "acp-proxy",
        allocator: Optional[SequenceAllocator] = None,
    ) -> CanonicalEventV1:
        """Build and filter a metadata-only ``acp_parse_failed`` event."""
        event = parse_failed_event(
            parse_error_kind,
            occurred_at=occurred_at,
            emitter_id=emitter_id,
            framing=observed.framing,
            evidence_digest=observed.source_digest,
            monotonic_ns=observed.receive_monotonic_ns,
            allocator=allocator,
        )
        return self.filter(event)

    def proxy_error(
        self,
        error_kind: str,
        *,
        occurred_at: datetime,
        emitter_id: str = "acp-proxy",
        allocator: Optional[SequenceAllocator] = None,
    ) -> CanonicalEventV1:
        """Build and filter a metadata-only ``system.proxy.error`` event."""
        event = proxy_error_event(
            error_kind,
            occurred_at=occurred_at,
            emitter_id=emitter_id,
            allocator=allocator,
        )
        return self.filter(event)

    def summary_of(self, event: CanonicalEventV1) -> PrivacySummary:
        """Return the privacy summary attached to a filtered event."""
        return event.privacy
