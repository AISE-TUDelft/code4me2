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
5. Open **Settings > Tools > Code4Me V2**, sign in with your study account, and wait for the agent status to show **Ready**.
6. Open AI Chat and select **Code4Me Agent** from the agent picker.

Code4Me installs its matching agent runtime from the plugin. You do not need a Code4Me source checkout, Python, Node.js, Docker, or a model-provider API key. Your project may still require its normal compiler or build tools.

## Repair and diagnostics

If setup does not reach **Ready**, use **Repair agent** in the Code4Me settings. Restart the IDE after a repair if AI Chat has already been opened.

When reporting a problem, include the Code4Me plugin version, runtime version, operating system, CPU architecture, and the status message shown in settings. Do not enable extended ACP logging or share project contents unless the study coordinator specifically requests it.

Goose and Codex integrations are retained for development but are not participant-ready in this release.

Study coordinators: distribute only the versioned artifact from the **Build participant plugin** release workflow. A ZIP produced by the ordinary Gradle `buildPlugin` task is a development artifact and must not be shared with participants.
