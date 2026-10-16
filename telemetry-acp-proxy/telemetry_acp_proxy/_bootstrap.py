"""Make the shared research contracts importable without installing the server.

The proxy reuses the canonical contract from the server tree
(``code4me2-server/src/research/telemetry``) rather than duplicating it. This is
the **single, documented bootstrap point** that adds the server ``src``
directory to ``sys.path`` only when the import does not already resolve.

Resolution order:

1. an explicit ``TELEMETRY_PROXY_SERVER_SRC`` environment variable;
2. the sibling ``code4me2-server/src`` directory relative to this checkout;
3. leave ``sys.path`` untouched (the caller is expected to provide it).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Optional

__all__ = ["ensure_research_on_path", "server_src_candidates"]

_ENV_VAR = "TELEMETRY_PROXY_SERVER_SRC"


def server_src_candidates() -> list[Path]:
    """Return the candidate ``code4me2-server/src`` directories, best first."""
    candidates: list[Path] = []
    override = os.environ.get(_ENV_VAR)
    if override:
        candidates.append(Path(override).expanduser())
    # code4me2/dev/telemetry-acp-proxy/telemetry_acp_proxy/_bootstrap.py
    # parents[2] = dev, parents[3] = code4me2, parents[4] = repository root.
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "code4me2-server" / "src"
        if candidate not in candidates:
            candidates.append(candidate)
    return candidates


def ensure_research_on_path() -> Optional[Path]:
    """Insert the server ``src`` into ``sys.path`` if ``research`` is missing.

    Returns the path that was inserted, or ``None`` when no change was needed.
    """
    try:
        import research.telemetry  # noqa: F401

        return None
    except ModuleNotFoundError:
        pass

    for candidate in server_src_candidates():
        if (candidate / "research" / "telemetry").is_dir():
            text = str(candidate)
            if text not in sys.path:
                sys.path.insert(0, text)
            return candidate
    return None
