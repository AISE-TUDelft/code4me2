"""Codex adapter (``codex-v1``): additive semantic enrichment for tool events.

Adds a ``normalized_tool_kind`` label to generic ``tool.*`` events. It never
removes or replaces a generic field: the shared ``enrich_with_adapter`` keeps
the generic payload authoritative on conflict.
"""

from __future__ import annotations

from typing import Optional

from research.telemetry.normalization.models import CanonicalCandidateV1

__all__ = ["CodexAdapterV1", "ADAPTER_VERSION", "MAPPING_RULE_VERSION"]

ADAPTER_VERSION = "codex-v1"
MAPPING_RULE_VERSION = "codex-v1/mapping-1"

_TOOL_EVENT_TYPES = frozenset(
    {"tool.created", "tool.started", "tool.completed", "tool.failed"}
)

_EDIT_MARKERS = ("edit", "write", "patch", "apply", "create_file")
_READ_MARKERS = ("read", "open", "view")
_COMMAND_MARKERS = ("exec", "command", "shell", "terminal", "bash")


class CodexAdapterV1:
    """Enriches generic tool events with a normalized tool-kind label."""

    adapter_version = ADAPTER_VERSION
    mapping_rule_version = MAPPING_RULE_VERSION
    supported_release_ranges = [">=0.0.0"]

    def supports(self, release: str) -> bool:
        """The enrichment rules are release-independent."""
        return isinstance(release, str)

    def enrich(self, candidate: CanonicalCandidateV1) -> Optional[CanonicalCandidateV1]:
        """Return a candidate with an added label, or ``None`` to leave it as-is."""
        if candidate.event_type.value not in _TOOL_EVENT_TYPES:
            return None
        kind = str(candidate.payload.get("tool_kind") or "").lower()
        name = str(candidate.payload.get("tool_name") or "").lower()
        haystack = f"{kind} {name}"
        label = None
        if any(marker in haystack for marker in _EDIT_MARKERS):
            label = "FILE_EDIT"
        elif any(marker in haystack for marker in _READ_MARKERS):
            label = "FILE_READ"
        elif any(marker in haystack for marker in _COMMAND_MARKERS):
            label = "COMMAND"
        if label is None:
            return None
        payload = dict(candidate.payload)
        payload["normalized_tool_kind"] = label
        return candidate.model_copy(update={"payload": payload})
