"""Agent artifact verification and child-process lifecycle.

The proxy launches only an exact bootstrap-pinned artifact, using an argument
array (never a shell string) and an explicit environment allowlist. It owns the
child's process group so a shutdown/revocation tears down the whole tree.
"""

from __future__ import annotations

import hashlib
import os
import signal
import subprocess
from enum import Enum
from pathlib import Path
from typing import Any, BinaryIO, Callable, Iterable, Mapping, Optional

__all__ = [
    "ArtifactVerificationError",
    "ProxyProcess",
    "ProxyState",
    "UnsafeAgentPathError",
    "build_environment",
    "normalize_digest",
    "sha256_file",
    "verify_artifact",
]

DIGEST_PREFIX = "sha256:"

# The child inherits only these environment variables (plus explicit overrides),
# so provider credentials in the proxy's environment never leak to the agent.
DEFAULT_ENV_ALLOWLIST = (
    "PATH",
    "HOME",
    "LANG",
    "LC_ALL",
    "TMPDIR",
    "TEMP",
    "TMP",
    "SYSTEMROOT",
    "COMSPEC",
    "PATHEXT",
    "USERPROFILE",
    "CODE4ME_BRIDGE_DIR",
    # Research attribution handed to a managed agent running under a study
    # (ISSUE-02). Not credentials: the ids are validated server-side against
    # the authorized account.
    "CODE4ME_RESEARCH_ENROLLMENT_ID",
    "CODE4ME_RESEARCH_SESSION_ID",
)

DEFAULT_TERMINATE_TIMEOUT_SECONDS = 5.0


class ArtifactVerificationError(RuntimeError):
    """Raised when the pinned agent artifact cannot be verified."""


class UnsafeAgentPathError(ArtifactVerificationError):
    """Raised when the agent path escapes its runtime root or contains ``..``."""


class ProxyState(str, Enum):
    """Lifecycle state of the launched agent process."""

    IDLE = "idle"
    STARTING = "starting"
    RUNNING = "running"
    STOPPING = "stopping"
    STOPPED = "stopped"
    FAILED = "failed"


def normalize_digest(value: str) -> str:
    """Strip an optional ``sha256:`` prefix from a digest."""
    text = (value or "").strip()
    return text[len(DIGEST_PREFIX) :] if text.startswith(DIGEST_PREFIX) else text


def sha256_file(path: Path) -> str:
    """Return the lowercase hex SHA-256 of a file."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_artifact(
    path: str | os.PathLike[str],
    expected_digest: str,
    *,
    root: Optional[str | os.PathLike[str]] = None,
) -> Path:
    """Verify the pinned artifact exists, is safe, and matches its digest.

    Returns the fully resolved path. Raises :class:`UnsafeAgentPathError` for a
    traversal/escape and :class:`ArtifactVerificationError` for a missing file or
    digest mismatch. It never falls back to another agent or version.
    """
    raw = Path(path)
    if ".." in raw.parts:
        raise UnsafeAgentPathError(f"agent path contains '..': {raw}")

    resolved = raw.expanduser().resolve()
    if root is not None:
        root_resolved = Path(root).expanduser().resolve()
        if resolved != root_resolved and not str(resolved).startswith(
            str(root_resolved) + os.sep
        ):
            raise UnsafeAgentPathError(
                f"agent path {resolved} escapes runtime root {root_resolved}"
            )

    if not resolved.is_file():
        raise ArtifactVerificationError(f"agent artifact not found: {resolved}")

    actual = sha256_file(resolved)
    if actual != normalize_digest(expected_digest):
        raise ArtifactVerificationError(
            "agent artifact digest mismatch: expected "
            f"{normalize_digest(expected_digest)} but resolved {actual}"
        )
    return resolved


def build_environment(
    allowlist: Iterable[str] = DEFAULT_ENV_ALLOWLIST,
    *,
    source: Optional[Mapping[str, str]] = None,
    overrides: Optional[Mapping[str, str]] = None,
) -> dict[str, str]:
    """Build an explicit, allowlisted child environment."""
    origin = source if source is not None else os.environ
    environment = {name: origin[name] for name in allowlist if name in origin}
    if overrides:
        environment.update(overrides)
    return environment


class ProxyProcess:
    """Owns the pinned agent child process and its process group."""

    def __init__(
        self,
        argv: Iterable[str],
        *,
        working_directory: Optional[str | os.PathLike[str]] = None,
        env_allowlist: Iterable[str] = DEFAULT_ENV_ALLOWLIST,
        env_overrides: Optional[Mapping[str, str]] = None,
        popen_factory: Callable[..., Any] = subprocess.Popen,
    ) -> None:
        self.argv = list(argv)
        if not self.argv:
            raise ValueError("argv must not be empty")
        self.working_directory = working_directory
        self.env_allowlist = tuple(env_allowlist)
        self.env_overrides = dict(env_overrides or {})
        self._popen_factory = popen_factory
        self._process: Optional[Any] = None
        self.state = ProxyState.IDLE

    @property
    def pid(self) -> Optional[int]:
        """The child pid, or ``None`` before start."""
        return self._process.pid if self._process is not None else None

    @property
    def stdin(self) -> Optional[BinaryIO]:
        """Writable child stdin pipe, or ``None``."""
        return self._process.stdin if self._process is not None else None

    @property
    def stdout(self) -> Optional[BinaryIO]:
        """Readable child stdout pipe, or ``None``."""
        return self._process.stdout if self._process is not None else None

    @property
    def returncode(self) -> Optional[int]:
        """The child return code, or ``None`` while running."""
        return self._process.poll() if self._process is not None else None

    def start(self) -> Any:
        """Launch the child with an argv array and an allowlisted environment."""
        environment = build_environment(
            self.env_allowlist, overrides=self.env_overrides
        )
        self.state = ProxyState.STARTING
        try:
            self._process = self._popen_factory(
                self.argv,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=None,
                cwd=str(self.working_directory)
                if self.working_directory is not None
                else None,
                env=environment,
                shell=False,
                start_new_session=(os.name == "posix"),
            )
        except OSError:
            self.state = ProxyState.FAILED
            raise
        self.state = ProxyState.RUNNING
        return self._process

    def wait(self, timeout: Optional[float] = None) -> Optional[int]:
        """Wait for the child to exit, returning its return code."""
        if self._process is None:
            return None
        code = self._process.wait(timeout=timeout)
        self.state = ProxyState.STOPPED
        return code

    def terminate(self, timeout: float = DEFAULT_TERMINATE_TIMEOUT_SECONDS) -> None:
        """Terminate the child process group, escalating to SIGKILL."""
        if self._process is None:
            return
        if self._process.poll() is not None:
            self.state = ProxyState.STOPPED
            return
        self.state = ProxyState.STOPPING
        try:
            if os.name == "posix":
                os.killpg(os.getpgid(self._process.pid), signal.SIGTERM)
            else:
                self._process.terminate()
        except (ProcessLookupError, PermissionError, OSError):
            pass
        try:
            self._process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                if os.name == "posix":
                    os.killpg(os.getpgid(self._process.pid), signal.SIGKILL)
                else:
                    self._process.kill()
            except (ProcessLookupError, PermissionError, OSError):
                pass
            try:
                self._process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                pass
        self.state = ProxyState.STOPPED
