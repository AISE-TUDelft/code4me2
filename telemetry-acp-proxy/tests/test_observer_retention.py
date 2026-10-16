"""ISSUE-14: observer retention is bounded and parsed payloads are released."""

from __future__ import annotations

from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.observe import DEFAULT_RECORD_CAP, AcpDirection, Observer

CANARY = "CANARY-RETENTION-PAYLOAD"


def _prompt_frame(index: int) -> bytes:
    return encode_message(
        {
            "jsonrpc": "2.0",
            "id": index,
            "method": "session/prompt",
            "params": {
                "sessionId": "sess-retention",
                "prompt": [
                    {"type": "text", "text": f"{CANARY}-{index}-" + "x" * 4096}
                ],
            },
        }
    )


def test_records_are_bounded_and_release_payloads_after_callback():
    calls: list[tuple[str, int]] = []

    def callback(observation) -> None:
        calls.append((observation.method, observation.size))

    observer = Observer(on_observation=callback, record_cap=DEFAULT_RECORD_CAP)
    for index in range(1000):
        observer.observe(AcpDirection.HOST_TO_AGENT, _prompt_frame(index))

    assert len(calls) == 1000
    assert len(observer.records) == DEFAULT_RECORD_CAP
    assert len(observer.records) <= observer.record_cap
    # Retained records are metadata copies: the parsed payload is gone.
    assert all(record.payload is None for record in observer.records)
    # No prompt text can be recovered from the retained history.
    assert CANARY not in repr(list(observer.records))


def test_callback_still_sees_the_transient_parsed_payload():
    payloads: list[object] = []

    observer = Observer(
        on_observation=lambda observation: payloads.append(observation.payload)
    )
    observer.observe(AcpDirection.HOST_TO_AGENT, _prompt_frame(1))

    assert len(payloads) == 1
    assert payloads[0] is not None
    assert CANARY in repr(payloads[0])


def test_malformed_count_survives_record_pruning():
    observer = Observer(record_cap=4)
    observer.observe(AcpDirection.AGENT_TO_HOST, b"not valid json\n")
    for index in range(100):
        observer.observe(AcpDirection.HOST_TO_AGENT, _prompt_frame(index))

    # The malformed record has been pruned from the ring...
    assert len(observer.records) == 4
    assert all(record.parse_status == "ok" for record in observer.records)
    # ...but the explicit counters still carry it.
    assert observer.malformed_count == 1
    assert observer.parse_status_counts == {"ok": 100, "error": 1}


def test_production_observer_is_bounded_and_capture_is_explicit():
    production = Observer()
    assert production.capture_transcripts is False
    assert production.records.maxlen == production.record_cap

    capturing = Observer(capture_transcripts=True)
    for index in range(3):
        capturing.observe(AcpDirection.HOST_TO_AGENT, _prompt_frame(index))
    assert len(capturing.records) == 3
    assert all(record.payload is not None for record in capturing.records)


def test_record_cap_must_be_positive():
    import pytest

    with pytest.raises(ValueError):
        Observer(record_cap=0)
