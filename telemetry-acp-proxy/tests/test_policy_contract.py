"""ISSUE-09: the frozen policy and adapter selection reach the proxy parser.

The registration emits ``--adapter``, ``--telemetry-policy`` and
``--telemetry-policy-digest``; the proxy verifies the digest before doing any
work and drops privacy-blocked observations before the spool.
"""

from __future__ import annotations

import io
import sys
from datetime import datetime, timezone

from conftest import fixture_path, python_digest  # type: ignore[import-not-found]

from research.telemetry.builder import EventBuilder
from research.telemetry.enums import CanonicalEventType, EventSource
from research.telemetry.privacy import PrivacyPolicy, filter_event
from telemetry_acp_proxy import main as proxy_main

# Reference digest of the default policy fields, computed by the same canonical
# JSON rules the plugin's PrivacyPolicy.computedDigest() uses. Pinning it here
# proves the Kotlin and Python digest implementations agree.
DEFAULT_POLICY_DIGEST = "c8a07e0b35fed432330e6e91d6a58f36ee8c2eaa93693277fddcd0aa5515c797"


def _policy(**overrides) -> PrivacyPolicy:
    base = {
        "allowed_field_classes": [
            "SYSTEM",
            "BEHAVIORAL",
            "CODE_METADATA",
        ],
        "content_allowed": False,
        "consent_active": True,
        "code_metadata_mode": "hash",
    }
    base.update(overrides)
    return PrivacyPolicy.model_validate(base)


def test_parser_accepts_the_registration_policy_contract():
    args = proxy_main.build_parser().parse_args(
        [
            "--telemetry-policy",
            "/tmp/policy.json",
            "--telemetry-policy-digest",
            "abc123",
            "--adapter",
            "codex-v1",
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--agent-cmd",
            "agent",
        ]
    )
    assert args.telemetry_policy == "/tmp/policy.json"
    assert args.telemetry_policy_digest == "abc123"
    assert args.adapter == "codex-v1"


def test_computed_digest_matches_the_plugin_reference():
    assert proxy_main.computed_policy_digest(_policy()) == DEFAULT_POLICY_DIGEST


def test_a_mismatched_policy_digest_fails_closed_before_forwarding(tmp_path):
    agent = fixture_path("echo_agent.py")
    spool = tmp_path / "spool.jsonl"
    diagnostics: list[str] = []
    host_out = io.BytesIO()

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(agent)],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{spool}",
        capability="cap-1",
        policy=_policy(policy_digest="deadbeef"),
        policy_digest="0" * 64,
        host_read=io.BytesIO(b""),
        host_write=host_out,
        diagnostics=diagnostics.append,
    )

    assert exit_code == proxy_main.EXIT_USAGE
    assert any("does not match" in message for message in diagnostics)
    assert host_out.getvalue() == b"", "nothing may be forwarded before the check"
    assert not spool.exists(), "nothing may be spooled before the check"


def test_unknown_adapter_fails_closed():
    agent = fixture_path("echo_agent.py")
    diagnostics: list[str] = []

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(agent)],
        agent_digest=python_digest(),
        adapter_name="not-a-real-adapter",
        host_read=io.BytesIO(b""),
        host_write=io.BytesIO(),
        diagnostics=diagnostics.append,
    )

    assert exit_code == proxy_main.EXIT_USAGE


def test_privacy_blocked_events_are_dropped_before_the_spool():
    event = (
        EventBuilder()
        .build(
            emitter_id="proxy-test",
            event_type=CanonicalEventType.AGENT_MESSAGE_COMPLETED,
            source=EventSource.ACP,
            occurred_at=datetime.now(timezone.utc),
            normalizer_version="test",
            payload={"prompt": "secret source text"},
            emitter_sequence=1,
        )
    )
    filtered = filter_event(event, _policy(blocked_field_classes=["CONTENT"]))
    assert filtered.summary.blocked

    diagnostics: list[str] = []
    deliverable = proxy_main._deliverable([filtered.event], diagnostics.append)

    assert deliverable == []
    assert any("privacy-blocked" in message for message in diagnostics)


def test_parser_accepts_repeated_agent_env_overrides():
    args = proxy_main.build_parser().parse_args(
        [
            "--agent-env",
            "GOOSE_MODEL=claude-3",
            "--agent-env",
            "GOOSE_MODE=auto",
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--agent-cmd",
            "agent",
        ]
    )
    assert args.agent_env == ["GOOSE_MODEL=claude-3", "GOOSE_MODE=auto"]


def test_malformed_agent_env_fails_closed():
    parsed, error = proxy_main._agent_environment(["GOOSE_MODEL"])
    assert parsed == {}
    assert error is not None and "KEY=VALUE" in error

    agent = fixture_path("echo_agent.py")
    # main() must refuse the malformed pair before any launch/forwarding.
    exit_code = proxy_main.main(
        [
            "--agent-env",
            "GOOSE_MODEL",
            "--agent-digest",
            python_digest(),
            "--agent-cmd",
            sys.executable,
            str(agent),
        ]
    )
    assert exit_code == proxy_main.EXIT_USAGE


def test_agent_env_overrides_reach_only_the_child_environment():
    from telemetry_acp_proxy.lifecycle import ProxyProcess

    recorded: dict[str, str] = {}

    class _RecordingProcess:
        pid = 1
        stdin = None
        stdout = None

        def poll(self):
            return None

    def factory(argv, **kwargs):
        recorded.update(kwargs.get("env") or {})
        return _RecordingProcess()

    process = ProxyProcess(
        [sys.executable, "-c", "pass"],
        env_overrides={"BYOA_AGENT_MODEL": "claude-3"},
        popen_factory=factory,
    )
    process.start()

    assert recorded["BYOA_AGENT_MODEL"] == "claude-3"
    assert "BYOA_AGENT_MODEL" not in __import__("os").environ
