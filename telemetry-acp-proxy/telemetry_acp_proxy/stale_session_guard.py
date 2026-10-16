"""Stop a chat whose research session is gone from reaching its agent.

An AI Chat process can outlive the study session it was launched for: the
plugin tears the session's runtime down (sign-out, erase, project close,
revocation) or a later activation replaces it, removing or rewriting the
one-time capability file, while the old proxy still holds its original token
and a spool endpoint that is closed or belongs to someone else. Without this
guard the agent would keep working with its telemetry silently lost.

The proxy launched with ``--capability-file`` checks that file before every
host chunk reaches the agent, and every few seconds in between (``check_now``).
While it still holds the token this process started with, nothing changes
(rotation keeps the token, so it never trips this). Once the file is missing or
holds another token the chat is stale for good:

* the host request that noticed it, and any later one, is answered with a
  JSON-RPC error telling the participant to start a new chat;
* nothing else from the host reaches the agent;
* ``on_stale`` runs once, and the proxy uses it to stop the agent. A turn
  already running is not left working unobserved, a cancel or permission reply
  it would need cannot hang, and a host that reuses the process for another
  chat has to start a fresh one.

The answered and withheld chunks still pass through the telemetry pipeline
like any other host frame.

An empty or unreadable file is re-read briefly before concluding, so a racing
rewrite of the same token is never mistaken for the end of the session.
"""

from __future__ import annotations

import secrets
import threading
import time
from pathlib import Path
from typing import Any, Callable, Mapping, Optional

from .framing import Frame, encode_message
from .observe import AcpDirection

__all__ = ["STALE_SESSION_CODE", "STALE_SESSION_MESSAGE", "StaleSessionGuard"]

STALE_SESSION_CODE = -32000
STALE_SESSION_MESSAGE = (
    "This chat's Code4Me research session has ended and the chat was stopped. "
    "Start a new chat to continue; the study agent is available while your study is active."
)

#: Re-reads of an empty or unreadable capability file before it counts as gone.
_UNCERTAIN_READ_ATTEMPTS = 3
_UNCERTAIN_READ_DELAY_SECONDS = 0.05


def _legal_jsonrpc_id(value: Any) -> bool:
    return isinstance(value, (str, int, float)) and not isinstance(value, bool)


class StaleSessionGuard:
    """Interceptor that refuses a stale chat's host traffic (see the module doc).

    ``on_stale`` runs once, outside the lock, when the chat first turns stale;
    it must be cheap and non-raising.
    """

    def __init__(
        self,
        capability_file: str,
        capability: str,
        *,
        on_stale: Optional[Callable[[], None]] = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._path = Path(capability_file)
        self._capability = capability
        self._on_stale = on_stale
        self._sleep = sleep
        self._lock = threading.Lock()
        self._stale = False
        self.refused = 0

    @property
    def stale(self) -> bool:
        with self._lock:
            return self._stale

    def _read(self) -> Optional[str]:
        """The file's token, ``""`` when it is empty/unreadable, ``None`` when missing."""
        try:
            return self._path.read_text(encoding="utf-8").strip()
        except FileNotFoundError:
            return None
        except OSError:
            return ""

    def _still_live(self) -> bool:
        for attempt in range(_UNCERTAIN_READ_ATTEMPTS):
            current = self._read()
            if current is None:
                return False
            if current:
                return secrets.compare_digest(current, self._capability)
            if attempt + 1 < _UNCERTAIN_READ_ATTEMPTS:
                self._sleep(_UNCERTAIN_READ_DELAY_SECONDS)
        return False

    def check_now(self) -> bool:
        """Whether the chat is stale, detecting it now if needed (``on_stale`` runs once)."""
        with self._lock:
            if self._stale:
                return True
        if self._still_live():
            return False
        with self._lock:
            first = not self._stale
            self._stale = True
        if first and self._on_stale is not None:
            self._on_stale()
        return True

    def intercept(
        self,
        direction: AcpDirection,
        chunk: bytes,
        frames: list[Frame],
        buffered_partial: int,
    ) -> Optional[bytes]:
        if direction != AcpDirection.HOST_TO_AGENT:
            return None
        if not self.check_now():
            return None
        answers = []
        for frame in frames:
            payload = frame.payload
            if (
                frame.parse_error is None
                and isinstance(payload, Mapping)
                and isinstance(payload.get("method"), str)
                and _legal_jsonrpc_id(payload.get("id"))
            ):
                answers.append(
                    encode_message(
                        {
                            "jsonrpc": "2.0",
                            "id": payload["id"],
                            "error": {"code": STALE_SESSION_CODE, "message": STALE_SESSION_MESSAGE},
                        },
                        framing=frame.framing,
                    )
                )
        with self._lock:
            self.refused += len(answers)
        # Empty bytes withhold the chunk entirely (nothing reaches the agent).
        return b"".join(answers)
