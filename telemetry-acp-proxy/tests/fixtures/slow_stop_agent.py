"""Fixture ACP agent that is slow to stop: it echoes stdin lines, ignoring SIGTERM.

Stands in for an agent that takes long to exit (one that has run turns and owns
an MCP subprocess): stopping it takes the proxy's whole SIGTERM-to-SIGKILL
escalation. It still exits at stdin EOF, so it never outlives its proxy.
"""

import signal
import sys

signal.signal(signal.SIGTERM, signal.SIG_IGN)
for line in sys.stdin.buffer:
    sys.stdout.buffer.write(line)
    sys.stdout.buffer.flush()
