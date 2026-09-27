"""Bidirectional, byte-preserving ACP forwarding pump.

Every complete original frame is written to the other side immediately and
verbatim. Diagnostics go to a stderr sink only; **stdout carries protocol frames
and nothing else**.

Forwarding never waits on telemetry: each chunk is written and flushed first,
then handed to a non-blocking delivery sink (by default ``observer.observe``,
whose callback only normalizes and enqueues). The spool POST and its retries run
on the delivery worker thread, so a degraded spool cannot stall ACP streaming.

An optional ``intercept`` compatibility hook is consulted after a chunk is
parsed (and observed through ``on_frame``) but before the forwarding decision:
when it returns bytes for a HOST_TO_AGENT chunk, those bytes are written to the
host stream instead and the chunk never reaches the agent. The intercepted chunk
and the synthesized response both travel the normal telemetry path. With no
interceptor configured the pump is exactly the original byte-preserving forward.

Closing semantics:

* when the child closes its stdout, the host side is closed (EOF);
* when the host closes its stdin, the child's stdin is closed, and if the child
  does not exit on its own the caller's ``terminate_agent`` callback is invoked
  so the child (and its process tree) is torn down.
"""

from __future__ import annotations

import contextlib
import sys
import threading
from typing import Any, BinaryIO, Callable, Optional

from .framing import Frame, FrameReader
from .observe import AcpDirection, Observer

__all__ = ["AcpForwarder", "ForwardResult", "InterceptCallback"]

DEFAULT_BUFFER_SIZE = 64 * 1024
SELF_EXIT_GRACE_SECONDS = 5.0

FrameCallback = Callable[[AcpDirection, bytes, list[Frame]], None]
#: Handoff for one observed chunk. Must not block: production wires it to
#: ``Observer.observe`` whose callback only normalizes and enqueues. Network
#: I/O and retries happen on the delivery worker thread.
DeliveryCallback = Callable[[AcpDirection, bytes], Any]
#: Optional compatibility hook consulted for every observed chunk. It receives
#: the direction, the raw chunk, the frames decoded from it, and the reader's
#: ``buffered_bytes`` after the feed. Returning bytes for a HOST_TO_AGENT chunk
#: answers the chunk without forwarding it to the agent: the bytes are written
#: to the host instead. ``None`` (or bytes for the other direction) preserves
#: the byte-preserving forward exactly as before. Must be cheap and must not
#: block: it runs on the forwarding pump thread.
InterceptCallback = Callable[[AcpDirection, bytes, list[Frame], int], Optional[bytes]]


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
    """Pumps bytes between the host and the agent while observing frames.

    ``delivery`` is the test-visible seam for the observation sink. It defaults
    to ``observer.observe`` and must be non-blocking; the pump forwards the next
    chunk without waiting for telemetry transport.

    ``intercept`` is the optional compatibility seam (see
    [InterceptCallback]). With no interceptor configured the forwarding path is
    exactly the original byte-preserving pump.
    """

    def __init__(
        self,
        *,
        observer: Observer,
        delivery: Optional[DeliveryCallback] = None,
        diagnostics: Optional[Callable[[str], None]] = None,
        buffer_size: int = DEFAULT_BUFFER_SIZE,
        on_frame: Optional[FrameCallback] = None,
        intercept: Optional[InterceptCallback] = None,
    ) -> None:
        self.observer = observer
        self.delivery: DeliveryCallback = delivery or observer.observe
        self.buffer_size = buffer_size
        self.on_frame = on_frame
        self.intercept = intercept
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
        # The host->agent pump can now write the host stream (a replayed
        # response), which the agent->host pump also writes: one lock guards
        # every write+flush to the host so frames can never interleave.
        host_write_lock = threading.Lock()
        # Frame counters are read-modify-written by both pumps; keep them exact.
        counters_lock = threading.Lock()
        # A replayed response is observed as AGENT_TO_HOST traffic from the
        # host pump thread, while the agent pump delivers real agent frames:
        # the observer's per-direction frame reader is not thread-safe.
        agent_observation_lock = threading.Lock()

        def _count(direction: AcpDirection, frames: list[Frame]) -> None:
            with counters_lock:
                counters[direction.value] += len(frames)

        def _deliver_replay(replacement: bytes) -> None:
            """Write a synthesized response to the host and observe it.

            The bytes travel the same telemetry path as a real AGENT_TO_HOST
            chunk (``on_frame`` when set, then ``delivery``), so normalization
            still sees the response the host actually received.
            """
            try:
                with host_write_lock:
                    host_write.write(replacement)
                    host_write.flush()
            except (BrokenPipeError, OSError, ValueError) as error:
                # A dead host stream must not take the forwarding pump down.
                self._emit(f"proxy: {AcpDirection.AGENT_TO_HOST.value} stream closed: {error}")
                return
            frames = FrameReader().feed(replacement)
            if frames:
                _count(AcpDirection.AGENT_TO_HOST, frames)
            with agent_observation_lock:
                if frames and self.on_frame is not None:
                    self.on_frame(AcpDirection.AGENT_TO_HOST, replacement, frames)
                self.delivery(AcpDirection.AGENT_TO_HOST, replacement)

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
                    observation_guard = (
                        agent_observation_lock
                        if direction is AcpDirection.AGENT_TO_HOST
                        else contextlib.nullcontext()
                    )
                    if frames:
                        _count(direction, frames)
                        if self.on_frame is not None:
                            with observation_guard:
                                self.on_frame(direction, chunk, frames)
                    if self.intercept is not None:
                        replacement = self.intercept(
                            direction, chunk, frames, reader.buffered_bytes
                        )
                        if direction is AcpDirection.HOST_TO_AGENT and replacement is not None:
                            # The intercepted chunk is still observed, but it is
                            # never forwarded: the agent only ever sees the first
                            # handshake, and the host receives the cached answer.
                            self.delivery(direction, chunk)
                            _deliver_replay(replacement)
                            continue
                    # Forward first, deliver second: the peer must never wait on
                    # telemetry. Delivery must be non-blocking (normalize +
                    # enqueue); the spool POST + retries happen only on the
                    # delivery worker thread.
                    guard = (
                        host_write_lock
                        if direction is AcpDirection.AGENT_TO_HOST
                        else contextlib.nullcontext()
                    )
                    with guard:
                        writer_stream.write(chunk)
                        writer_stream.flush()
                    with observation_guard:
                        self.delivery(direction, chunk)
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
