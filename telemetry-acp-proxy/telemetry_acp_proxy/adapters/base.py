"""Adapter contract (re-export of the shared ``AgentAdapter``).

An adapter is enrichment only: it may add labels to a generic candidate but can
never delete or replace a generic event, nor change its source, id or fidelity.
"""

from __future__ import annotations

from research.telemetry.normalization.generic_acp import AgentAdapter

__all__ = ["AgentAdapter"]
