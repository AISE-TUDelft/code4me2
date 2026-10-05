# Participant plugin and study agents

All participants install the same plugin ZIP, from JetBrains Marketplace once
published there. The plugin bundles **no agent**. A study assignment pins its agent
release. When a participant prepares the study, the plugin uses the verified cache
entry for that archive's SHA-256, or downloads the exact archive from the public
GitHub Release of `AISE-TUDelft/code4me2-server`. It checks the archive's size,
SHA-256 and contents, and only then installs and runs it.

A plugin update therefore never changes which agent a study runs. A new agent
version needs no new plugin, only a runtime release, its registration on the
server, and a study that selects it. Goose and Codex remain participant-installed
ACP agents.

The plugin ZIP carries the research proxy for macOS arm64/x64, Linux x64 and
Windows x64, built from the server's shared contract sources.

## 1. Publish a runtime release (server repository)

Run **Actions → Build managed runtime** in `code4me2-server`:

- `version`: an unused runtime version.
- `publishRelease`:
  - `true` publishes the immutable `runtime-v<version>` GitHub Release: the four
    agent archives plus `code4me-managed-runtime-release.json`, whose artifacts
    carry their exact `download_url`.
  - `false` is a dry run. It leaves only the combined Actions artifact
    `code4me-managed-runtime-release`. That artifact has no download URLs, so
    participants cannot use it.
- `sign` (default `false`): code-sign the archives; the macOS archive is also
  notarized.
  - Participants don't need it. The plugin downloads, verifies and launches the
    agent itself, so Gatekeeper and SmartScreen never assess it.
  - It mainly helps Windows hosts with Smart App Control or strict antivirus.
  - It needs the signing secrets (see `code4me2-server/packaging/README.md`) and
    produces archives with a new SHA-256.
- `minPluginVersion`: optional. The server refuses plugins older than this for
  studies that use the release.

A repository admin should enable **Settings → General → Releases → Enable release
immutability** on the server repository. Published assets and tags then cannot
change. The workflow already refuses to replace an existing release.

## 2. Register the release (website)

Open **Admin → Agents → Import a runtime manifest and its archives**. Then either:

- tick **Import from release URLs** and enter the Release URL of
  `code4me-managed-runtime-release.json` and the four archive URLs; or
- upload that JSON with the four ZIPs it declares. Use the original Release files,
  never re-zipped copies or the outer Actions download ZIP.

The server checks every archive's size and SHA-256 against the manifest and keeps
each artifact's `download_url`. It accepts download URLs only for exact release
assets of `AISE-TUDelft/code4me2-server`; `latest` URLs are rejected.

Select the release in an agent profile and that profile in a study. The study's
frozen assignment pins the archive SHA-256, and later profile edits do not change
a running study.

## 3. Build the participant plugin (plugin repository)

Run **Actions → Build participant plugin**:

| Input | Meaning |
|---|---|
| `serverUrl` | Backend origin baked into the plugin. Use `https://…` for a Marketplace candidate, or `http://localhost:<port>` with a prerelease version for a test ZIP. |
| `version` | Plugin SemVer version. |
| `serverRepository`, `serverTag` | Server repository and published release tag supplying the shared proxy sources. There is no default tag. The workflow checks that the release is published, validates the source files, and resolves one commit for all four proxy builds. |
| `fullHostSmoke` | Run the plugin tests, then the agent installer tests on all four hosts. It does not start the final ZIP in IntelliJ: `ui-tests` has no ZIP smoke test yet. |

The server tag selects proxy source code independently of the study's agent. The build:

1. builds four self-contained proxies;
2. runs `./gradlew buildPlugin` with `-Pcode4me.serverUrl`;
3. runs `scripts/verify-participant-artifact.py --agent-free`. This checks that the
   ZIP contains all four proxies, no `code4me-runtime/` agent, and no credentials
   or developer paths.

The `participant-release-evidence` artifact's `build-report.json` records the
plugin and server commits and the ZIP SHA-256. Manual runs build artifacts only.
To publish to Marketplace and create a GitHub Release with the ZIP attached, push
a stable `vX.Y.Z` tag. See the publishing settings in `README.md`.
Tag-triggered releases read the server source tag from `CODE4ME_RELEASE_SERVER_TAG`;
manual builds use the `serverTag` input. Neither requires you to supply a commit SHA.

Until the workflow is on `main`:

