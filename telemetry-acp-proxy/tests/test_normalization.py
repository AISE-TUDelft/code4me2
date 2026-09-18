"""Golden normalization tests over the shared canonical contract."""

from __future__ import annotations

import json

from conftest import fixture_path  # type: ignore[import-not-found]

from research.study.agents.enums import SnapshotCapabilityState
from research.telemetry.enums import CanonicalEventType

from telemetry_acp_proxy.adapters import get_adapter
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.normalize import ProxyNormalizer, build_capability_snapshot
from telemetry_acp_proxy.observe import AcpDirection, Observer


def _transcript_observations():
    messages = json.loads(fixture_path("acp_transcript.json").read_text())
    observer = Observer()
    observations = []
    for entry in messages:
        direction = AcpDirection(entry["direction"])
        observations.extend(observer.observe(direction, encode_message(entry["message"])))
    return observations


def _signature(events):
    return [
        {
            "sequence": event.emitter_sequence,
            "event_type": event.event_type,
            "source": event.source,
            "fidelity": event.provenance.fidelity.value,
            "coverage_state": event.coverage.state.value,
            "lifecycle_state": event.lifecycle_state,
            "usage_tokens": event.metrics.usage_tokens,
            "usage_state": event.metrics.usage_capability.state.value,
            "normalizer_version": event.provenance.normalizer_version,
        }
        for event in events
    ]


def test_golden_transcript_normalization():
    normalizer = ProxyNormalizer()
    events = []
    for observed in _transcript_observations():
        events.extend(normalizer.normalize_observed(observed))

    golden = json.loads(fixture_path("golden_events.json").read_text())
    assert _signature(events) == golden


def test_generic_provenance_and_fidelity():
    normalizer = ProxyNormalizer()
    observations = _transcript_observations()
    initialize_request = observations[0]
    events = normalizer.normalize_observed(initialize_request)

    assert events
    event = events[0]
    assert event.event_type == CanonicalEventType.INTERACTION_STARTED.value
    assert event.source == "acp"
    assert event.provenance.normalizer_version == "generic-acp-v1"
    assert event.provenance.fidelity.value in {"exact", "normalized", "inferred"}
    assert event.emitter_id == "acp-proxy"


def test_response_without_a_mapping_is_unknown_source_event():
    normalizer = ProxyNormalizer()
    observations = _transcript_observations()
    initialize_response = observations[1]
    events = normalizer.normalize_observed(initialize_response)

    assert events
    assert events[0].event_type == CanonicalEventType.UNKNOWN_SOURCE_EVENT.value
    assert events[0].coverage.state.value == "NEEDS_REVIEW"


def test_adapter_enriches_without_deleting_generic_event():
    message = {
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {
            "sessionId": "sess-1",
            "update": {
                "sessionUpdate": "tool_call",
                "toolCallId": "call-1",
                "title": "Edit file",
                "kind": "edit",
                "status": "pending",
                "rawInput": {"path": "/synthetic/project/main.py"},
            },
        },
    }
    observer = Observer()
    observed = observer.observe(AcpDirection.AGENT_TO_HOST, encode_message(message))[0]

    generic_events = ProxyNormalizer().normalize_observed(observed)
    enriched_events = ProxyNormalizer(adapter=get_adapter("codex-v1")).normalize_observed(
        observed
    )

    assert len(generic_events) == 1 and len(enriched_events) == 1
    generic, enriched = generic_events[0], enriched_events[0]

    # Same generic fact: event type, id-agnostic identity and generic fields.
    assert generic.event_type == enriched.event_type == CanonicalEventType.TOOL_CREATED.value
    assert generic.provenance.fidelity == enriched.provenance.fidelity
    assert enriched.payload["tool_call_id"] == "call-1"
    assert enriched.payload["tool_name"] == "Edit file"
    # The adapter only added a label.
    assert "normalized_tool_kind" not in generic.payload
    assert enriched.payload["normalized_tool_kind"] == "FILE_EDIT"
    assert enriched.provenance.adapter_version == "codex-v1"


