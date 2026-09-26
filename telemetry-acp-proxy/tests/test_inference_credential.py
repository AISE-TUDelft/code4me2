"""``--inference-credential-file``: the gateway credential reaches the child only.

The plugin writes a JSON file ``{schema_version, credential_env_key,
credential}``; the proxy sets that one variable in the agent child's
environment. It must never travel on argv, never be logged, never be deleted
by the proxy, and a missing/malformed file must refuse the launch.
"""

from __future__ import annotations

import json
import os
import sys

import pytest

from conftest import python_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.lifecycle import build_environment

CREDENTIAL = "c4m-inference-capability-token-value"


def _credential_file(tmp_path, **overrides):
    document = {"schema_version": "1", "credential_env_key": "OPENAI_API_KEY", "credential": CREDENTIAL}
    document.update(overrides)
    path = tmp_path / "inference-credential.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    return path


class _Recorder:
    """Stands in for ProxyProcess: records the child overrides, refuses to start."""

    instances: list = []

    def __init__(self, argv, env_overrides=None, **kwargs):
        self.argv = argv
        self.env_overrides = dict(env_overrides or {})
        _Recorder.instances.append(self)

    def start(self):
        raise OSError("recorder: not launching")


def _run(monkeypatch, *, agent_env=None, credential=None, diagnostics=None):
    _Recorder.instances.clear()
    monkeypatch.setattr(proxy_main, "ProxyProcess", _Recorder)
    return proxy_main.run_proxy(
        agent_cmd=[sys.executable, "-c", "pass"],
        agent_digest=python_digest(),
        agent_env=agent_env,
        inference_credential=credential,
        diagnostics=diagnostics,
    )


def test_credential_is_injected_only_into_the_child_environment(tmp_path, monkeypatch):
    path = _credential_file(tmp_path)
    credential, error = proxy_main.load_inference_credential(str(path))
    assert error is None
    assert credential == proxy_main.InferenceCredential("OPENAI_API_KEY", CREDENTIAL)

    diagnostics: list[str] = []
    exit_code = _run(monkeypatch, agent_env={"GOOSE_MODEL": "m"}, credential=credential, diagnostics=diagnostics.append)
    assert exit_code == proxy_main.EXIT_ARTIFACT  # the recorder refused to start; injection happened before
    [process] = _Recorder.instances
    assert process.env_overrides == {"GOOSE_MODEL": "m", "OPENAI_API_KEY": CREDENTIAL}
    assert "OPENAI_API_KEY" not in os.environ or os.environ["OPENAI_API_KEY"] != CREDENTIAL
    assert path.exists(), "the proxy must not delete the plugin-owned credential file"


def test_startup_diagnostics_log_the_key_name_never_the_value(tmp_path, monkeypatch):
    credential, _ = proxy_main.load_inference_credential(str(_credential_file(tmp_path)))
    diagnostics: list[str] = []
    _run(monkeypatch, credential=credential, diagnostics=diagnostics.append)
    startup = [line for line in diagnostics if "telemetry policy active" in line]
    assert startup and "inference_credential_env=OPENAI_API_KEY" in startup[0]
    assert all(CREDENTIAL not in line for line in diagnostics)


def test_agent_env_collision_with_the_credential_key_is_a_usage_error(tmp_path, monkeypatch):
    credential, _ = proxy_main.load_inference_credential(str(_credential_file(tmp_path)))
    diagnostics: list[str] = []
    exit_code = _run(
        monkeypatch, agent_env={"OPENAI_API_KEY": "argv-value"}, credential=credential,
        diagnostics=diagnostics.append,
    )
    assert exit_code == proxy_main.EXIT_USAGE
    assert _Recorder.instances == []  # refused before any launch
    assert any("must not set the inference credential variable" in line for line in diagnostics)


