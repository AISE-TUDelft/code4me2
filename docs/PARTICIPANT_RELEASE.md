# One ZIP, one preparation workflow

All participants install the same ZIP. Code4Me's native agent and the research
proxy are bundled for macOS arm64/x64, Linux x64 and Windows x64. Goose and Codex
remain participant-installed ACP agents. Their command, adapter and configuration
contracts are declared in the recipe; the workflow never installs them.

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
  hashes are verified. Preparation computes the executable and complete
  dependency inventory from those bytes.
- Choose qualified Goose/Codex ACP commands, adapters and configuration bindings
  for the intended versions. A version label or a discovered executable alone
  does not demonstrate agent compatibility.
- Optional profiles use existing profile request fields: name, framework_version,
  connection_id, model, approval_policy, max_steps, tools_json, is_active,
  temperature and max_context_tokens. The command supplies release_id. Provider
  connection IDs are non-secret; provider credentials and participant paths must
  never enter a recipe. BYOA continues to use participant credentials.

## Prepare and build

From the plugin repository, with the native release files in a separate input
directory:

    ../code4me2-server/.venv/bin/python scripts/participant-release.py validate \
      /path/to/recipe.json --inputs /path/to/native-release

    ../code4me2-server/.venv/bin/python scripts/participant-release.py prepare \
      /path/to/recipe.json --inputs /path/to/native-release \
      --output /path/to/prepared-release

Both commands are offline. Preparation uses a new output directory and does not
stamp source versions or change the committed runtime manifest. It emits:

- catalog.json and research-agents/: exact release-keyed runtime payloads;
- resources/code4me-runtime/: independently checksummed transport archives;
- registration.json: three derived leaves, optional pinned profiles and provenance.

recipe.json and prepared-inputs.json pin these outputs for later phases. Changed
or missing inputs are rejected before building or writing to a server. To apply
a CI build locally, prepare again from the uploaded recipe and the same native
inputs, and compare its registration.json and recipe digest with CI evidence.

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
the hash of its own commit). It builds the native proxies, prepares the catalog, verifies that
recipe pins match workflow inputs, and invokes the same build command. Existing
native runtime release production remains a prerequisite.

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
For new managed leaves, pass release_id and execution_manifest_digest to
ConformanceRunner.run along with the archive artifact_digest, adapter_digest and
host. Supply resulting real receipts as a JSON array:

    ../code4me2-server/.venv/bin/python scripts/participant-release.py apply \
      /path/to/prepared-release --server-url https://study.example.org \
      --receipts /path/to/real-conformance-receipts.json

The same command uploads these receipts, verifies qualification across every
managed platform, and creates/reuses compatible profile templates. Missing
qualification leaves pending entries and exits 2. Profiles with conflicting or
ambiguous existing names require a new name; existing profiles are not edited.
Use the returned profile IDs in the existing study UI.

## Identity and compatibility

DistributionArtifact.sha256 remains the archive hash. Its optional execution
manifest binds the relative entrypoint, argv and sorted complete file inventory.
Bootstrap projects archive_sha256, executable_sha256,
execution_manifest_digest and adapter_digest separately. Selection checks the
release ID and archive pin; launch also checks the executable, inventory and
adapter. Receipt reuse across different execution leaves is rejected even when
their archive bytes happen to match.

Historical archive-only leaves and snapshots are preserved. Bootstrap explicitly
blocks launching an archive leaf without an execution manifest. Generate a new
leaf; do not relabel old digests or retarget frozen assignments. New server
models accept old records, but old clients cannot be assumed to launch new
execution leaves. Deploy server changes first and require the corresponding
participant ZIP for those studies.

## Verification and limits

Unit/contract tests use small synthetic native-shaped archives to exercise
unequal archive/executable hashes, complete inventories, safe extraction,
resumable apply and exact selection. They do not prove real agent compatibility.
The strict final-artifact verifier requires the recipe inventory and all four
managed platforms:

    python3 scripts/verify-participant-artifact.py /path/to/plugin.zip \
      --require-participant-release

Before participants run, obtain the actual native artifacts, compatible
Goose/Codex installations/configuration, real conformance receipts, and the
same-ZIP mixed-agent study evidence. Existing BYOA installed-binary attestation,
telemetry/privacy, product fencing and lifecycle findings from the architecture
ruling remain separate readiness work; this distribution change does not
declare them resolved.
