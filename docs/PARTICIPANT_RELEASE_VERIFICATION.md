# Participant release implementation verification

2026-09-21. Scope: the one-ZIP preparation/build/registration workflow and its
managed execution identity contract, across code4me2 and code4me2-server.

## Fresh results

From code4me2-server, with TEST_DATABASE_URL directed exclusively at a newly
created disposable PostgreSQL 16/pgvector container:

    .venv/bin/python -m pytest -q tests/test_participant_release.py tests/backend_tests/research/test_runtime.py tests/backend_tests/research/test_manifest_import.py tests/backend_tests/research/test_release_qualification.py tests/backend_tests/research/test_execution_receipts.py --tb=short

Exit 0: **120 passed**, with existing pytest/Alembic deprecation warnings.
The initial run without this override had 112 passes and five database setup
errors because localhost:5433 was unavailable. No assertions were disabled.
The successful run includes real receipt persistence, explicit new-leaf binding
over identical historical archive bytes, immutable receipt replay, and rejection
of ambiguous unscoped receipts.

From code4me2:

    ./gradlew --no-daemon --no-configuration-cache :test --tests 'me.code4me.research.proxy.*' --tests 'me.code4me.research.bootstrap.*' --tests 'me.code4me.research.session.*'

Exit 0: **239 tests, zero failures/errors/skips**. This includes unequal archive
and executable pins, wrong release/adapter/inventory rejection, exact selection,
and a shared Python/Kotlin digest vector with a UTF-8 dependency filename.

    ../code4me2-server/.venv/bin/python -m pytest -q tests/scripts

Exit 0: **15 passed**. Includes real nested ZIP verification, missing dependency
and catalog entry rejection, content-conflict preflight, and interrupted apply
followed by idempotent reconciliation. API calls in these script tests use an
in-memory transport; no live registration was performed.

The participant workflow parsed successfully as YAML. The CLI help command
also exited 0. Git diff whitespace checks passed in both repositories.

## Gradle producer boundary

Four small native-shaped archive fixtures and proxy fixtures were generated in
a new temporary directory, with clearly synthetic source/provenance values.
The executed staging command was:

    ./gradlew --no-daemon --no-configuration-cache stageResearchProxy verifyParticipantRuntimeResources -PparticipantReleaseDir=/var/folders/bh/70hysdjx7kld66tdn8mfgh7w0000gn/T/code4me-release-boundary-dbye6aqk/prepared -PresearchProxyDistDir=/var/folders/bh/70hysdjx7kld66tdn8mfgh7w0000gn/T/code4me-release-boundary-dbye6aqk/proxies -PpluginVersion=2.0.0 -PresearchProxyPlatforms=macos-aarch64,macos-x64,linux-x64,windows-x64 -PrequireResearchProxyBundles=true

Exit 0: both tasks passed. The actual Gradle staging output was placed in a
temporary ZIP and passed inspect_zip(..., require_participant_release=True),
finding exactly one manifest and zero findings. This proves the preparation →
Gradle producer → verifier boundary. It does not prove native execution or
self-contained proxy behavior on any platform.

## Remaining release evidence

No shippable participant ZIP was produced, no remote workflow was dispatched,
and no release was registered on an existing server. Release assembly still
requires clean pinned source commits, the actual four-platform native/proxy
artifacts, a concrete recipe with compatible external-agent contracts, and real
qualification receipts. Existing uncommitted work was preserved.

Actual Goose/Codex/managed-agent runs, clean-host installation and same-ZIP
multi-user telemetry remain unverified. The broader privacy, durability,
installed-agent attestation, product-fencing and lifecycle rulings are outside
this release-workflow implementation. They remain participant-readiness gates.

See [the operator workflow](PARTICIPANT_RELEASE.md) for preparation, deployment
order, registration/retry behavior and historical archive-leaf handling.
