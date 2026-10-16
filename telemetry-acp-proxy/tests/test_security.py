"""Security tests: secrets never cross the privacy gate or reach stderr."""

from __future__ import annotations

from research.telemetry.privacy import PrivacyPolicy

from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.normalize import ProxyNormalizer
from telemetry_acp_proxy.observe import AcpDirection, Observer
from telemetry_acp_proxy.privacy_gate import PrivacyGate

CANARY_KEY = "sk-CANARY-PROXY-0000000001"
CANARY_PROMPT = "CANARY-PROXY-PROMPT-TEXT"
CANARY_GH = "ghp_CANARYPROXYSECRET0002"
CANARY_BEARER = "Bearer CANARY-PROXY-BEARER-0003"


def _normalized_events(message: dict, *, direction=AcpDirection.HOST_TO_AGENT):
    observed = Observer().observe(direction, encode_message(message))[0]
    return ProxyNormalizer().normalize_observed(observed)


def _gated(message: dict, *, policy=None, direction=AcpDirection.HOST_TO_AGENT):
    gate = PrivacyGate(policy or PrivacyPolicy.default())
    return [gate.filter(event) for event in _normalized_events(message, direction=direction)]


def _events_for(message: dict, *, direction=AcpDirection.HOST_TO_AGENT):
    return _gated(message, direction=direction)


def test_secrets_never_reach_canonical_events():
    events = []
    events += _events_for(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": 1,
                "clientCapabilities": {},
                "api_key": CANARY_KEY,
                "authorization": CANARY_BEARER,
            },
        }
    )
    events += _events_for(
        {
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {
                "sessionId": "sess-1",
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "call-1",
                    "title": "run",
                    "kind": "exec",
                    "status": "pending",
                    "rawInput": {"command": "run", "token": CANARY_GH},
                    "content": [{"type": "text", "text": CANARY_PROMPT}],
                },
            },
        },
        direction=AcpDirection.AGENT_TO_HOST,
    )

    serialized = "\n".join(event.model_dump_json() for event in events)
    for canary in (CANARY_KEY, CANARY_PROMPT, CANARY_GH, CANARY_BEARER):
        assert canary not in serialized

    tool_events = [event for event in events if event.event_type.startswith("tool.")]
    assert tool_events
    payload = tool_events[0].payload
    assert payload.get("arguments") == "[REDACTED]"
    assert payload.get("content") == "[REDACTED]"


def test_default_policy_does_not_capture_content():
    events = _events_for(
        {
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {
                "sessionId": "sess-1",
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "messageId": "m1",
                    "content": {"type": "text", "text": CANARY_PROMPT},
                },
            },
        },
        direction=AcpDirection.AGENT_TO_HOST,
    )
    serialized = "\n".join(event.model_dump_json() for event in events)
    assert CANARY_PROMPT not in serialized
    message_events = [
        event for event in events if event.event_type.startswith("agent.message")
    ]
    assert message_events
    assert message_events[0].payload["content"] == "[REDACTED]"


def test_parse_failure_is_metadata_only_and_never_contains_raw_text():
    gate = PrivacyGate(PrivacyPolicy.default())
    observed = Observer().observe(
        AcpDirection.AGENT_TO_HOST, b'{"jsonrpc":"2.0","api_key":"' + CANARY_KEY.encode() + b'"\n'
    )[0]

    event = gate.parse_failed(
        observed, parse_error_kind="json decode error", occurred_at=observed.receive_wall_time
    )

    serialized = event.model_dump_json()
    assert CANARY_KEY not in serialized
    assert event.payload["failure_kind"] == "acp_parse_failed"
    # The raw malformed bytes are represented only by a digest.
    assert event.provenance.evidence_digest == observed.source_digest
    assert event.payload["parse_error_kind"] == "json decode error"


def test_content_allowed_only_with_explicit_consent():
    policy = PrivacyPolicy(
        allowed_field_classes=[],
        content_allowed=True,
        consent_active=True,
    )
    events = _gated(
        {
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {
                "sessionId": "sess-1",
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "messageId": "m1",
                    "content": {"type": "text", "text": CANARY_PROMPT},
                },
            },
        },
        policy=policy,
        direction=AcpDirection.AGENT_TO_HOST,
    )
    # With explicit allowance AND consent, content is allowed through the gate.
    serialized = "\n".join(event.model_dump_json() for event in events)
    assert CANARY_PROMPT in serialized


def test_secret_still_dropped_under_permissive_policy():
    policy = PrivacyPolicy(
        allowed_field_classes=[],
        content_allowed=True,
        consent_active=True,
    )
    events = _gated(
        {
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {
                "sessionId": "s",
                "update": {
                    "sessionUpdate": "tool_call",
                    "toolCallId": "c",
                    "title": "run",
                    "status": "pending",
                    "rawInput": {"api_key": CANARY_KEY},
                },
            },
        },
        policy=policy,
        direction=AcpDirection.AGENT_TO_HOST,
    )
    serialized = "\n".join(event.model_dump_json() for event in events)
    assert CANARY_KEY not in serialized


def test_permission_and_plan_metadata_survive_the_default_gate():
    permission = _events_for(
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "session/request_permission",
            "params": {
                "sessionId": "s",
                "toolCall": {"toolCallId": "call-1"},
                "options": [
                    {"optionId": "allow_once", "name": "Allow", "kind": "allow_once"},
                    {"optionId": "reject_once", "name": "Reject", "kind": "reject_once"},
                ],
            },
        },
        direction=AcpDirection.AGENT_TO_HOST,
    )
    plan = _events_for(
        {
            "jsonrpc": "2.0",
            "method": "session/update",
            "params": {
                "sessionId": "s",
                "update": {
                    "sessionUpdate": "plan",
                    "entries": [
                        {"content": "step one", "priority": "high", "status": "pending"}
                    ],
                },
            },
        },
        direction=AcpDirection.AGENT_TO_HOST,
    )

    requested = permission[0]
    assert requested.payload["options"] == [
        {"option_id": "allow_once", "kind": "allow_once"},
        {"option_id": "reject_once", "kind": "reject_once"},
    ]
    updated = plan[0]
    assert updated.payload["plan_size"] == 1
    assert updated.payload["plan_status_counts"] == [
        {"status": "pending", "count": 1}
    ]
