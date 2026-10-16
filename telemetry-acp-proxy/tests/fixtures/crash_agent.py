"""Fixture ACP agent that exits non-zero after one frame (simulated crash)."""

import sys

sys.stdin.buffer.readline()
sys.stderr.write("crash-agent: simulated crash\n")
raise SystemExit(3)
