"""``--status-file``: the plugin reads the proxy's content-free drop counter.

The ACP entry emits ``--status-file`` (or ``CODE4ME_RESEARCH_STATUS_FILE``) and
the participant status surface consumes only the document's ``dropped`` integer.
The document is canonical JSON of the delivery snapshot: counters and booleans
only, never a payload, event id, path, or capability.
"""

from __future__ import annotations

import io
import json
import sys
import threading

from conftest import fixture_path, python_digest  # type: ignore[import-not-found]

from telemetry_acp_proxy import main as proxy_main
from telemetry_acp_proxy.framing import encode_message
from telemetry_acp_proxy.spool_client import SpoolSendResult

INITIALIZE_FRAME = encode_message(
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
)

#: The exact, content-free key set of the delivery status document.
STATUS_DOCUMENT_KEYS = {
    "capacity",
    "enqueued",
    "delivered",
    "dropped_full",
    "dropped_error",
    "dropped_shutdown",
    "dropped",
    "pending",
    "closed",
    "worker_alive",
    "healthy",
}

DIGEST = "sha256:" + "0" * 64


def _capture_spool(monkeypatch) -> dict:
    """Replace the spool client with one that records the delivered events."""
    sink: dict = {}

    class CapturingSpool:
        def __init__(self, endpoint, capability, **kwargs):
            pass

        def send(self, events):
            sink.setdefault("events", []).extend(events)
            return SpoolSendResult(sent=len(events), dropped=0)

    monkeypatch.setattr(proxy_main, "LocalSpoolClient", CapturingSpool)
    return sink


def _agent_argv(*proxy_args: str) -> list[str]:
    """Proxy flags, then the REMAINDER ``--agent-cmd`` (which must be last)."""
    return [
        "--agent-digest",
        python_digest(),
        "--spool-endpoint",
        "file:///tmp/status-file-spool.jsonl",
        "--capability",
        "cap-1",
        *proxy_args,
        "--agent-cmd",
        sys.executable,
        str(fixture_path("echo_agent.py")),
    ]


def _run_via_main(
    monkeypatch,
    argv: list[str],
    *,
    host_read: bytes = INITIALIZE_FRAME,
    diagnostics: list[str] | None = None,
) -> int:
    """Run ``main`` end to end with a scripted host stdin/stdout."""
    real_run = proxy_main.run_proxy

    def scripted_run(**kwargs):
        kwargs.setdefault("host_read", io.BytesIO(host_read))
        kwargs.setdefault("host_write", io.BytesIO())
        if diagnostics is not None:
            kwargs.setdefault("diagnostics", diagnostics.append)
        return real_run(**kwargs)

    monkeypatch.setattr(proxy_main, "run_proxy", scripted_run)
    return proxy_main.main(argv)


def test_parser_accepts_the_status_file_flag():
    args = proxy_main.build_parser().parse_args(
        [
            "--agent-digest",
            DIGEST,
            "--status-file",
            "/tmp/status.json",
            "--agent-cmd",
            "agent",
        ]
    )
    assert args.status_file == "/tmp/status.json"


def test_resolve_status_file_prefers_the_flag_then_the_environment():
    env = {proxy_main.STATUS_FILE_ENV_VAR: "env-status.json"}
    assert proxy_main.resolve_status_file("flag-status.json", env) == "flag-status.json"
    assert proxy_main.resolve_status_file(None, env) == "env-status.json"
    assert proxy_main.resolve_status_file("   ", env) == "env-status.json"
    assert proxy_main.resolve_status_file(None, {}) is None
    assert proxy_main.resolve_status_file(None, {proxy_main.STATUS_FILE_ENV_VAR: "   "}) is None


def test_status_file_flag_writes_a_content_free_document(monkeypatch, tmp_path):
    status_path = tmp_path / "status.json"
    _capture_spool(monkeypatch)

    exit_code = _run_via_main(monkeypatch, [*_agent_argv("--status-file", str(status_path))])

    assert exit_code == proxy_main.EXIT_OK
    document = json.loads(status_path.read_text(encoding="utf-8"))
    assert set(document) == STATUS_DOCUMENT_KEYS
    assert isinstance(document["dropped"], int)
    assert document["dropped"] == 0
    assert document["healthy"] is True
    # Content-free by construction: only counters and booleans travel.
    assert all(isinstance(value, (int, bool)) for value in document.values())


def test_status_document_never_carries_payload_or_id_content(monkeypatch, tmp_path):
    sentinel = "sentinel-4b1c9e-do-not-leak"
    frame = encode_message(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": sentinel,
            "params": {"sessionId": sentinel},
        }
    )
    status_path = tmp_path / "status.json"
    sink = _capture_spool(monkeypatch)

    exit_code = _run_via_main(
        monkeypatch,
        [*_agent_argv("--status-file", str(status_path))],
        host_read=frame,
    )

    assert exit_code == proxy_main.EXIT_OK
    assert sink["events"], "the activation must have produced canonical events"
    raw = status_path.read_text(encoding="utf-8")
    assert sentinel not in raw
    document = json.loads(raw)
    assert set(document) == STATUS_DOCUMENT_KEYS


