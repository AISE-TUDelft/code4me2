# Managed agent roadmap

## Current study milestone

The Code4Me agent is the certified participant runtime. Its installation, ACP registration, authentication, provider routing, policy, repair, and version reporting are managed by the Code4Me plugin and hosted backend.

Only profiles whose runtime is certified for the deployed participant release may be activated for a study. An unsupported assigned runtime must produce an explicit error; clients must never substitute another runtime.

## Deferred Goose and Codex work

- [ ] **Goose distribution:** replace PATH discovery with pinned, verified artifacts for each supported platform.
- [ ] **Codex distribution:** replace the source checkout, Node/npm, and development system properties with packaged or managed artifacts.
- [ ] **Shared onboarding:** add both runtimes to participant readiness checks, installation, repair, registration, and diagnostics.
- [ ] **Study enforcement:** verify assigned provider routing, tools, approval behavior, and telemetry attribution for each runtime.
- [ ] **Compatibility certification:** test installation, authentication, upgrades, rollback, and recovery on every supported platform.

The existing Goose and Codex source, server profiles, inference relay, and developer configuration remain supported for development while these items are open.
