"""Optional adapter registry.

The base pipeline works with no adapter. ``get_adapter`` returns an adapter
instance by name, or ``None`` for "none". Adapters only enrich; the shared
``enrich_with_adapter`` guarantees generic events are never deleted or replaced.
"""

from __future__ import annotations

from typing import Optional

from .base import AgentAdapter
from .codex_v1 import CodexAdapterV1

__all__ = ["ADAPTER_REGISTRY", "AgentAdapter", "get_adapter"]

ADAPTER_REGISTRY: dict[str, type] = {
    "codex-v1": CodexAdapterV1,
}


def get_adapter(name: Optional[str]) -> Optional[AgentAdapter]:
    """Return an adapter by name; ``generic-acp`` uses base normalization only."""
    if name is None or name in {"none", "generic-acp"}:
        return None
    factory = ADAPTER_REGISTRY.get(name)
    if factory is None:
        raise KeyError(f"unknown adapter: {name!r}")
    return factory()