def test_adapter_does_not_claim_exact_fidelity_or_change_event_type():
    message = {
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {
            "sessionId": "sess-1",
            "update": {
                "sessionUpdate": "tool_call_update",
                "toolCallId": "call-2",
                "title": "Write file",
                "kind": "write",
                "status": "completed",
            },
        },
    }
    observed = Observer().observe(
        AcpDirection.AGENT_TO_HOST, encode_message(message)
    )[0]

    generic = ProxyNormalizer().normalize_observed(observed)[0]
    enriched = ProxyNormalizer(adapter=get_adapter("codex-v1")).normalize_observed(
        observed
    )[0]

    assert generic.event_type == enriched.event_type == CanonicalEventType.TOOL_COMPLETED.value
    assert enriched.provenance.fidelity == generic.provenance.fidelity


def test_unknown_method_is_sanitized_metadata_only():
    message = {
        "jsonrpc": "2.0",
        "id": 99,
        "method": "vendor/magicThing",
        "params": {"secretThing": "ghp_CANARYPROXY0000000001", "count": 3},
    }
    observed = Observer().observe(
        AcpDirection.HOST_TO_AGENT, encode_message(message)
    )[0]

    events = ProxyNormalizer().normalize_observed(observed)
    assert events
    event = events[0]
    assert event.event_type == CanonicalEventType.UNKNOWN_SOURCE_EVENT.value
    serialized = event.model_dump_json()
    assert "ghp_CANARYPROXY0000000001" not in serialized
    assert event.payload["unknown_method"] == "vendor/magicThing"
    assert event.payload["param_names"] == ["count", "secretThing"]


def test_capability_snapshot_keeps_declared_and_observed_separate():
    observations = _transcript_observations()
    snapshot = build_capability_snapshot(
        observations,
        agent_id="codex-acp",
        release_id="rel-1",
        protocol_version="1",
    )

    assert snapshot.declared
    assert any(
        entry.state == SnapshotCapabilityState.DECLARED
        for entry in snapshot.declared.values()
    )
    assert snapshot.observed["INITIALIZE"].state == SnapshotCapabilityState.OBSERVED
    # A declared capability with no observation stays declared, never synthesized.
    declared_only = [
        name for name in snapshot.declared if name not in snapshot.observed
    ]
    assert declared_only


def _events_from(entries):
    """Normalize ``[(direction, message), ...]`` with one shared proxy normalizer."""
    observer = Observer()
    normalizer = ProxyNormalizer()
    events = []
    for direction, message in entries:
        for observed in observer.observe(direction, encode_message(message)):
            events.extend(normalizer.normalize_observed(observed))
    return events


def test_permission_request_carries_metadata_only_options_and_permission_id():
    events = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                {
                    "jsonrpc": "2.0",
                    "id": 4,
                    "method": "session/request_permission",
                    "params": {
                        "sessionId": "sess-1",
                        "toolCall": {"toolCallId": "call-1"},
                        "options": [
                            {"optionId": "allow_once", "name": "Allow", "kind": "allow_once"},
                            {"optionId": "reject_once", "name": "Reject", "kind": "reject_once"},
                        ],
                    },
                },
            )
        ]
    )

    assert len(events) == 1
    event = events[0]
    assert event.event_type == CanonicalEventType.PERMISSION_REQUESTED.value
    assert event.correlations.permission_id == "4"
    assert event.correlations.tool_call_id == "call-1"
    assert event.payload["tool_call_id"] == "call-1"
    assert event.payload["option_count"] == 2
    assert event.payload["options"] == [
        {"option_id": "allow_once", "kind": "allow_once"},
        {"option_id": "reject_once", "kind": "reject_once"},
    ]
    # Option human labels are not metadata and must not be captured.
    serialized = json.dumps(event.payload)
    assert "Allow" not in serialized and "Reject" not in serialized


def _permission_round_trip(response_result):
    return _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                {
                    "jsonrpc": "2.0",
                    "id": 7,
                    "method": "session/request_permission",
                    "params": {
                        "sessionId": "sess-1",
                        "toolCall": {"toolCallId": "call-7"},
                        "options": [
                            {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
                            {"optionId": "reject", "name": "Reject", "kind": "reject_once"},
                            {"optionId": "always", "name": "Always", "kind": "allow_always"},
                            {
                                "optionId": "never",
                                "name": "Never",
                                "kind": "reject_always",
                            },
                        ],
                    },
                },
            ),
            (
                AcpDirection.HOST_TO_AGENT,
                {"jsonrpc": "2.0", "id": 7, "result": response_result},
            ),
        ]
    )


