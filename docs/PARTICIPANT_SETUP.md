# Code4Me study setup

## Supported computers

The study release supports IntelliJ IDEA 2026.2.2 with the current AI Assistant plugin on:

- macOS 15 on Apple Silicon or Intel
- Windows 11 x64
- Ubuntu 24.04 x64

WSL, remote development, containers, and Windows/Linux ARM64 are not supported by this release.

## Install and start

1. Install or update JetBrains AI Assistant from **Settings > Plugins**.
2. Download the versioned Code4Me ZIP supplied by the study coordinator.
3. In **Settings > Plugins**, choose **Install Plugin from Disk**, select the ZIP, and restart the IDE when prompted.
4. Open the project you will use in the study.
5. Open **Settings > Tools > Code4Me V2** and sign in with your study account.
6. Join the study from the Code4Me research settings using the enrollment code
   from your study coordinator. Code4Me activates the study and registers the
   **Code4Me Research Proxy** entry for this project.
7. Open AI Chat and select **Code4Me Research Proxy** from the agent picker.

The research activation path is the one authoritative participant entry. While a
study is active for your project, Code4Me does not register or offer the direct
**Code4Me Agent** entry: choosing **Prepare agent** (or the
**Prepare Code4Me Agent Session** action) shows a redirect to the research entry
instead. A session started through any other entry is not observed by the study.

Code4Me installs its matching agent runtime from the plugin. You do not need a Code4Me source checkout, Python, Node.js, Docker, or a model-provider API key. Your project may still require its normal compiler or build tools.

If your study runs Goose, the study provides the model access: Code4Me points
Goose at the study's server and hands it a study-issued credential at each
start. Your own Goose provider keys and `~/.config/goose` settings are not used
in a study, and the study's credential is never shown to you. When your study's
AI budget is used up, the status bar shows **Research: AI budget used up**: the
agent refuses new requests until the study team tops the budget up, while
research collection continues.

## Repair and diagnostics

If the study does not become active, reopen the project or rejoin from the
Code4Me research settings. If setup does not reach **Ready**, use **Repair
agent** in the Code4Me settings; during an active study that action reports the
research redirect instead of touching the managed entry. Restart the IDE after a
repair if AI Chat has already been opened.

When reporting a problem, include the Code4Me plugin version, runtime version, operating system, CPU architecture, and the status message shown in settings. Do not enable extended ACP logging or share project contents unless the study coordinator specifically requests it.

## Developer-only surfaces

These paths are retained for development and are **not participant-ready** in
this release. They are gated, are absent from participant flows, and must never
be used for a study session:

- **Local development proxy** (`LocalProxyServer`): starts only when the
  developer `code4me.developerAgents` system property is enabled.
- **Goose runtime** (`GooseRuntime`) and the **vendored developer Codex**
  integration (`dev/codex-acp-proxy/`): not participant-ready.
- **Developer agents** (`prepareDeveloperAgents()`): a no-op unless
  `-Dcode4me.developerAgents=true` is set, and never run for a study-active
  project.

Study coordinators: distribute only the versioned artifact from the **Build participant plugin** release workflow. A ZIP produced by the ordinary Gradle `buildPlugin` task is a development artifact and must not be shared with participants.
