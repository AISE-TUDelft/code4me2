"""Standalone, versioned, transparent ACP stdio telemetry proxy (Issue 08).

The proxy sits between an ACP host (IntelliJ) and the real agent process,
forwarding JSON-RPC frames byte-for-byte while emitting privacy-filtered
:class:`CanonicalEventV1` observations. Run it with
``python -m telemetry_acp_proxy.main``.

The shared canonical contract lives in the server tree; :mod:`._bootstrap` is
the single, documented point that makes ``research.*`` importable without
installing the server.
"""

from __future__ import annotations

from ._bootstrap import ensure_research_on_path

ensure_research_on_path()

from .adapters import get_adapter  # noqa: E402
from .forwarder import AcpForwarder, ForwardResult  # noqa: E402
from .framing import Frame, FrameReader, iter_frames  # noqa: E402
from .lifecycle import (  # noqa: E402
    ArtifactVerificationError,
    ProxyProcess,
    ProxyState,
    UnsafeAgentPathError,
    verify_artifact,
)
from .normalize import ProxyNormalizer, build_capability_snapshot  # noqa: E402
from .observe import (  # noqa: E402
    AcpDirection,
    ObservedAcpMessageV1,
    Observer,
)
from .privacy_gate import PrivacyGate  # noqa: E402
from .spool_client import (  # noqa: E402
    CapabilityAlreadyConsumed,
    LocalSpoolClient,
    OneTimeCapability,
    SpoolSendResult,
)

__version__ = "1.0.0"
PROXY_VERSION = __version__

__all__ = [
    "PROXY_VERSION",
    "__version__",
    "AcpDirection",
    "AcpForwarder",
    "ArtifactVerificationError",
    "CapabilityAlreadyConsumed",
    "ForwardResult",
    "Frame",
    "FrameReader",
    "LocalSpoolClient",
    "ObservedAcpMessageV1",
    "Observer",
    "OneTimeCapability",
    "PrivacyGate",
    "ProxyNormalizer",
    "ProxyProcess",
    "ProxyState",
    "SpoolSendResult",
    "UnsafeAgentPathError",
    "build_capability_snapshot",
    "ensure_research_on_path",
    "get_adapter",
    "iter_frames",
    "verify_artifact",
]
