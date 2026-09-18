"""``--capability-file`` reads the one-time capability without deleting it.

The file is plugin-owned: it is written on every activation and deleted only on
teardown, so the proxy must leave it in place for repeated launches.
"""

from __future__ import annotations

import io
import sys

import pytest

from conftest import fixture_path, python_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main


def test_capability_file_is_read_without_deletion(tmp_path):
    capability_file = tmp_path / "capability"
    capability_file.write_text("one-time-token\n", encoding="utf-8")

    first_token, first_error = proxy_main.resolve_capability(None, str(capability_file))
    second_token, second_error = proxy_main.resolve_capability(None, str(capability_file))

    assert first_error is None
    assert second_error is None
    assert first_token == second_token == "one-time-token"
    assert capability_file.exists(), "the proxy must not delete the plugin-owned capability file"


def test_missing_capability_file_is_a_usage_error(tmp_path):
    missing = tmp_path / "does-not-exist"

    token, error = proxy_main.resolve_capability(None, str(missing))

    assert token is None
    assert error is not None

    exit_code = proxy_main.main(
        [
            "--agent-digest",
            "sha256:" + "0" * 64,
            "--capability-file",
            str(missing),
            "--agent-cmd",
            sys.executable,
        ]
    )
    assert exit_code == proxy_main.EXIT_USAGE


def test_capability_and_capability_file_are_mutually_exclusive():
    with pytest.raises(SystemExit):
        proxy_main.build_parser().parse_args(
            [
                "--agent-digest",
                "sha256:" + "0" * 64,
                "--capability",
                "inline",
                "--capability-file",
                "/tmp/capability",
                "--agent-cmd",
                "agent",
            ]
        )


def test_capability_file_token_is_used_for_the_spool(monkeypatch, tmp_path):
    capability_file = tmp_path / "capability"
    capability_file.write_text("token-from-file", encoding="utf-8")
    captured: dict[str, str] = {}

    class CapturingSpool:
        def __init__(self, endpoint, capability, **kwargs):
            captured["endpoint"] = endpoint
            captured["capability"] = capability
            captured["kwargs"] = kwargs

        def send(self, events):
            return None

    monkeypatch.setattr(proxy_main, "LocalSpoolClient", CapturingSpool)

    token, error = proxy_main.resolve_capability(None, str(capability_file))
    assert error is None

    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{tmp_path / 'spool.jsonl'}",
        capability=token,
        host_read=io.BytesIO(b""),
        host_write=io.BytesIO(),
        diagnostics=lambda _message: None,
    )

    assert exit_code == proxy_main.EXIT_OK
    assert captured["capability"] == "token-from-file"
    assert captured["endpoint"] == f"file://{tmp_path / 'spool.jsonl'}"


def test_environment_capability_is_used_when_the_file_is_missing(tmp_path):
    # The ACP entry persists CODE4ME_RESEARCH_CAPABILITY; the fallback file may
    # already have been removed, so a missing file must not fail the launch.
    missing = tmp_path / "deleted-capability"
    environment = {proxy_main.CAPABILITY_ENV_VAR: "env-token\n"}

    token, error = proxy_main.resolve_capability(None, str(missing), environment)

    assert error is None
    assert token == "env-token"


def test_environment_capability_is_used_without_any_file_argument():
    token, error = proxy_main.resolve_capability(
        None, None, {proxy_main.CAPABILITY_ENV_VAR: "env-token"}
    )

    assert error is None
    assert token == "env-token"


def test_blank_environment_capability_is_treated_as_missing(tmp_path):
    missing = tmp_path / "deleted-capability"

    token, error = proxy_main.resolve_capability(
        None, str(missing), {proxy_main.CAPABILITY_ENV_VAR: "   "}
    )

    assert token is None
    assert error is not None


def test_explicit_capability_wins_over_the_environment():
    token, error = proxy_main.resolve_capability(
        "explicit", None, {proxy_main.CAPABILITY_ENV_VAR: "env-token"}
    )

    assert error is None
    assert token == "explicit"


def test_a_required_capability_retries_until_it_appears(tmp_path):
    missing = tmp_path / "late-capability"
    environment: dict[str, str] = {}
    ticks = {"count": 0}

    def fake_monotonic() -> float:
        return ticks["count"] * 0.05

    def fake_sleep(_seconds: float) -> None:
        ticks["count"] += 1
        # Activation lands while the launch is waiting.
        environment[proxy_main.CAPABILITY_ENV_VAR] = "late-token"

    token, error = proxy_main.resolve_capability_with_retry(
        None,
        str(missing),
        required=True,
        environment=environment,
        timeout_seconds=5.0,
        interval_seconds=0.1,
        sleep=fake_sleep,
        monotonic=fake_monotonic,
    )

    assert error is None
    assert token == "late-token"
    assert ticks["count"] >= 1


def test_a_required_capability_gives_up_after_the_bounded_window(tmp_path):
    missing = tmp_path / "never-capability"
    ticks = {"count": 0}

    def fake_monotonic() -> float:
        return float(ticks["count"])

    def fake_sleep(_seconds: float) -> None:
        ticks["count"] += 1

    token, error = proxy_main.resolve_capability_with_retry(
        None,
        str(missing),
        required=True,
        environment={},
        timeout_seconds=2.0,
        interval_seconds=1.0,
        sleep=fake_sleep,
        monotonic=fake_monotonic,
    )

    assert token is None
    assert error is not None
    assert ticks["count"] <= 3, "the retry window must stay bounded"


def test_an_optional_missing_capability_does_not_retry(tmp_path):
    missing = tmp_path / "does-not-exist"

    token, error = proxy_main.resolve_capability_with_retry(
        None,
        str(missing),
        required=False,
        environment={},
        sleep=lambda _seconds: pytest.fail("an unrequired capability must not wait"),
    )

    assert token is None
    assert error is not None

