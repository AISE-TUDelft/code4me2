"""Bounded, non-blocking telemetry delivery for the ACP proxy.

The forwarding pumps must never wait on telemetry transport: a slow or
unavailable spool would otherwise stall agent streaming and contaminate the
latency measurements the study is collecting. Producers normalize and
privacy-filter an observation and hand only the resulting envelope to
:meth:`DeliveryQueue.enqueue`, which never blocks (``put_nowait``). A single
daemon consumer thread performs the network I/O and the client's bounded
retries.

Queue policies are explicit and measurable:

* **capacity** - ``queue.Queue(maxsize=capacity)``; a full queue drops the item
  and increments ``dropped_full`` (forwarding never blocks and memory stays
  bounded);
* **delivery errors** - an exception from the deliver callable increments
  ``dropped_error`` and the worker keeps going;
* **shutdown** - :meth:`DeliveryQueue.close` flushes within a bounded timeout
  and counts anything not delivered as ``dropped_shutdown``.

Raw ACP chunks and unbounded event batches must never reach this queue: only
normalized, privacy-filtered envelopes (:class:`DeliveryBatch`) are enqueued.
"""

from __future__ import annotations

import queue
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Generic, Optional, TypeVar

__all__ = [
    "DEFAULT_CAPACITY",
    "DEFAULT_CLOSE_TIMEOUT_SECONDS",
    "DeliveryBatch",
    "DeliveryQueue",
]

#: Default number of delivery units the queue holds before dropping.
DEFAULT_CAPACITY = 256

#: Default upper bound for flushing queued telemetry during shutdown.
DEFAULT_CLOSE_TIMEOUT_SECONDS = 5.0

#: How often the consumer wakes to notice a close request while idle.
_CONSUMER_POLL_SECONDS = 0.05

ItemT = TypeVar("ItemT")


@dataclass(frozen=True)
class DeliveryBatch:
    """One normalized, privacy-filtered unit of telemetry delivery.

    ``events`` are canonical events that already passed the privacy gate - raw
    ACP chunks are never represented here. ``direction`` and ``session_id`` are
    attribution metadata carried alongside the events so entries stay
    attributable after leaving the forwarding thread.
    """

    events: tuple[Any, ...]
    direction: Optional[str] = None
    session_id: Optional[str] = None

    def __len__(self) -> int:
        return len(self.events)


class DeliveryQueue(Generic[ItemT]):
    """Bounded handoff to one daemon delivery worker.

    ``deliver`` runs on the worker thread and may block (network I/O, retries);
    :meth:`enqueue` runs on the forwarding thread and never blocks.
    """

    def __init__(
        self,
        deliver: Callable[[ItemT], Any],
        *,
        capacity: int = DEFAULT_CAPACITY,
        diagnostics: Optional[Callable[[str], None]] = None,
        name: str = "telemetry-delivery",
    ) -> None:
        if capacity < 1:
            raise ValueError("capacity must be >= 1")
        self.capacity = capacity
        self._deliver = deliver
        self._diagnostics = diagnostics
        self._queue: queue.Queue[ItemT] = queue.Queue(maxsize=capacity)
        self._lock = threading.Lock()
        self._closed = False
        self._in_flight = 0
        self.enqueued = 0
        self.delivered = 0
        self.dropped_full = 0
        self.dropped_error = 0
        self.dropped_shutdown = 0
        self._thread = threading.Thread(target=self._consume, name=name, daemon=True)
        self._thread.start()

    # -- producer side ------------------------------------------------------

    def enqueue(self, item: ItemT) -> bool:
        """Hand off ``item`` without blocking; returns False when dropped."""
        with self._lock:
            if self._closed:
                self.dropped_shutdown += 1
                return False
        try:
            self._queue.put_nowait(item)
        except queue.Full:
            with self._lock:
                self.dropped_full += 1
                first_drop = self.dropped_full == 1
            if first_drop:
                self._diagnose(
                    "proxy: delivery queue saturated "
                    f"(capacity={self.capacity}); dropping telemetry, forwarding continues"
                )
            return False
        with self._lock:
            self.enqueued += 1
        return True

    # -- worker side --------------------------------------------------------

    def _consume(self) -> None:
        while True:
            with self._lock:
                stopping = self._closed
            # Drain what is already queued before honoring the stop request.
            if stopping and self._queue.empty():
                return
            try:
                item = self._queue.get(timeout=_CONSUMER_POLL_SECONDS)
            except queue.Empty:
                continue
            with self._lock:
                self._in_flight += 1
            try:
                self._deliver(item)
            except Exception as error:  # noqa: BLE001 - a failure must not kill the worker
                with self._lock:
                    self.dropped_error += 1
                self._diagnose(f"proxy: telemetry delivery failed: {error}")
            else:
                with self._lock:
                    self.delivered += 1
            finally:
                with self._lock:
                    self._in_flight -= 1
                self._queue.task_done()

    # -- lifecycle / health -------------------------------------------------

    def close(self, timeout: float = DEFAULT_CLOSE_TIMEOUT_SECONDS) -> dict[str, Any]:
        """Stop the worker, flushing what can be delivered within ``timeout``.

        Items still queued when the bound expires are released and counted as
        ``dropped_shutdown``. An item already being delivered when the bound
        expires is not interrupted (it may still complete and count as
        delivered); it is visible as ``pending`` in the returned snapshot.
        """
        with self._lock:
            self._closed = True
        deadline = time.monotonic() + max(0.0, timeout)
        self._thread.join(timeout=max(0.0, deadline - time.monotonic()))
        drained = 0
        while True:
            try:
                self._queue.get_nowait()
            except queue.Empty:
                break
            drained += 1
            self._queue.task_done()
        if drained:
            with self._lock:
                self.dropped_shutdown += drained
        return self.snapshot()

    def snapshot(self) -> dict[str, Any]:
        """Return a consistent health/coverage dict for logging."""
        with self._lock:
            enqueued = self.enqueued
            delivered = self.delivered
            dropped_full = self.dropped_full
            dropped_error = self.dropped_error
            dropped_shutdown = self.dropped_shutdown
            in_flight = self._in_flight
            closed = self._closed
        dropped = dropped_full + dropped_error + dropped_shutdown
        return {
            "capacity": self.capacity,
            "enqueued": enqueued,
            "delivered": delivered,
            "dropped_full": dropped_full,
            "dropped_error": dropped_error,
            "dropped_shutdown": dropped_shutdown,
            "dropped": dropped,
            "pending": self._queue.qsize() + in_flight,
            "closed": closed,
            "worker_alive": self._thread.is_alive(),
            "healthy": dropped == 0,
        }

    # -- internals ----------------------------------------------------------

    def _diagnose(self, message: str) -> None:
        if self._diagnostics is not None:
            self._diagnostics(message)
