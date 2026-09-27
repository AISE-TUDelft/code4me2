# Research proxy release packaging

For complete participant releases, use the
[one-recipe workflow](PARTICIPANT_RELEASE.md). It supplies the required
participantReleaseDir, stages release-keyed managed agents and retains installed
Goose/Codex. The staging commands below describe the proxy component alone.

The participant plugin ZIP ships a **self-contained** research ACP proxy for
every supported platform. The packaged resolver refuses a source-only
(`self_contained = false`) runtime with `NOT_SELF_CONTAINED`, so release staging
must never fall back to proxy source. This note documents the platform matrix,
the runner requirements, and the exact local commands that exercise the same
staging/verification path as CI.

## Supported platform matrix

| Proxy platform | Build runner | Notes |
| --- | --- | --- |
| `macos-aarch64` | `macos-14` | Apple silicon |
| `macos-x64` | `macos-15-intel` | Intel (`macos-13` is retired) |
| `linux-x64` | `ubuntu-24.04` | |
| `windows-x64` | `windows-2022` | chosen only when the runtime release declares it |

PyInstaller is **host-only**: it cannot cross-compile. Each platform is built on
a runner of the matching OS/CPU; `buildResearchProxyBundle` fails when
`-PresearchProxyPlatforms` does not name the build host. The build also needs a
`code4me2-server` checkout next to `code4me2` because PyInstaller packages the
shared `research` modules (`--paths ../code4me2-server/src`), and the runner
installs the locked server toolchain
(`python -m pip install -r code4me2-server/packaging/requirements-runtime.lock
-r code4me2-server/packaging/requirements-build.lock` plus
`pip install --no-deps ./code4me2-server`).

## How CI obtains the release platform list

The participant workflow derives the list from the managed runtime release
manifest (`code4me-managed-runtime-release.json`, produced by
`code4me2-server/packaging/create_release_manifest.py`). Its artifacts are named
`code4me-agent-<os>-<arch>.zip`; `scripts/research-proxy-platforms.py` maps those
names (`arm64` → `aarch64`) onto the supported matrix above and emits both the
`platforms=` CSV and the GitHub Actions `matrix=` JSON. The `prepare-proxy-matrix`
job provides them to the `build-research-proxy` matrix; the release job downloads
each `research-proxy-<platform>` artifact into
`telemetry-acp-proxy/dist/<os>-<arch>/` before staging.

## Local release command

```sh
# On each platform host (once per platform; produces dist/<os>-<arch>/):
cd code4me2
PYTHON=/path/to/python ./gradlew buildResearchProxyBundle \
  -PresearchProxyPlatforms=macos-aarch64   # the host's own platform id

# Assemble and verify the participant ZIP with the release contract:
./gradlew --no-configuration-cache buildParticipantPlugin \
  -PpluginVersion=1.2.3 \
  -Pcode4me.serverUrl=https://api.example.org \
  -PresearchProxyPlatforms=macos-aarch64,macos-x64,linux-x64 \
  -PrequireResearchProxyBundles=true

python3 scripts/verify-participant-artifact.py build/distributions/<zip>
```

Without `-PresearchProxyPlatforms`/`-PrequireResearchProxyBundles` the staging
task stays host-only and may use the source fallback for local development; any
declared matrix (or the strict flag) disables that fallback and fails when a
prebuilt `telemetry-acp-proxy/dist/<os>-<arch>/` bundle is missing.

Negative control (must fail, not degrade to source):

```sh
./gradlew stageResearchProxy \
  -PresearchProxyPlatforms=linux-x64 \
  -PrequireResearchProxyBundles=true
# Missing self-contained research proxy bundle(s) for: linux-x64. ...
```

The workflow runs this as the `negative-control-missing-proxy` job.

## Inference credential file (Goose arms)

For a study arm that relays through the research inference gateway (Goose), the
plugin writes a plugin-owned, owner-only (0600) JSON file and passes only its
path to the proxy: `--inference-credential-file <absolute path>` (emitted before
`--agent-cmd`), with the entry-env fallback
`CODE4ME_RESEARCH_INFERENCE_CREDENTIAL_FILE`. The file is
`{"schema_version":"1","credential_env_key":"OPENAI_API_KEY","credential":"<bearer>"}`.
The proxy validates it, injects the credential into the agent child's
environment under `credential_env_key`, logs only the key name, and never
deletes the file. The plugin replaces the file atomically on every manifest
refresh and deletes it on deactivation; a missing or malformed file is a proxy
usage error, so the agent is never launched unconfigured. The non-secret gateway
settings (`OPENAI_HOST`, `OPENAI_BASE_PATH`, `GOOSE_PROVIDER`, `GOOSE_PATH_ROOT`)
travel as ordinary `--agent-env KEY=VALUE` overrides; the credential value never
appears in argv, the ACP entry, or logs.

## Verifying a built ZIP

`scripts/verify-participant-artifact.py` scans the plugin ZIP (including nested
jars) for forbidden paths/text and then re-verifies
`research-runtime/proxy-manifest.json`: every declared platform must be
`self_contained`, declare a resolvable entrypoint, and match every proxy/agent
file record by size and SHA-256. A `run.py` entrypoint or non-self-contained
platform fails the verification.

Focused tests:

```sh
cd code4me2
../code4me2-server/.venv/bin/python -m pytest -q tests/scripts
```
