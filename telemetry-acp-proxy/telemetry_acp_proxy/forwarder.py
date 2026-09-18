"""Bidirectional, byte-preserving ACP forwarding pump.

Every complete original frame is written to the other side immediately and
verbatim. Diagnostics go to a stderr sink only; **stdout carries protocol frames
and nothing else**.

Closing semantics:

* when the child closes its stdout, the host side is closed (EOF);
* when the host closes its stdin, the child's stdin is closed, and if the child
  does not exit on its own the caller's ``terminate_agent`` callback is invoked
  so the child (and its process tree) is torn down.
"""

from __future__ import annotations

import sys
import threading
from typing import BinaryIO, Callable, Optional

from .framing import Frame, FrameReader
from .observe import AcpDirection, Observer

__all__ = ["AcpForwarder", "ForwardResult"]

DEFAULT_BUFFER_SIZE = 64 * 1024
SELF_EXIT_GRACE_SECONDS = 5.0

FrameCallback = Callable[[AcpDirection, bytes, list[Frame]], None]


def read_available(stream: BinaryIO, size: int) -> bytes:
    """Read up to ``size`` bytes without waiting for the buffer to fill.

    ACP is interactive: the host writes one small JSON-RPC frame (for example
    ``initialize``) and then waits for the response while keeping the pipe open.
    ``BufferedReader.read(size)`` blocks until *size* bytes are available or EOF,
    so with an open pipe it never returns and the frame is never forwarded: the
    host hangs forever on "starting agent".

    ``read1`` performs at most one raw read, so whatever has arrived is returned
    immediately. Plain/raw streams (which have no ``read1``) fall back to
    ``read``.
    """
    read1 = getattr(stream, "read1", None)
    if callable(read1):
        return read1(size)
    return stream.read(size)


class ForwardResult:
    """Outcome of one forwarding run."""

    def __init__(
        self,
        *,
        host_to_agent_frames: int,
        agent_to_host_frames: int,
        host_closed_stdin: bool,
        agent_exited: bool,
    ) -> None:
        self.host_to_agent_frames = host_to_agent_frames
        self.agent_to_host_frames = agent_to_host_frames
        self.host_closed_stdin = host_closed_stdin
        self.agent_exited = agent_exited


class AcpForwarder:
    """Pumps bytes between the host and the agent while observing frames."""

    def __init__(
        self,
        *,
        observer: Observer,
        diagnostics: Optional[Callable[[str], None]] = None,
        buffer_size: int = DEFAULT_BUFFER_SIZE,
        on_frame: Optional[FrameCallback] = None,
    ) -> None:
        self.observer = observer
        self.buffer_size = buffer_size
        self.on_frame = on_frame
        self._diagnostics = diagnostics or (lambda message: print(message, file=sys.stderr))

    def _emit(self, message: str) -> None:
        self._diagnostics(message)

    def run(
        self,
        host_read: BinaryIO,
        host_write: BinaryIO,
        agent_read: BinaryIO,
        agent_write: BinaryIO,
        *,
        terminate_agent: Optional[Callable[[], None]] = None,
        grace_seconds: float = SELF_EXIT_GRACE_SECONDS,
    ) -> ForwardResult:
        """Forward until either side closes; then apply closing semantics."""
        counters = {"host_to_agent": 0, "agent_to_host": 0}
        host_eof = threading.Event()
        agent_eof = threading.Event()

        def _pump(
            reader_stream: BinaryIO,
            writer_stream: BinaryIO,
            direction: AcpDirection,
            closed_event: threading.Event,
        ) -> None:
            reader = FrameReader()
            try:
                while True:
                    chunk = read_available(reader_stream, self.buffer_size)
                    if not chunk:
                        break
                    frames = reader.feed(chunk)
                    if frames:
                        counters[direction.value] += len(frames)
                        if self.on_frame is not None:
                            self.on_frame(direction, chunk, frames)
                    # Forward first, observe second: the peer must never wait on
                    # telemetry. Observation can do real I/O (canonicalization,
                    # spool POST + fsync), so it is kept off the critical path of
                    # the protocol. Bytes are written verbatim either way.
                    writer_stream.write(chunk)
                    writer_stream.flush()
                    self.observer.observe(direction, chunk)
            except (BrokenPipeError, OSError, ValueError) as error:
                self._emit(f"proxy: {direction.value} stream closed: {error}")
            finally:
                try:
                    writer_stream.close()
                except OSError:
                    pass
                closed_event.set()

        host_thread = threading.Thread(
            target=_pump,
            args=(host_read, agent_write, AcpDirection.HOST_TO_AGENT, host_eof),
            name="proxy-host-to-agent",
            daemon=True,
        )
        agent_thread = threading.Thread(
            target=_pump,
            args=(agent_read, host_write, AcpDirection.AGENT_TO_HOST, agent_eof),
            name="proxy-agent-to-host",
            daemon=True,
        )

        agent_thread.start()
        host_thread.start()

        host_thread.join()
        # Host stdin closed: give the child a chance to exit on its own, then
        # terminate its process tree so the host side sees EOF.
        agent_exited = agent_eof.wait(timeout=grace_seconds)
        if not agent_exited and terminate_agent is not None:
            self._emit("proxy: terminating agent after host stdin closed")
            terminate_agent()
            agent_exited = agent_eof.wait(timeout=grace_seconds)
        agent_thread.join(timeout=grace_seconds)

        # Drain any trailing partial frames from both directions.
        for observation in self.observer.close():
            if not observation.ok:
                self._emit(
                    "proxy: trailing partial frame "
                    f"({observation.parse_error or 'unknown'})"
                )

        return ForwardResult(
            host_to_agent_frames=counters["host_to_agent"],
            agent_to_host_frames=counters["agent_to_host"],
            host_closed_stdin=host_eof.is_set(),
            agent_exited=agent_exited,
        )
