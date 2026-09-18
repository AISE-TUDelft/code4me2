"""PyInstaller entry point for the self-contained telemetry ACP proxy.

PyInstaller cannot follow a package-relative ``python -m`` invocation, so this
thin module imports the real CLI from the installed package. It is used only by
`.github/workflows/research-proxy-package.yml`; the runtime consumes the
resulting self-contained bundle, never this source file.
"""

from __future__ import annotations

from telemetry_acp_proxy.main import main

if __name__ == "__main__":
    raise SystemExit(main())