def test_credential_file_path_falls_back_to_the_entry_environment(tmp_path):
    env_var = proxy_main.INFERENCE_CREDENTIAL_FILE_ENV_VAR
    assert proxy_main.resolve_inference_credential_file(None, {}) is None
    assert proxy_main.resolve_inference_credential_file(None, {env_var: "  "}) is None
    assert proxy_main.resolve_inference_credential_file(None, {env_var: "/from/env"}) == "/from/env"
    assert proxy_main.resolve_inference_credential_file("/flag", {env_var: "/from/env"}) == "/flag"
    assert proxy_main.resolve_inference_credential_file("  ", {env_var: "/from/env"}) == "/from/env"


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        json.dumps(["list"]),
        json.dumps({"schema_version": "2", "credential_env_key": "OPENAI_API_KEY", "credential": "x"}),
        json.dumps({"schema_version": "1", "credential_env_key": "BAD KEY", "credential": "x"}),
        json.dumps({"schema_version": "1", "credential_env_key": "OPENAI_API_KEY", "credential": ""}),
        json.dumps({"schema_version": "1", "credential_env_key": "OPENAI_API_KEY", "credential": "with\nnewline"}),
        json.dumps({"schema_version": "1", "credential_env_key": "OPENAI_API_KEY", "credential": "x" * (70 * 1024)}),
    ],
)
def test_malformed_credential_files_are_usage_errors(tmp_path, content):
    path = tmp_path / "bad.json"
    path.write_text(content, encoding="utf-8")
    credential, error = proxy_main.load_inference_credential(str(path))
    assert credential is None and error is not None
    exit_code = proxy_main.main(
        [
            "--agent-digest", python_digest(),
            "--inference-credential-file", str(path),
            "--agent-cmd", sys.executable, "-c", "pass",
        ]
    )
    assert exit_code == proxy_main.EXIT_USAGE


def test_missing_credential_file_retries_briefly_then_refuses(tmp_path):
    missing = tmp_path / "missing.json"
    clock = {"now": 0.0}
    sleeps: list[float] = []

    def sleep(seconds):
        sleeps.append(seconds)
        clock["now"] += seconds

    credential, error = proxy_main.load_inference_credential_with_retry(
        str(missing), timeout_seconds=1.0, interval_seconds=0.25, sleep=sleep, monotonic=lambda: clock["now"]
    )
    assert credential is None and error is not None and sleeps
    # The file appears during the window: the launch proceeds.
    calls = {"n": 0}

    def sleep_then_write(seconds):
        calls["n"] += 1
        if calls["n"] == 2:
            _credential_file(tmp_path).replace(missing)
        clock["now"] += seconds

    clock["now"] = 0.0
    credential, error = proxy_main.load_inference_credential_with_retry(
        str(missing), timeout_seconds=1.0, interval_seconds=0.25, sleep=sleep_then_write, monotonic=lambda: clock["now"]
    )
    assert error is None and credential.value == CREDENTIAL


def test_main_wires_the_flag_and_the_env_fallback(tmp_path, monkeypatch):
    path = _credential_file(tmp_path)
    captured = {}

    def fake_run_proxy(**kwargs):
        captured.update(kwargs)
        return proxy_main.EXIT_OK

    monkeypatch.setattr(proxy_main, "run_proxy", fake_run_proxy)
    base = ["--agent-digest", python_digest(), "--agent-cmd", sys.executable, "-c", "pass"]
    assert proxy_main.main(["--inference-credential-file", str(path), *base]) == proxy_main.EXIT_OK
    assert captured["inference_credential"] == proxy_main.InferenceCredential("OPENAI_API_KEY", CREDENTIAL)
    captured.clear()
    monkeypatch.setenv(proxy_main.INFERENCE_CREDENTIAL_FILE_ENV_VAR, str(path))
    assert proxy_main.main(base) == proxy_main.EXIT_OK
    assert captured["inference_credential"].env_key == "OPENAI_API_KEY"
    captured.clear()
    monkeypatch.delenv(proxy_main.INFERENCE_CREDENTIAL_FILE_ENV_VAR)
    assert proxy_main.main(base) == proxy_main.EXIT_OK
    assert captured["inference_credential"] is None


def test_inherited_provider_variables_are_dropped_while_overrides_win():
    environment = build_environment(
        source={"HOME": "/home/p", "OPENAI_API_KEY": "participant-key", "GOOSE_PROVIDER": "anthropic", "PATH": "/bin"},
        overrides={"OPENAI_API_KEY": CREDENTIAL, "GOOSE_PROVIDER": "openai"},
    )
    assert environment == {"HOME": "/home/p", "PATH": "/bin", "OPENAI_API_KEY": CREDENTIAL, "GOOSE_PROVIDER": "openai"}
