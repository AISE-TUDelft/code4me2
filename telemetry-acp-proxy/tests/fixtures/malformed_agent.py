"""Fixture ACP agent that emits malformed JSON, then exits cleanly."""

import sys

sys.stdin.buffer.readline()
sys.stdout.buffer.write(b"{ this is not valid json }\n")
sys.stdout.buffer.flush()
raise SystemExit(0)
