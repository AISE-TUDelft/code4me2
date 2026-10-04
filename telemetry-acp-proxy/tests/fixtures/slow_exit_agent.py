"""Fixture ACP agent that is slow to exit once its stdin closes.

Stands in for a real agent that saves its state when the chat ends (the built-in
agent stores chat memory over HTTP): after stdin EOF it keeps running for a few
seconds before exiting, and it ignores SIGTERM meanwhile.
"""

import signal
import sys
import time

signal.signal(signal.SIGTERM, signal.SIG_IGN)
for line in sys.stdin.buffer:
    sys.stdout.buffer.write(line)
    sys.stdout.buffer.flush()
time.sleep(3)
