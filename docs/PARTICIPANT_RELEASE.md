# One ZIP, one preparation workflow

All participants install the same ZIP. Code4Me's native agent and the research
proxy are bundled for macOS arm64/x64, Linux x64 and Windows x64. Goose and Codex
remain participant-installed ACP agents. Their command, adapter and configuration
contracts are declared in the recipe; the workflow never installs them.

The plugin ships exactly **one** managed agent artifact identity: the runtime
recipe `code4me-runtime/manifest.json` plus the platform archive it declares. The
research path installs that recipe through the same `ManagedRuntimeInstaller` the
managed (non-research) participant path uses, and matches it against the
bootstrap manifest's pinned `agent_release.artifact_digest` before any byte is
written. There is no release-keyed per-platform agent payload in the proxy
manifest, and no second agent copy to keep in sync.

The server still stores three immutable agent releases. The workflow derives
their IDs and registers them together; there is no release-group table or parent
pin. Studies keep their own frozen profile/release assignments. The initial
recipe supports one version of each framework, shared by any number of studies.
Additional version-comparison recipes are not implemented by this command.

## Inputs

Use the server's Python environment (Pydantic v2) and a matching server checkout.
Start from [participant-release.example.json](participant-release.example.json).
It is an intentionally invalid template: replace every REPLACE value with
actual pinned inputs. Do not fill hashes with placeholders.

- Pin the full plugin/server commits and independent plugin/agent versions.
- Supply the existing native release manifest and all four archives together.
  The recipe pins the manifest's file SHA-256; its server commit and archive
  hashes are verified. Preparation copies each archive and pins its executable,
  SHA-256 and size.
- Choose qualified Goose/Codex ACP commands, adapters and configuration bindings
  for the intended versions. A version label or a discovered executable alone
  does not demonstrate agent compatibility.
- Optional profiles use existing profile request fields: name, framework_version,
  connection_id, model, approval_policy, max_steps, tools_json, is_active,
  temperature and max_context_tokens. Provider connection IDs are non-secret;
  provider credentials and participant paths must never enter a recipe. BYOA
  continues to use participant credentials.

## Prepare and build

From the plugin repository, with the native release files in a separate input
directory:

    ../code4me2-server/.venv/bin/python scripts/participant-release.py validate \
      /path/to/recipe.json --inputs /path/to/native-release

    ../code4me2-server/.venv/bin/python scripts/participant-release.py prepare \
      /path/to/recipe.json --inputs /path/to/native-release \
      --output /path/to/prepared-release

Both commands are offline. Preparation uses a new output directory and does not
stamp source versions or change the committed runtime recipe or archive. It emits
a single recipe document plus the transport archives it pins:

- recipe.json: the one prepared release document (runtime version, plugin/server
  commits, its own adapter identity, the three agent declarations, optional
  profile templates and the per-platform archive sha256/size/executable);
- resources/code4me-runtime/: the independently checksummed native archives, one
  per declared platform;
- prepared-inputs.json: the sha256 of every emitted file.

There is no `catalog.json`, no `research-agents/` payload tree and no
release-keyed per-platform agent block: the recipe document **is** the release
inventory. The plugin derives its simplified release inventory (schema version,
plugin version, platforms and the three framework identities) directly from
recipe.json when it stages the proxy manifest, so the shipped recipe and the
archive it declares are the single managed agent identity. There is no separate
`inventory` key and no server-derived `release_id` to match. recipe.json and
prepared-inputs.json pin these outputs for later phases. Changed or missing
inputs are rejected before building or writing to a server. To apply a CI build
locally, prepare again from the uploaded recipe and the same native inputs, and
compare its recipe digest with CI evidence.

Build the research proxy on each matching native host using the pinned clean
plugin/server checkouts:

    ./gradlew --no-daemon --no-configuration-cache buildResearchProxyBundle

Collect the four resulting telemetry-acp-proxy/dist/<platform>/ directories on
the assembly host. The builder records source revisions and a hash of its
server/proxy contract sources; preparation-based staging rejects missing,
dirty or mismatched provenance.
The Gradle property researchProxyDistDir can point to an external bundle directory.

    ../code4me2-server/.venv/bin/python scripts/participant-release.py build \
      /path/to/prepared-release --server-url https://study.example.org

The build checks source pins, uses the prepared resource overlay, verifies the
actual Gradle output ZIP and writes build-report.json with its SHA-256. It
requires clean committed source inputs and never commits them for you. It does
not register releases, publish an artifact or deploy a server.

