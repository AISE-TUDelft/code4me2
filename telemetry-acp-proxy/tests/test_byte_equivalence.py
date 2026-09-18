"""Byte-equivalence: direct vs proxied fixture agent, stdout protocol-only."""

from __future__ import annotations

import json
import os
import subprocess
import sys

from conftest import (  # type: ignore[import-not-found]
    COMPONENT_DIR,
    fixture_path,
    python_digest,
)

from telemetry_acp_proxy.framing import encode_message


def _transcript_bytes() -> bytes:
    entries = json.loads(fixture_path("acp_transcript.json").read_text())
    return b"".join(encode_message(entry["message"]) for entry in entries)


def _run(argv, payload: bytes, *, cwd=None, extra_env=None):
    env = dict(os.environ)
    if extra_env:
        env.update(extra_env)
    process = subprocess.Popen(
        argv,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=cwd,
        env=env,
    )
    out, err = process.communicate(payload, timeout=30)
    return process.returncode, out, err


def test_direct_and_proxied_stdout_are_byte_identical():
    agent = fixture_path("echo_agent.py")
    digest = python_digest()
    payload = _transcript_bytes()

    direct_rc, direct_out, _ = _run([sys.executable, str(agent)], payload)

    proxy_argv = [
        sys.executable,
        "-m",
        "telemetry_acp_proxy.main",
        "--agent-digest",
        digest,
        "--agent-cmd",
        sys.executable,
        str(agent),
    ]
    proxy_rc, proxy_out, proxy_err = _run(
        proxy_argv,
        payload,
        cwd=str(COMPONENT_DIR),
        extra_env={"PYTHONPATH": str(COMPONENT_DIR)},
    )

    assert direct_rc == 0
    assert proxy_rc == 0
    assert proxy_out == direct_out
    # Diagnostics, if any, go to stderr only.
    assert isinstance(proxy_err, bytes)


def test_proxy_stdout_contains_only_protocol_frames():
    agent = fixture_path("echo_agent.py")
    digest = python_digest()
    payload = _transcript_bytes()

    proxy_argv = [
        sys.executable,
        "-m",
        "telemetry_acp_proxy.main",
        "--agent-digest",
        digest,
        "--agent-cmd",
        sys.executable,
        str(agent),
    ]
    _, proxy_out, _ = _run(
        proxy_argv,
        payload,
        cwd=str(COMPONENT_DIR),
        extra_env={"PYTHONPATH": str(COMPONENT_DIR)},
    )

    lines = [line for line in proxy_out.split(b"\n") if line.strip()]
    assert lines
    for line in lines:
        decoded = json.loads(line.decode("utf-8"))  # raises if a diagnostic leaked
        assert "jsonrpc" in decoded
