# Running Codex locally

Codex runs as an ACP agent (`codex-acp`, vendored in `./codex-acp`) whose LLM calls
are routed through the plugin's local proxy → Code4Me server → OpenAI.

## Prerequisites
- **Node.js ≥ 18** and **npm** on your `PATH` (tested with Node 24 / npm 11).
- The **Code4Me server** running locally with OpenAI access (see below).
- JetBrains IDE able to build/run the plugin.

## 1. Server (`server/` in this repo)
In `.env`:
```env
OPENAI_API_KEY=sk-...          # your OpenAI key — required for the Codex passthrough
OPENAI_MODEL=gpt-5.1-codex-mini # optional: pin a model (omit to forward Codex's choice)
```
Restart the server after editing `.env` (it's read at startup).

## 2. Plugin
```bash
./gradlew clean runIde   # 'clean' is required, otherwise the proxy isn't started
```
In the sandbox IDE:
1. **Log in to Code4Me** — without a session token the plugin skips writing `acp.json`.
2. That's it. On startup the plugin automatically:
   - runs `npm ci` in `codex-acp/` if `node_modules/` is missing (first run only, ~1–2 min),
   - writes `~/.jetbrains/acp.json` with **`Goose (Code4Me)`** and **`Codex (Code4Me)`** entries.

## 3. Use it
Open the agent / AI Chat panel and select **`Codex (Code4Me)`**.

## What's configured automatically (don't do these by hand)
- `npm install` — auto on startup.
- `acp.json` Codex entry — auto, with `CODEX_PROXY_URL` → local proxy and a dummy
  `OPENAI_API_KEY` (`code4me-local-proxy`); the real key is injected server-side.

## Troubleshooting
- **No `Codex (Code4Me)` entry** → check idea.log for `[AgentStartupManager] codex…`;
  usually `node_modules` missing or `npm` not on `PATH`.
- **`400 … 'low' is not supported … text.verbosity`** → `gpt-5.1-codex-mini` only allows
  `medium`; the server reconciles this — make sure you **restarted the server**.
- **Connection refused** → proxy port is `localProxyPort` (default `47362`); confirm the
  server is up.