The participant GitHub workflow takes the file contents as recipeJson. Keep
the versioned recipe outside the source commit it pins (a file cannot contain
the hash of its own commit). It builds the native proxies, prepares the single
release document, verifies that recipe pins match workflow inputs, and invokes
the same build command. Existing native runtime release production remains a
prerequisite.

## Register and resume

Deploy compatible server contracts before distributing a new participant ZIP.
Set CODE4ME_RELEASE_AUTH_TOKEN outside shell history to an administrator's
auth_token cookie value. Only the explicit apply phase contacts a server:

    ../code4me2-server/.venv/bin/python scripts/participant-release.py apply \
      /path/to/prepared-release --server-url https://study.example.org

The command preflights all identities/content, registers missing leaves and
reuses exact existing records. Conflicts stop without overwriting or deleting
anything. apply-report.json records completed and pending operations. After a
timeout, rerunning reconciles the server state; no cross-request transaction is
claimed.

Registration does not qualify a release. Run actual conformance cases against
the selected artifacts/installations using the existing conformance runner.
For new managed leaves, pass the release_id to ConformanceRunner.run along with
the archive artifact_digest, the adapter_digest and the host. Supply resulting
real receipts as a JSON array:

    ../code4me2-server/.venv/bin/python scripts/participant-release.py apply \
      /path/to/prepared-release --server-url https://study.example.org \
      --receipts /path/to/real-conformance-receipts.json

The same command uploads these receipts, verifies qualification across every
managed platform, and creates/reuses compatible profile templates. Missing
qualification leaves pending entries and exits 2. Profiles with conflicting or
ambiguous existing names require a new name; existing profiles are not edited.
Use the returned profile IDs in the existing study UI.

## Identity and compatibility

DistributionArtifact.sha256 remains the archive hash. Bootstrap projects
archive_sha256 and adapter_digest separately; the bootstrap manifest pins
`agent_release.artifact_digest` (the ZIP's SHA-256) and, when the release
declares one, `agent_release.adapter_digest`.

The client's managed identity is therefore exactly one archive pin plus one
optional recipe adapter pin. A PACKAGED distribution resolves the shipped
`code4me-runtime/manifest.json`, refuses when its declared archive sha256 does not
equal the bootstrap pin (before writing anything), and only then installs the
archive. There is no execution manifest and no extracted-file inventory on the
client side: the recipe's declared executable, argv and archive bytes are the
launch contract. The bootstrap release ID is recorded for attribution, not
matched against a client-side release table.

Historical archive-only leaves and snapshots are preserved in server storage. Do
not relabel old digests or retarget frozen assignments. Deploy server changes
first and require the corresponding participant ZIP for those studies.

## Local test releases (development only)

A production participant release always covers the four native platforms above.
For local end-to-end testing on the current macOS arm64 host, an operator may
prepare a real single-platform release from the one native runtime archive and
research-proxy bundle that exist locally. This mode is explicit and never
implied:

- `prepare`/`validate` accept `--platforms macos-aarch64` only when
  `CODE4ME_LOCAL_RELEASE=1` is set; the recipe, the staged proxy manifest and the
  shipped `code4me-runtime/manifest.json` then declare exactly that subset.
- `build` passes `-PparticipantLocalRelease=true` (which permits a loopback HTTP
  `--server-url` such as `http://localhost:8008` and relaxes only the
  four-platform runtime-recipe coverage check) and verifies the ZIP with
  `--allow-partial-platforms`, which requires the artifact platforms to match
  the recipe's own inventory instead of the production matrix.
- Without the environment variable and the subset flag, every phase keeps the
  strict four-platform contract, and the strict verifier still rejects any
  partial or non-self-contained artifact.

The resulting ZIP is a local test artifact. It carries the release, profile and
study records for one platform and must not be distributed to participants.

## Verification and limits

Unit/contract tests use small synthetic native-shaped archives to exercise
unequal archive/executable hashes, safe extraction, resumable apply and the
fail-closed pinned-digest check. They do not prove real agent compatibility.
The strict final-artifact verifier requires the plugin's recipe-derived release
inventory, the single shipped `code4me-runtime/manifest.json` + archive it
declares, and all four managed platforms:

    python3 scripts/verify-participant-artifact.py /path/to/plugin.zip \
      --require-participant-release

Before participants run, obtain the actual native artifacts, compatible
Goose/Codex installations/configuration, real conformance receipts, and the
same-ZIP mixed-agent study evidence. Existing BYOA installed-binary attestation,
telemetry/privacy, product fencing and lifecycle findings from the architecture
ruling remain separate readiness work; this distribution change does not
declare them resolved.