- A push to `feat/plugin` builds a test ZIP with version
  `0.0.1-branch.g<plugin SHA prefix>`.
- Optional repository variables:
  - `CODE4ME_BRANCH_SERVER_URL` (default `http://localhost:18080`)
  - `CODE4ME_BRANCH_SERVER_REPOSITORY` (default `AISE-TUDelft/code4me2-server`)
  - `CODE4ME_BRANCH_SERVER_REF` (default `feat/plugin`; must be a full commit SHA
    when the server URL is HTTPS)
- If the server repository needs a token, set `CODE4ME_RELEASE_TOKEN` with
  contents read access.
- GitHub shows the **Run workflow** button only for workflows on the default
  branch. After a branch push has registered the workflow, the API can dispatch it.

## Identity and compatibility

`DistributionArtifact.sha256` is the archive hash. The bootstrap pins
`agent_release.artifact_digest` and carries the selected platform artifact's
`download_url`, size, executable and managed protocol.

The plugin refuses:

- an archive whose SHA-256 differs from the pin;
- a URL outside the canonical repository, or a `latest` URL;
- a redirect away from GitHub's release hosts;
- any size, digest or content mismatch.

Installation never executes the archive. A fresh bootstrap authorizes the native
self-check and the launch.

A verified cache entry is used without a download. A cache miss needs HTTPS
access to `github.com`, `release-assets.githubusercontent.com` and
`objects.githubusercontent.com` on the participant's machine. A cache miss
without a URL blocks the study, and it never falls back to a bundled or unrelated agent. So a
study pinned to an older release without `download_url` runs only where its
archive is already cached. For participants, assign a release imported from a
published runtime Release.

## Local development and e2e

A local build can target a loopback backend with
`-Pcode4me.serverUrl=http://localhost:8008 -PparticipantLocalRelease=true`.

Studies never use a bundled archive. To run a locally built agent in a study:

1. Register its manifest and archive in the local backend.
2. Copy the same archive to
   `<IDE system path>/code4me/runtimes/code4me-agent/archives/<sha256>.zip`. On
   macOS the system path is usually `~/Library/Caches/JetBrains/IntelliJIdea2026.2`.
   The plugin verifies the archive's size and digest before use.

The e2e UI layer does exactly this (`code4me_e2e.runtime.seed_agent_cache`).

The recipe CLI (`scripts/participant-release.py validate|prepare|build`) and
`scripts/build-plugin-with-agent.py` still build ZIPs with a bundled agent recipe.
Only the ordinary non-study setup reads a bundled recipe, and the server refuses
that setup to users without an active study. Participant builds do not use these
tools, and releases are registered through the website import.

For three-agent recipes:

- Goose and Codex releases declare qualified ACP commands, adapters and
  configuration bindings. A version label or a discovered executable alone does
  not show that an agent is compatible.
- A Goose release must bind the five research inference gateway fields the plugin
  fills at launch:
  - `inference_gateway_host`
  - `inference_gateway_base_path`
  - `inference_gateway_credential` (`env` transport only; delivered through an
    owner-only file, never argv)
  - `provider_kind` (with a `value_map`, `openai_compatible` → `openai`)
  - `state_dir`
- The example recipe binds these to `OPENAI_HOST`, `OPENAI_BASE_PATH`,
  `OPENAI_API_KEY`, `GOOSE_PROVIDER` and `GOOSE_PATH_ROOT`. Without all five, the
  server refuses to bootstrap the arm (`INFERENCE_GATEWAY_UNBOUND`) and the plugin
  refuses to launch (`RUNTIME_UNAVAILABLE`).
- Provider credentials and participant paths never enter a recipe.

Local single-platform recipe releases need `CODE4ME_LOCAL_RELEASE=1` with
`--platforms macos-aarch64`, and are verified with `--allow-partial-platforms`.
They are local test artifacts and must not be distributed.

## Verification and limits

Unit and contract tests use small synthetic archives. They cover:

- download and cache verification;
- the canonical-repository pin;
- safe extraction;
- the agent-free ZIP verifier.

They do not prove real agent compatibility. Before participants run, you still
need:

- a published runtime Release;
- its registration and qualification on the deployed backend;
- a plugin ZIP built from the deployed server commit;
- an installed-IDE study test on each target platform, including a clean
  Windows 11 machine with Defender (and Smart App Control if participants use it).
