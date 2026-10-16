"""Fixture ACP agent: echoes every stdin line back, byte-for-byte.

Used by the direct-vs-proxied byte-equivalence test. Reading/writing raw bytes
(never decoding) guarantees the proxy must forward exactly the original frames.
"""

import sys

for line in sys.stdin.buffer:
    sys.stdout.buffer.write(line)
    sys.stdout.buffer.flush()