def test_environment_status_file_is_used_when_the_flag_is_absent(monkeypatch, tmp_path):
    status_path = tmp_path / "status.json"
    monkeypatch.setenv(proxy_main.STATUS_FILE_ENV_VAR, str(status_path))
    _capture_spool(monkeypatch)

    exit_code = _run_via_main(monkeypatch, _agent_argv())

    assert exit_code == proxy_main.EXIT_OK
    document = json.loads(status_path.read_text(encoding="utf-8"))
    assert document["dropped"] == 0


def test_flag_wins_over_the_environment(monkeypatch, tmp_path):
    flag_path = tmp_path / "flag.json"
    env_path = tmp_path / "env.json"
    monkeypatch.setenv(proxy_main.STATUS_FILE_ENV_VAR, str(env_path))
    _capture_spool(monkeypatch)

    exit_code = _run_via_main(monkeypatch, [*_agent_argv("--status-file", str(flag_path))])

    assert exit_code == proxy_main.EXIT_OK
    assert flag_path.exists()
    assert not env_path.exists(), "the losing env path must never be written"


def test_no_status_path_writes_nothing(monkeypatch, tmp_path):
    monkeypatch.delenv(proxy_main.STATUS_FILE_ENV_VAR, raising=False)
    _capture_spool(monkeypatch)

    exit_code = _run_via_main(monkeypatch, _agent_argv())

    assert exit_code == proxy_main.EXIT_OK
    assert list(tmp_path.iterdir()) == []


def test_status_write_failure_is_logged_and_swallowed(tmp_path):
    messages: list[str] = []

    proxy_main.write_status_document(
        str(tmp_path / "missing-dir" / "status.json"),
        proxy_main._zero_delivery_snapshot(),
        messages.append,
    )

    assert messages and "status file" in messages[0]


def test_unwritable_status_path_does_not_change_the_exit_code(monkeypatch, tmp_path):
    _capture_spool(monkeypatch)
    baseline = _run_via_main(monkeypatch, _agent_argv())

    unwritable = tmp_path / "missing-dir" / "status.json"
    diagnostics: list[str] = []
    coded = _run_via_main(
        monkeypatch,
        [*_agent_argv("--status-file", str(unwritable))],
        diagnostics=diagnostics,
    )

    assert baseline == proxy_main.EXIT_OK
    assert coded == baseline
    assert not unwritable.exists()
    assert any("status file" in message for message in diagnostics)


def test_concurrent_status_writers_never_race_on_a_shared_path(monkeypatch, tmp_path):
    """Concurrent proxies sharing one ``--status-file`` must not race staging.

    Several proxy processes for the same enrollment are configured with the same
    path. A single fixed ``<path>.tmp`` staging file lets one writer replace the
    file out from under another, so the loser's ``os.replace`` fails with
    ``FileNotFoundError`` (the observed ``.tmp -> ...json`` diagnostic). Every
    writer must stage to a uniquely named temporary file in the same directory.
    """
    status_path = tmp_path / "status.json"
    writers = 16
    diagnostics: list[str] = []
    failures: list[BaseException] = []
    barrier = threading.Barrier(writers)
    real_replace = proxy_main.os.replace
    staged: list[str] = []
    staged_lock = threading.Lock()

    def interleaved_replace(source, destination):
        with staged_lock:
            staged.append(str(source))
        # Release every writer together so all replacements race the same
        # destination; with a shared staging file, every replace but the first
        # would fail.
        barrier.wait(timeout=10)
        return real_replace(source, destination)

    monkeypatch.setattr(proxy_main.os, "replace", interleaved_replace)

    def write_status(index: int) -> None:
        snapshot = proxy_main._zero_delivery_snapshot()
        snapshot["enqueued"] = index
        snapshot["dropped"] = index
        try:
            proxy_main.write_status_document(str(status_path), snapshot, diagnostics.append)
        except BaseException as error:  # pragma: no cover - asserted below
            failures.append(error)

    threads = [threading.Thread(target=write_status, args=(index,)) for index in range(writers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)

    assert not failures, f"status writers raised: {failures}"
    assert all(not thread.is_alive() for thread in threads), "a status writer did not finish"
    assert diagnostics == [], f"concurrent status writes failed: {diagnostics}"
    document = json.loads(status_path.read_text(encoding="utf-8"))
    assert set(document) == STATUS_DOCUMENT_KEYS
    # Every writer must have staged through its own temporary path: a shared
    # ``status.json.tmp`` collapses this set to one entry.
    assert len(staged) == writers
    assert len(set(staged)) == writers
