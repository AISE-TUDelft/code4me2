"""``--capability-file`` reads the one-time capability without deleting it.

The file is plugin-owned: it is written on every activation and deleted only on
teardown, so the proxy must leave it in place for repeated launches.
"""

from __future__ import annotations

import io
import json
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



class _CapturedOutput(io.BytesIO):
    def close(self) -> None:
        pass


def _run_chat(tmp_path, capability_file, host_bytes, diagnostics):
    host_output = _CapturedOutput()
    exit_code = proxy_main.run_proxy(
        agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
        agent_digest=python_digest(),
        spool_endpoint=f"file://{tmp_path / 'spool.jsonl'}",
        capability="old-session-token",
        capability_file=str(capability_file),
        host_read=io.BytesIO(host_bytes),
        host_write=host_output,
        diagnostics=diagnostics.append,
    )
    return exit_code, host_output.getvalue()


def test_old_chat_is_refused_once_its_session_capability_is_replaced(tmp_path):
    capability_file = tmp_path / "capability"
    capability_file.write_text("new-session-token", encoding="utf-8")
    diagnostics: list[str] = []

    exit_code, output = _run_chat(
        tmp_path, capability_file, b'{"jsonrpc":"2.0","id":1,"method":"session/new"}\n', diagnostics
    )

    assert exit_code == proxy_main.EXIT_SPOOL_REJECTED
    answer = json.loads(output.decode("utf-8"))
    # The chat learns why instead of hanging; the agent never saw the request.
    assert answer["id"] == 1 and "Start a new chat" in answer["error"]["message"]
    assert "session/new" not in output.decode("utf-8")
    assert any("session for this chat has ended" in message for message in diagnostics)
    assert "old-session-token" not in " ".join(diagnostics)
    assert "new-session-token" not in " ".join(diagnostics)


def test_old_chat_is_refused_once_its_session_capability_is_removed(tmp_path):
    diagnostics: list[str] = []

    exit_code, output = _run_chat(
        tmp_path, tmp_path / "removed-capability", b'{"jsonrpc":"2.0","id":7,"method":"session/prompt"}\n', diagnostics
    )

    assert exit_code == proxy_main.EXIT_SPOOL_REJECTED
    assert json.loads(output.decode("utf-8"))["id"] == 7


def test_a_chat_of_the_live_session_is_forwarded_untouched(tmp_path):
    capability_file = tmp_path / "capability"
    capability_file.write_text("old-session-token\n", encoding="utf-8")
    frame = b'{"jsonrpc":"2.0","id":1,"method":"session/new"}\n'

    exit_code, output = _run_chat(tmp_path, capability_file, frame, [])

    assert exit_code == proxy_main.EXIT_OK
    assert output == frame  # the echo agent answered: the frame reached it byte for byte

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


def test_a_session_torn_down_while_the_host_is_idle_stops_the_agent(tmp_path, monkeypatch):
    import threading
    import time

    from test_initialize_replay import _MemoryChannel  # type: ignore[import-not-found]

    monkeypatch.setattr(proxy_main, "STALE_SESSION_POLL_SECONDS", 0.05)
    capability_file = tmp_path / "capability"
    capability_file.write_text("old-session-token", encoding="utf-8")
    host_input, host_output = _MemoryChannel(), _MemoryChannel()
    diagnostics: list[str] = []
    result: dict = {}

    def run():
        result["exit"] = proxy_main.run_proxy(
            agent_cmd=[sys.executable, str(fixture_path("echo_agent.py"))],
            agent_digest=python_digest(),
            spool_endpoint=f"file://{tmp_path / 'spool.jsonl'}",
            capability="old-session-token",
            capability_file=str(capability_file),
            host_read=host_input,
            host_write=host_output,
            diagnostics=diagnostics.append,
        )

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    try:
        time.sleep(0.3)
        capability_file.unlink()  # the plugin tore the session down; the host sent nothing
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline and not host_output._closed:
            time.sleep(0.05)
        # The agent was stopped on its own: its side (the host's input) closed.
        assert host_output._closed
        assert any("the agent is stopped" in message for message in diagnostics)
    finally:
        host_input.close()
        thread.join(timeout=10.0)
    assert result["exit"] == proxy_main.EXIT_SPOOL_REJECTED

