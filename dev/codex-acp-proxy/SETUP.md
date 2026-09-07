# Running Codex locally

Codex runs as an ACP agent (`codex-acp`, vendored in `./codex-acp`) whose LLM calls
are routed through the plugin's local proxy → Code4Me server → OpenAI.

## Prerequisites
- **Node.js ≥ 18** and **npm** on your `PATH` (tested with Node 24 / npm 11).
- **Goose** binary on `PATH`, or installed through JetBrains AI Assistant's agent picker —
  optional. Goose and Codex are registered independently, so Codex works fine without Goose
  installed (see `AgentStartupManager`/`GooseRuntime`).
- The **Code4Me backend server** — a separate project from this plugin repo — running locally
  with OpenAI access (see below).
- JetBrains IDE able to build/run the plugin.

## 1. Code4Me backend server (separate repo, not part of this repository)
In that project's `.env`:
```env
OPENAI_API_KEY=sk-...          # your OpenAI key — required for the Codex passthrough
OPENAI_MODEL=gpt-5.1-codex-mini # optional: pin a model (omit to forward Codex's choice)
```
Restart the server after editing `.env` (it's read at startup). If the plugin's `server.host`
in `plugin.conf` can't reach it as written (e.g. the agent runs in a container), set
`server.acpRuntimeBaseUrl` in `plugin.conf` to an address it can reach.

## 2. Plugin
```bash
./gradlew clean :runIde   # 'clean' is required, otherwise the proxy isn't started
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
- **No `Goose (Code4Me)` entry, plus a "Goose runtime not found" notification** → no Goose binary
  was found on `PATH`, in the JetBrains ACP agent registry, or in an existing `acp.json`. This does
  not affect `Codex (Code4Me)`, which is registered independently. Install Goose (or pick an agent
  from AI Assistant's agent list) and reopen the project.
- **No `Codex (Code4Me)` entry, but `Goose (Code4Me)` is present** → check idea.log for
  `[AgentStartupManager] codex…`; usually `node_modules` missing or `npm` not on `PATH`.
- **`400 … 'low' is not supported … text.verbosity`** → `gpt-5.1-codex-mini` only allows
  `medium`; the server reconciles this — make sure you **restarted the server**.
- **Connection refused** → proxy port is `localProxyPort` (default `47362`); confirm the
  server is up.
- **`Codex (Code4Me)` entry never appears (or vanishes after previously working), with
  `idea.log` showing "native Codex binary is missing"** → this is a known macOS Gatekeeper/XProtect
  false positive that quarantines/deletes the vendored `@openai/codex` native binary as "malware"
  ([openai/codex#31377](https://github.com/openai/codex/issues/31377)), independent of anything
  this plugin does. `node_modules/` stays intact — only the Mach-O binary under
  `node_modules/@openai/codex-darwin-*/vendor/*/bin/codex` gets removed, which is why the plugin
  checks for that file specifically rather than just `node_modules/`. Bumping the pinned
  `@openai/codex` version in `codex-acp/package.json` to a newer release (not yet matched by
  XProtect's signature database) and re-running `npm install` is the community-reported fix;
  re-running the *same* version will just get flagged again.