def test_permission_decided_allow_maps_the_selected_option_kind():
    events = _permission_round_trip(
        {"outcome": {"outcome": "selected", "optionId": "allow"}}
    )

    assert [event.event_type for event in events] == [
        CanonicalEventType.PERMISSION_REQUESTED.value,
        CanonicalEventType.PERMISSION_DECIDED.value,
    ]
    decided = events[1]
    assert decided.payload["outcome"] == "selected"
    assert decided.payload["selected_option_id"] == "allow"
    assert decided.payload["selected_option_kind"] == "allow_once"
    assert decided.payload["decision"] == "allow"
    assert decided.correlations.permission_id == "7"
    assert decided.correlations.tool_call_id == "call-7"


def test_permission_decided_reject_always_maps_to_reject():
    decided = _permission_round_trip(
        {"outcome": {"outcome": "selected", "optionId": "never"}}
    )[1]

    assert decided.event_type == CanonicalEventType.PERMISSION_DECIDED.value
    assert decided.payload["selected_option_kind"] == "reject_always"
    assert decided.payload["decision"] == "reject"


def test_permission_decided_cancelled_is_recorded():
    decided = _permission_round_trip({"outcome": {"outcome": "cancelled"}})[1]

    assert decided.event_type == CanonicalEventType.PERMISSION_DECIDED.value
    assert decided.payload["outcome"] == "cancelled"
    assert decided.payload["decision"] == "cancelled"


def test_uncorrelated_permission_response_is_not_invented_as_a_decision():
    events = _events_from(
        [
            (
                AcpDirection.HOST_TO_AGENT,
                {
                    "jsonrpc": "2.0",
                    "id": 99,
                    "result": {"outcome": {"outcome": "selected", "optionId": "allow"}},
                },
            )
        ]
    )

    assert events[0].event_type == CanonicalEventType.UNKNOWN_SOURCE_EVENT.value


def test_tool_lifecycle_created_once_then_started_then_completed():
    events = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update("call-9", kind="tool_call", status="pending"),
            ),
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update("call-9", kind="tool_call", status="pending"),
            ),
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update(
                    "call-9", kind="tool_call_update", status="in_progress"
                ),
            ),
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update("call-9", kind="tool_call_update", status="completed"),
            ),
        ]
    )

    assert [event.event_type for event in events] == [
        CanonicalEventType.TOOL_CREATED.value,
        CanonicalEventType.TOOL_STARTED.value,
        CanonicalEventType.TOOL_STARTED.value,
        CanonicalEventType.TOOL_COMPLETED.value,
    ]
    assert events[0].lifecycle_state == "pending"
    assert events[3].lifecycle_state == "completed"


def test_tool_failure_is_an_explicit_failed_terminal():
    events = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update("call-10", kind="tool_call", status="pending"),
            ),
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update("call-10", kind="tool_call_update", status="error"),
            ),
        ]
    )

    assert events[-1].event_type == CanonicalEventType.TOOL_FAILED.value
    assert events[-1].lifecycle_state == "failed"


def test_agent_message_completed_on_prompt_response_with_usage():
    events = _events_from(
        [
            (
                AcpDirection.HOST_TO_AGENT,
                {
                    "jsonrpc": "2.0",
                    "id": 3,
                    "method": "session/prompt",
                    "params": {"sessionId": "sess-1", "prompt": [{"type": "text", "text": "hi"}]},
                },
            ),
            (
                AcpDirection.AGENT_TO_HOST,
                {
                    "jsonrpc": "2.0",
                    "id": 3,
                    "result": {"stopReason": "end_turn", "usage": {"totalTokens": 42}},
                },
            ),
        ]
    )

    assert events[0].event_type == CanonicalEventType.AGENT_MESSAGE_STARTED.value
    completed = events[-1]
    assert completed.event_type == CanonicalEventType.AGENT_MESSAGE_COMPLETED.value
    assert completed.lifecycle_state == "completed"
    assert completed.payload["stop_reason"] == "end_turn"
    assert completed.metrics.usage_tokens == 42
    assert completed.metrics.usage_capability.state.value == "AVAILABLE"


def test_agent_message_completed_without_usage_is_null_and_unavailable():
    completed = _events_from(
        [
            (
                AcpDirection.HOST_TO_AGENT,
                {
                    "jsonrpc": "2.0",
                    "id": 3,
                    "method": "session/prompt",
                    "params": {"sessionId": "sess-1"},
                },
            ),
            (AcpDirection.AGENT_TO_HOST, {"jsonrpc": "2.0", "id": 3, "result": {}}),
        ]
    )[-1]

    assert completed.event_type == CanonicalEventType.AGENT_MESSAGE_COMPLETED.value
    assert completed.metrics.usage_tokens is None
    assert completed.metrics.usage_capability.state.value == "UNAVAILABLE"


