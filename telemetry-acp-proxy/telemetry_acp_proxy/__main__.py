"""``python -m telemetry_acp_proxy``: the Windows bundle starts the proxy this way."""

from telemetry_acp_proxy.main import main

if __name__ == "__main__":
    raise SystemExit(main())
