# codex-acp proxy (vendored)

This directory vendors the [`codex-acp`](https://github.com/agentclientprotocol/codex-acp)
agent so the Code4Me **Agent** mode can launch Codex over ACP, with its LLM calls
routed through the plugin's local proxy for telemetry.

## Provenance
- **Upstream:** https://github.com/agentclientprotocol/codex-acp.git
- **Base commit:** `509ed1aa914123cef20b911721883827d9816e05` (tag `v0.0.45`, branch `main`)
- **Local patch:** `src/CodexAcpClient.ts` — routes the agent's LLM calls to the
  Code4Me local proxy when `CODEX_PROXY_URL` is set (see `../codex-acp.patch`).

## Setup
See **[SETUP.md](SETUP.md)** for the local run guide. In short: nothing manual — on IDE
startup the plugin auto-runs `npm ci` (if `node_modules/` is missing) and registers a
`Codex (Code4Me)` entry in `~/.jetbrains/acp.json`.

> `node_modules/` and `dist/` are gitignored — only source is committed.