def test_usage_update_reports_observed_tokens_and_keeps_zero():
    available, zero = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                _usage_update({"inputTokens": 10, "outputTokens": 5}),
            ),
            (
                AcpDirection.AGENT_TO_HOST,
                _usage_update({"totalTokens": 0}),
            ),
        ]
    )

    assert available.event_type == CanonicalEventType.USAGE_UPDATED.value
    assert available.metrics.usage_tokens == 15
    assert available.metrics.usage_capability.state.value == "AVAILABLE"
    # An observed zero is a real measurement, never "unavailable".
    assert zero.metrics.usage_tokens == 0
    assert zero.metrics.usage_capability.state.value == "AVAILABLE"


def test_usage_update_without_tokens_is_null_and_unavailable():
    event = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                _usage_update({"used": None, "size": 200000}),
            )
        ]
    )[0]

    assert event.event_type == CanonicalEventType.USAGE_UPDATED.value
    assert event.metrics.usage_tokens is None
    assert event.metrics.usage_tokens != 0
    assert event.metrics.usage_capability.state.value == "UNAVAILABLE"


def test_plan_update_is_metadata_only():
    events = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                _tool_update(
                    None,
                    kind="plan",
                    status=None,
                    extra={
                        "entries": [
                            {"content": "CANARY-PLAN-STEP", "priority": "high", "status": "pending"},
                            {"content": "another", "priority": "low", "status": "completed"},
                        ]
                    },
                ),
            )
        ]
    )

    assert len(events) == 1
    event = events[0]
    assert event.event_type == CanonicalEventType.PLAN_UPDATED.value
    assert event.payload["plan_size"] == 2
    assert event.payload["plan_status_counts"] == [
        {"status": "completed", "count": 1},
        {"status": "pending", "count": 1},
    ]
    assert "CANARY-PLAN-STEP" not in event.model_dump_json()


def test_agent_error_response_is_an_explicit_error_event():
    events = _events_from(
        [
            (
                AcpDirection.AGENT_TO_HOST,
                {
                    "jsonrpc": "2.0",
                    "id": 11,
                    "error": {"code": -32601, "message": "method not found"},
                },
            )
        ]
    )

    assert len(events) == 1
    event = events[0]
    assert event.event_type == CanonicalEventType.AGENT_ERROR.value
    assert event.payload["error_source"] == "agent"
    assert event.payload["error_code"] == -32601


def _tool_update(tool_call_id, *, kind, status, extra=None):
    update = {"sessionUpdate": kind}
    if tool_call_id is not None:
        update["toolCallId"] = tool_call_id
    if status is not None:
        update["status"] = status
    if extra:
        update.update(extra)
    return {
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {"sessionId": "sess-1", "update": update},
    }


def _usage_update(usage):
    update = {"sessionUpdate": "usage_update", **usage}
    return {
        "jsonrpc": "2.0",
        "method": "session/update",
        "params": {"sessionId": "sess-1", "update": update},
    }



def test_acp_session_id_is_stamped_on_events_that_do_not_carry_it():
    """Regression: before this, only session-scoped *requests* carried
    ``session_id`` in their payload, so permission/tool/message events were
    unattributable and could never be joined back to the agent run."""
    observations = _transcript_observations()
    normalizer = ProxyNormalizer()
    by_type: dict[str, set] = {}
    for observed in observations:
        for event in normalizer.normalize_observed(observed):
            by_type.setdefault(str(event.event_type), set()).add(
                event.payload.get("session_id")
            )

    # Permission events previously had no session id at all.
    for event_type in ("permission.requested", "permission.decided"):
        assert by_type.get(event_type) == {"sess-synthetic-1"}, event_type

    # Tool and message-stream events are attributable too.
    for event_type in ("tool.created", "tool.completed", "agent.message.started"):
        assert "sess-synthetic-1" in (by_type.get(event_type) or set()), event_type

    # Only the session id learned from the stream is ever stamped.
    stamped = {sid for ids in by_type.values() for sid in ids if sid is not None}
    assert stamped == {"sess-synthetic-1"}

