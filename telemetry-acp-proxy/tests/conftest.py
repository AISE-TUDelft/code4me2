"""Self-bootstrapping test configuration.

Inserts the component directory into ``sys.path`` so ``import telemetry_acp_proxy``
works regardless of the pytest rootdir. ``pytest.ini`` disables the venv's
pytest-postgresql plugin (its psycopg wheel does not import under this Python),
which is unrelated to the proxy.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

COMPONENT_DIR = Path(__file__).resolve().parents[1]
if str(COMPONENT_DIR) not in sys.path:
    sys.path.insert(0, str(COMPONENT_DIR))

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def fixture_path(name: str) -> Path:
    """Return the absolute path to a test fixture."""
    return FIXTURES / name


def sha256_digest(path: Path) -> str:
    """Return the ``sha256:<hex>`` digest of a file."""
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def python_digest() -> str:
    """Return the digest of the current Python interpreter (the pinned argv[0])."""
    return sha256_digest(Path(sys.executable))
