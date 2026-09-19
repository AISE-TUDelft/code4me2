"""ISSUE-15: bounded delivery queue keeps telemetry off the forwarding path."""

from __future__ import annotations

import threading
import time

from telemetry_acp_proxy.delivery import DeliveryBatch, DeliveryQueue
from telemetry_acp_proxy.forwarder import AcpForwarder
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.normalize import ProxyNormalizer
from telemetry_acp_proxy.observe import AcpDirection, Observer
from telemetry_acp_proxy.privacy_gate import PrivacyGate


def _initialize_frame(request_id: int) -> bytes:
    return encode_message(
        {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "initialize",
            "params": {"protocolVersion": 1},
        }
    )


class _ChunkStream:
    """An interactive stream (``read1``) yielding scripted chunks, then EOF."""

    def __init__(self, chunks: list[bytes]) -> None:
        self._chunks = list(chunks)

    def read1(self, _size: int) -> bytes:
        return self._chunks.pop(0) if self._chunks else b""


class _RecordingSink:
    """Binary sink that records forwarded bytes and ignores close."""

    def __init__(self) -> None:
        self.data = bytearray()

    def write(self, chunk: bytes) -> int:
        self.data.extend(chunk)
        return len(chunk)

    def flush(self) -> None:
        return None

    def close(self) -> None:
        return None


def _wait_until(predicate, timeout: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.005)
    return predicate()


def test_slow_delivery_transport_does_not_block_forwarding():
    release = threading.Event()
    delivered: list[DeliveryBatch] = []

    def slow_transport(batch: DeliveryBatch) -> None:
        # Stands in for a spool POST with retries: the worker blocks, the pump
        # must not.
        release.wait(timeout=5.0)
        delivered.append(batch)

    delivery = DeliveryQueue(slow_transport, capacity=8)
    observer = Observer(
        on_observation=lambda observation: delivery.enqueue(
            DeliveryBatch(events=(), direction=observation.direction.value)
        )
    )
    forwarder = AcpForwarder(observer=observer)

    first, second = _initialize_frame(1), _initialize_frame(2)
    agent_out = _RecordingSink()
    started = time.monotonic()
    result = forwarder.run(
        _ChunkStream([first, second]),
        _RecordingSink(),
        _ChunkStream([]),
        agent_out,
    )
    elapsed = time.monotonic() - started

    # Both chunks were forwarded and enqueued while the transport was blocked.
    assert elapsed < 1.0
    assert result.host_to_agent_frames == 2
    assert bytes(agent_out.data) == first + second
    snapshot = delivery.snapshot()
    assert snapshot["enqueued"] == 2
    assert snapshot["delivered"] == 0

    release.set()
    assert _wait_until(lambda: delivery.snapshot()["delivered"] == 2)
    final = delivery.close(timeout=1.0)
    assert final["delivered"] == 2
    assert final["dropped"] == 0


def test_burst_over_capacity_drops_full_and_bounds_memory():
    release = threading.Event()
    diagnostics: list[str] = []

    def blocking_transport(_item: int) -> None:
        release.wait(timeout=5.0)

    delivery = DeliveryQueue(
        blocking_transport, capacity=8, diagnostics=diagnostics.append
    )
    for index in range(200):
        delivery.enqueue(index)

    snapshot = delivery.snapshot()
    # The queue itself never holds more than its capacity (plus the one item
    # the worker already took), regardless of the burst size.
    assert snapshot["pending"] <= delivery.capacity + 1
    assert snapshot["enqueued"] <= delivery.capacity + 1
    assert snapshot["dropped_full"] == 200 - snapshot["enqueued"]
    assert snapshot["dropped_full"] >= 190
    assert snapshot["delivered"] == 0
    assert any("saturated" in message for message in diagnostics)

    release.set()
    assert _wait_until(
        lambda: delivery.snapshot()["delivered"] == snapshot["enqueued"]
    )
    final = delivery.close(timeout=1.0)
    assert final["delivered"] == snapshot["enqueued"]
    assert final["healthy"] is False


def test_close_flushes_everything_within_the_bound():
    delivered: list[int] = []
    delivery = DeliveryQueue(delivered.append, capacity=64)
    for index in range(25):
        assert delivery.enqueue(index) is True

    snapshot = delivery.close(timeout=2.0)

    assert delivered == list(range(25))
    assert snapshot["delivered"] == 25
    assert snapshot["dropped"] == 0
    assert snapshot["dropped_shutdown"] == 0
    assert snapshot["pending"] == 0
    assert snapshot["healthy"] is True


def test_close_counts_undelivered_items_within_the_bound():
    release = threading.Event()
    delivery = DeliveryQueue(
        lambda _item: release.wait(timeout=5.0), capacity=8
    )
    for index in range(5):
        delivery.enqueue(index)

    started = time.monotonic()
    snapshot = delivery.close(timeout=0.1)
    elapsed = time.monotonic() - started
    release.set()

    assert elapsed < 1.0
    assert snapshot["dropped_shutdown"] >= 1
    # Bounded loss accounting: everything is delivered, still in flight, or
    # explicitly counted as dropped when the shutdown bound expired.
    assert (
        snapshot["delivered"] + snapshot["dropped_shutdown"] + snapshot["pending"]
        == snapshot["enqueued"]
    )


def test_session_and_direction_metadata_survive_the_queue():
    observer = Observer()
    normalizer = ProxyNormalizer()
    gate = PrivacyGate()

    enqueued: list[DeliveryBatch] = []
    batches: list[DeliveryBatch] = []
    delivery = DeliveryQueue(lambda batch: batches.append(batch), capacity=8)

    for direction, message in (
        (
            AcpDirection.HOST_TO_AGENT,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "session/prompt",
                "params": {
                    "sessionId": "sess-attribution",
                    "prompt": [{"type": "text", "text": "hi"}],
                },
            },
        ),
        (
            AcpDirection.AGENT_TO_HOST,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "result": {"stopReason": "end_turn"},
            },
        ),
    ):
        for observed in observer.observe(direction, encode_message(message)):
            events = tuple(
                gate.filter(event)
                for event in normalizer.normalize_observed(observed)
            )
            if not events:
                continue
            session_id = next(
                (
                    event.payload.get("session_id")
                    for event in events
                    if "session_id" in event.payload
                ),
                None,
            )
            batch = DeliveryBatch(
                events=events,
                direction=observed.direction.value,
                session_id=session_id,
            )
            enqueued.append(batch)
            delivery.enqueue(batch)

    assert _wait_until(lambda: len(batches) == 2)
    delivery.close(timeout=1.0)

    # Same envelopes, same order, nothing rewritten on the worker thread.
    assert batches == enqueued
    host_batch, agent_batch = batches
    assert host_batch.direction == "host_to_agent"
    assert agent_batch.direction == "agent_to_host"
    assert host_batch.session_id == agent_batch.session_id == "sess-attribution"
    assert host_batch.events[0].payload["session_id"] == "sess-attribution"


def test_delivery_log_reports_loss_and_metrics():
    from telemetry_acp_proxy import main as proxy_main

    lines: list[str] = []
    proxy_main._log_delivery(
        {
            "enqueued": 10,
            "delivered": 7,
            "dropped": 3,
            "dropped_full": 2,
            "dropped_error": 0,
            "dropped_shutdown": 1,
            "pending": 0,
        },
        lines.append,
    )

    assert any("coverage loss" in line and "dropped=3" in line for line in lines)
    assert any(
        "enqueued=10" in line and "delivered=7" in line for line in lines
    )
