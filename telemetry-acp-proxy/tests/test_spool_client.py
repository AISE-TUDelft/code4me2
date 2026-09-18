"""Acknowledged spool handoff: capability, partial ack, retry, and filtering."""

from __future__ import annotations

import json

from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.normalize import ProxyNormalizer
from telemetry_acp_proxy.observe import AcpDirection, Observer
from telemetry_acp_proxy.privacy_gate import PrivacyGate
from telemetry_acp_proxy.spool_client import (
    SCHEMA_VERSION,
    LocalSpoolClient,
    OneTimeCapability,
)

CANARY_KEY = "sk-CANARY-SPOOL-0000000001"


def _one_event():
    observed = Observer().observe(
        AcpDirection.HOST_TO_AGENT,
        encode_message({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}),
    )[0]
    return ProxyNormalizer().normalize_observed(observed)[0]


def _accepted(payload: dict) -> dict:
    return {
        "accepted": [event["event_id"] for event in payload["events"]],
        "duplicate": [],
        "rejected": [],
    }


def test_accepted_acknowledgement_marks_every_event_delivered():
    sent: list[str] = []

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        sent.append(capability)
        return _accepted(payload)

    event = _one_event()
    client = LocalSpoolClient(
        "http://127.0.0.1:1/spool",
        OneTimeCapability("cap-accepted"),
        transport=transport,
    )

    result = client.send([event])

    assert result.ok
    assert result.delivered_ids == [str(event.event_id)]
    assert result.dropped_ids == []
    assert sent == ["cap-accepted"]


def test_duplicate_acknowledgement_also_counts_as_delivered():
    event = _one_event()

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        return {"accepted": [], "duplicate": [event.model_dump(mode="json")["event_id"]], "rejected": []}

    client = LocalSpoolClient("http://127.0.0.1:1/spool", "cap-1", transport=transport)

    result = client.send([event])

    assert result.delivered_ids == [str(event.event_id)]
    assert result.dropped == 0


def test_partial_acknowledgement_delivers_only_the_listed_ids():
    events = [_one_event(), _one_event(), _one_event()]
    ids = [event.model_dump(mode="json")["event_id"] for event in events]

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        return {
            "accepted": [ids[0]],
            "duplicate": [ids[2]],
            "rejected": [{"event_id": ids[1], "reason": "invalid_event"}],
        }

    client = LocalSpoolClient("http://127.0.0.1:1/spool", "cap-1", transport=transport)

    result = client.send(events)

    assert result.delivered_ids == [ids[0], ids[2]]
    assert result.dropped_ids == [ids[1]]
    assert result.sent == 2
    assert result.dropped == 1
    assert not result.ok


def test_unacknowledged_ids_are_never_reported_as_delivered():
    event = _one_event()

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        return {"accepted": [], "duplicate": [], "rejected": []}

    client = LocalSpoolClient("http://127.0.0.1:1/spool", "cap-1", transport=transport)

    result = client.send([event])

    assert result.delivered_ids == []
    assert result.dropped_ids == [str(event.event_id)]
    assert not result.ok


def test_401_capability_is_retried_without_a_false_delivery():
    attempts: list[str] = []

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        attempts.append(capability)
        raise OSError("spool endpoint returned status 401")

    client = LocalSpoolClient(
        "http://127.0.0.1:1/spool",
        OneTimeCapability("cap-rejected"),
        transport=transport,
        max_attempts=3,
        jitter=lambda: 0.0,
        sleep=lambda _seconds: None,
    )

    result = client.send([_one_event()])

    assert result.sent == 0
    assert result.dropped == 1
    assert result.delivered_ids == []
    assert result.attempts == 3
    assert len(attempts) == 3
    assert result.error


def test_500_is_retried_without_a_false_delivery():
    slept: list[float] = []

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        raise OSError("spool endpoint returned status 500")

    client = LocalSpoolClient(
        "http://127.0.0.1:1/spool",
        "cap-1",
        transport=transport,
        max_attempts=3,
        base_delay_seconds=0.01,
        max_delay_seconds=0.5,
        jitter=lambda: 0.0,
        sleep=slept.append,
    )

    result = client.send([_one_event()])

    assert result.dropped == 1
    assert result.sent == 0
    assert slept == [0.01, 0.02], "capped exponential backoff"


def test_malformed_acknowledgement_is_retryable_and_never_a_delivery():
    attempts = []

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        attempts.append(1)
        return {"unexpected": "shape"}

    client = LocalSpoolClient(
        "http://127.0.0.1:1/spool",
        "cap-1",
        transport=transport,
        max_attempts=2,
        jitter=lambda: 0.0,
        sleep=lambda _seconds: None,
    )

    result = client.send([_one_event()])

    assert result.sent == 0
    assert result.dropped == 1
    assert len(attempts) == 2
    assert result.error


def test_payload_carries_schema_digest_emitter_and_capability_header():
    captured: dict = {}

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        captured["endpoint"] = endpoint
        captured["capability"] = capability
        captured["payload"] = payload
        return _accepted(payload)

    client = LocalSpoolClient(
        "http://127.0.0.1:1/spool",
        "cap-1",
        proxy_digest="ab" * 32,
        emitter_id="acp-proxy",
        transport=transport,
    )

    client.send([_one_event()])

    payload = captured["payload"]
    assert payload["schema_version"] == SCHEMA_VERSION
    assert payload["proxy_digest"] == "ab" * 32
    assert payload["emitter_id"] == "acp-proxy"
    assert payload["events"]
    assert "capability" not in payload
    assert "proxy_digest" not in payload["events"][0]
    assert captured["capability"] == "cap-1"


def test_secrets_are_filtered_before_the_spool_payload():
    observed = Observer().observe(
        AcpDirection.AGENT_TO_HOST,
        encode_message(
            {
                "jsonrpc": "2.0",
                "method": "session/update",
                "params": {
                    "sessionId": "sess-1",
                    "update": {
                        "sessionUpdate": "tool_call",
                        "toolCallId": "call-1",
                        "title": "run",
                        "status": "pending",
                        "rawInput": {"api_key": CANARY_KEY},
                    },
                },
            }
        ),
    )[0]
    events = [PrivacyGate().filter(event) for event in ProxyNormalizer().normalize_observed(observed)]

    captured: dict = {}

    def transport(endpoint: str, capability: str, payload: dict) -> dict:
        captured["payload"] = payload
        return _accepted(payload)

    client = LocalSpoolClient("http://127.0.0.1:1/spool", "cap-1", transport=transport)

    client.send(events)

    serialized = json.dumps(captured["payload"])
    assert CANARY_KEY not in serialized
    assert "api_key" not in serialized
