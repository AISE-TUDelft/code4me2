"""``--agent-cmd`` parsing: REMAINDER keeps vendor flags out of the proxy CLI.

The proxy's own flags must precede ``--agent-cmd``; everything after it is the
agent argv verbatim, including flags the proxy does not know (``--managed``).
"""

from __future__ import annotations

import pytest

from telemetry_acp_proxy import main as proxy_main

DIGEST = "sha256:" + "0" * 64


def _parse(agent_argv):
    return proxy_main.build_parser().parse_args(
        ["--agent-digest", DIGEST, "--agent-cmd", *agent_argv]
    )


def test_single_token_agent_command():
    args = _parse(["/opt/code4me/agents/codex-agent"])
    assert args.agent_cmd == ["/opt/code4me/agents/codex-agent"]


def test_multi_token_agent_command():
    args = _parse(["python", "script.py"])
    assert args.agent_cmd == ["python", "script.py"]


def test_vendor_flag_after_agent_bin_is_not_a_proxy_argument():
    # Regression: the observed ``unrecognized arguments: --managed`` came from
    # ``nargs="+"`` stopping at the unknown flag.
    args = _parse(["code4me-agent", "--managed"])
    assert args.agent_cmd == ["code4me-agent", "--managed"]


def test_vendor_flag_with_value_and_agent_positional():
    args = _parse(["code4me-agent", "--managed", "--profile", "prod", "run"])
    assert args.agent_cmd == ["code4me-agent", "--managed", "--profile", "prod", "run"]


def test_proxy_flags_before_agent_cmd_are_still_parsed():
    args = proxy_main.build_parser().parse_args(
        [
            "--agent-digest",
            DIGEST,
            "--adapter",
            "codex-v1",
            "--spool-endpoint",
            "file:///tmp/spool.jsonl",
            "--capability",
            "token",
            "--agent-cmd",
            "code4me-agent",
            "--managed",
        ]
    )
    assert args.adapter == "codex-v1"
    assert args.spool_endpoint == "file:///tmp/spool.jsonl"
    assert args.capability == "token"
    assert args.agent_cmd == ["code4me-agent", "--managed"]


def test_empty_agent_cmd_is_a_usage_error(capsys):
    exit_code = proxy_main.main(["--agent-digest", DIGEST, "--agent-cmd"])
    assert exit_code == proxy_main.EXIT_USAGE
    assert "agent" in capsys.readouterr().err


def test_agent_cmd_is_required():
    with pytest.raises(SystemExit):
        proxy_main.build_parser().parse_args(["--agent-digest", DIGEST])
