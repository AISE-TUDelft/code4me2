"""CLI entry point for the transparent ACP telemetry proxy.

``stdout`` is reserved exclusively for protocol frames forwarded from the agent;
every diagnostic goes to ``stderr``. Launch the proxy with an argument array and
a digest-pinned agent artifact:

    python -m telemetry_acp_proxy.main \
        --agent-digest sha256:<64 hex> \
        --spool-endpoint file:///tmp/spool.jsonl \
        --capability-file /run/code4me/capability \
        --telemetry-policy /etc/code4me/telemetry-policy.json \
        --telemetry-policy-digest <64 hex> \
        --adapter codex-v1 \
        --agent-cmd /opt/code4me/agents/codex-agent --stdio

``--agent-cmd`` **must be the last proxy option**: it consumes every remaining
argument (``argparse.REMAINDER``) as the agent argv, including vendor flags such
as ``--managed``. All proxy flags must therefore appear *before* ``--agent-cmd``.

``--telemetry-policy`` loads the frozen study policy (the shared server
``PrivacyPolicy`` JSON model). ``--telemetry-policy-digest`` pins the exact
policy: when present it must match both the document's ``policy_digest`` and the
digest recomputed from the loaded fields, otherwise the proxy exits with a usage
error before any frame is forwarded. ``--adapter`` selects an allowlisted
adapter; an unknown id is a usage error (fail closed, never a silent import).

``--capability`` is mutually exclusive with ``--capability-file``. The token is
resolved from (in order) ``--capability``, then ``--capability-file`` (read
without deleting it, so repeated launches read the same token), then the
``CODE4ME_RESEARCH_CAPABILITY`` environment variable. The environment variable is
the durable carrier: the ACP host entry persists it, so a launch keeps working
even after the plugin removes its fallback file. When a spool endpoint is
configured but the token is still missing, the proxy retries briefly (a few
seconds) before exiting so a launch racing activation can still succeed.

Exit codes:
    0   clean shutdown
    2   usage/configuration error
    10  agent artifact missing, unsafe, or digest mismatch
    20  agent process crashed
    21  malformed protocol output observed (acp_parse_failed)
    22  spool endpoint rejected the capability / was unavailable
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Optional, Sequence

from ._bootstrap import ensure_research_on_path

ensure_research_on_path()

from research.canonical import canonical_hash  # noqa: E402
from research.telemetry.builder import SequenceAllocator  # noqa: E402
from research.telemetry.privacy import PrivacyPolicy  # noqa: E402

from .adapters import get_adapter  # noqa: E402
from .delivery import (  # noqa: E402
    DEFAULT_CLOSE_TIMEOUT_SECONDS,
    DeliveryBatch,
    DeliveryQueue,
)
from .forwarder import AcpForwarder  # noqa: E402
from .lifecycle import (  # noqa: E402
    ArtifactVerificationError,
    ProxyProcess,
    ProxyState,
    UnsafeAgentPathError,
    normalize_digest,
    verify_artifact,
)
from .normalize import SESSION_ID_KEY, ProxyNormalizer  # noqa: E402
from .observe import ObservedAcpMessageV1, Observer  # noqa: E402
from .privacy_gate import PrivacyGate, agent_crashed_event  # noqa: E402
from .spool_client import (  # noqa: E402
    EMITTER_ID_PREFIX,
    LocalSpoolClient,
    SpoolSendResult,
    generate_emitter_id,
)

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_ARTIFACT = 10
EXIT_AGENT_CRASH = 20
EXIT_PARSE_FAILURE = 21
EXIT_SPOOL_REJECTED = 22

#: Environment variable carrying the one-time IPC capability. The ACP host entry
#: persists it, so a proxy launch no longer depends on a file surviving teardown.
CAPABILITY_ENV_VAR = "CODE4ME_RESEARCH_CAPABILITY"

#: Bounded window the proxy waits for a required capability to appear.
CAPABILITY_RETRY_SECONDS = 5.0
CAPABILITY_RETRY_INTERVAL_SECONDS = 0.1

__all__ = [
    "CAPABILITY_ENV_VAR",
    "build_parser",
    "computed_policy_digest",
    "main",
    "policy_digest_mismatch",
    "resolve_capability",
    "resolve_capability_with_retry",
    "run_proxy",
]


def build_parser() -> argparse.ArgumentParser:
    """Build the CLI parser."""
    parser = argparse.ArgumentParser(
        prog="telemetry-acp-proxy",
        description=(
            "Transparent ACP stdio telemetry proxy. stdout carries only protocol "
            "frames; diagnostics go to stderr."
        ),
    )
    parser.add_argument(
        "--agent-cmd",
        nargs=argparse.REMAINDER,
        required=True,
        metavar="ARG",
        help=(
            "Agent argv array (the first element is the digest-pinned executable). "
            "MUST BE LAST: every remaining argument, including vendor flags such as "
            "'--managed', belongs to the agent, so put all proxy flags before it."
        ),
    )
    parser.add_argument(
        "--agent-digest",
        required=True,
        help="Pinned SHA-256 of the agent artifact (with or without 'sha256:').",
    )
    parser.add_argument("--spool-endpoint", default=None, help="Local spool endpoint.")
    parser.add_argument(
        "--proxy-digest",
        default=None,
        help="Digest of this proxy runtime, carried in the spool payload.",
    )
    parser.add_argument(
        "--emitter-id",
        default=None,
        help=(
            "Canonical emitter id carried in the spool payload and on every "
            "event. Overrides the generated per-process default "
            f"('{EMITTER_ID_PREFIX}:<8 hex>')."
        ),
    )
    capability_group = parser.add_mutually_exclusive_group()
    capability_group.add_argument(
        "--capability",
        default=None,
        help="Opaque one-time local IPC capability for the spool.",
    )
    capability_group.add_argument(
        "--capability-file",
        default=None,
        metavar="PATH",
        help=(
            "Read the one-time capability from PATH without deleting it. Fallback "
            "only: the durable token is CODE4ME_RESEARCH_CAPABILITY in the entry "
            "env, used when PATH cannot be read (mutually exclusive with "
            "--capability)."
        ),
    )
    parser.add_argument(
        "--telemetry-policy",
        default=None,
        help="Path to a JSON telemetry policy (defaults to metadata-only).",
    )
    parser.add_argument(
        "--telemetry-policy-digest",
        default=None,
        help=(
            "Expected digest of the loaded --telemetry-policy. A mismatch with the "
            "policy document or its computed digest is a usage error (fail closed)."
        ),
    )
    parser.add_argument(
        "--adapter",
        default=None,
        help="Optional adapter name (for example 'codex-v1').",
    )
    parser.add_argument(
        "--agent-env",
        action="append",
        default=None,
        metavar="KEY=VALUE",
        help=(
            "Explicit environment override for the agent child (repeatable). "
            "Release-declared BYOA configuration reaches the child only through "
            "this flag; the rest of the environment stays allowlisted."
        ),
    )
    parser.add_argument(
        "--runtime-root",
        default=None,
        help="Optional root the agent artifact must stay inside.",
    )
    return parser


def _agent_environment(values: Optional[Sequence[str]]) -> tuple[dict[str, str], Optional[str]]:
    """Parse repeated ``KEY=VALUE`` overrides; a malformed pair is an error."""
    parsed: dict[str, str] = {}
    for item in values or []:
        key, separator, value = str(item).partition("=")
        key = key.strip()
        if not separator or not key:
            return {}, f"--agent-env must be KEY=VALUE, got {item!r}"
        parsed[key] = value
    return parsed, None


def _load_policy(path: Optional[str]) -> PrivacyPolicy:
    if path is None:
        return PrivacyPolicy.default()
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return PrivacyPolicy.model_validate(data)


def computed_policy_digest(policy: PrivacyPolicy) -> str:
    """Digest of the resolved policy fields.

    Mirrors the plugin's ``PrivacyPolicy.computedDigest`` (canonical JSON over
    the same five fields), so a registration can pin both the policy document
    and this independently recomputed value.
    """
    return canonical_hash(
        {
            "allowed": sorted(
                field_class.value for field_class in policy.allowed_field_classes
            ),
            "blocked": sorted(
                field_class.value for field_class in policy.blocked_field_classes
            ),
            "content_allowed": policy.content_allowed,
            "consent_active": policy.consent_active,
            "code_metadata_mode": policy.code_metadata_mode,
        }
    )


def policy_digest_mismatch(
    policy: PrivacyPolicy,
    expected_digest: Optional[str],
) -> Optional[str]:
    """Return a fail-closed error when [expected_digest] does not pin [policy].

    ``None`` means the expected digest matches the document and the recomputed
    policy; any other result is a usage error the caller must refuse.
    """
    if expected_digest is None:
        return None
    expected = normalize_digest(expected_digest)
    if not expected:
        return "--telemetry-policy-digest must not be blank"
    stored = policy.policy_digest
    if stored is None:
        return (
            "the telemetry policy document carries no policy_digest, so "
            f"--telemetry-policy-digest {expected_digest} cannot be verified"
        )
    if normalize_digest(stored) != expected:
        return (
            f"--telemetry-policy-digest {expected_digest} does not match the loaded "
            f"policy_digest {stored}"
        )
    computed = computed_policy_digest(policy)
    if computed != expected:
        return (
            f"--telemetry-policy-digest {expected_digest} does not match the computed "
            f"policy digest {computed}"
        )
    return None


def _capability_from_environment(
    environment: Optional[Mapping[str, str]] = None,
) -> Optional[str]:
    """Return the non-blank capability carried by the host entry env, if any."""
    source = os.environ if environment is None else environment
    token = source.get(CAPABILITY_ENV_VAR)
    if token is None:
        return None
    stripped = token.strip()
    return stripped or None


def resolve_capability(
    capability: Optional[str],
    capability_file: Optional[str],
    environment: Optional[Mapping[str, str]] = None,
) -> tuple[Optional[str], Optional[str]]:
    """Resolve the one-time capability.

    Resolution order:

    1. ``--capability`` is returned unchanged;
    2. ``--capability-file`` is read **without deleting it**: the plugin owns the
       file, rewrites it on every activation, and removes it only on teardown, so
       repeated proxy launches read the same valid token;
    3. ``CODE4ME_RESEARCH_CAPABILITY`` from the environment. The ACP host entry
       persists this, so a launch survives the plugin deleting its fallback file.

    Returns ``(token, error_message)``; exactly one is ``None`` (both are
    ``None`` when no capability was requested at all).
    """
    if capability is not None:
        return capability, None
    if capability_file is None:
        return _capability_from_environment(environment), None
    try:
        token = Path(capability_file).read_text(encoding="utf-8").strip()
    except OSError as error:
        file_error = f"proxy: cannot read capability file {capability_file}: {error}"
    else:
        if token:
            return token, None
        file_error = "proxy: capability file is empty"
    env_token = _capability_from_environment(environment)
    if env_token is not None:
        return env_token, None
    return None, file_error


def resolve_capability_with_retry(
    capability: Optional[str],
    capability_file: Optional[str],
    *,
    required: bool,
    environment: Optional[Mapping[str, str]] = None,
    timeout_seconds: float = CAPABILITY_RETRY_SECONDS,
    interval_seconds: float = CAPABILITY_RETRY_INTERVAL_SECONDS,
    sleep: Callable[[float], None] = time.sleep,
    monotonic: Callable[[], float] = time.monotonic,
) -> tuple[Optional[str], Optional[str]]:
    """Resolve the capability, briefly retrying while a required token is absent.

    A launch can race activation: the plugin persists the ACP entry immediately
    while the capability becomes readable a moment later. When the token is
    required ([required]) but still unresolvable, wait for a short bounded window
    so the launch succeeds instead of exiting non-zero.
    """
    token, error = resolve_capability(capability, capability_file, environment)
    if token is not None or not required:
        return token, error
    deadline = monotonic() + timeout_seconds
    while monotonic() < deadline:
        sleep(interval_seconds)
        token, error = resolve_capability(capability, capability_file, environment)
        if token is not None:
            return token, None
    return token, error


def _deliver(
    events: Sequence,
    spool: Optional[LocalSpoolClient],
    diagnostics,
) -> Optional[SpoolSendResult]:
    if spool is None or not events:
        return None
    result = spool.send(events)
    if not result.ok:
        diagnostics(
            f"proxy: dropped {result.dropped} canonical event(s): {result.error}"
        )
    return result


def _deliverable(events: Sequence, diagnostics) -> list:
    """Drop events the privacy gate blocked; they never reach the spool.

    The gate marks a blocked event with ``privacy.blocked`` and an empty
    payload. Enforcing the study policy here means a restrictive policy loses
    nothing that a server-side rejection would have discarded anyway, and no
    payload content is ever transported.
    """
    deliverable = [event for event in events if not event.privacy.blocked]
    blocked = len(events) - len(deliverable)
    if blocked:
        diagnostics(
            f"proxy: dropped {blocked} privacy-blocked observation(s) before the spool"
        )
    return deliverable


def _log_delivery(snapshot: Mapping[str, Any], diagnostics) -> None:
    """Emit the delivery queue's coverage/loss metrics to the stderr sink."""
    if snapshot.get("dropped"):
        diagnostics(
            "proxy: telemetry coverage loss: "
            f"dropped={snapshot.get('dropped')} "
            f"(full={snapshot.get('dropped_full')}, "
            f"error={snapshot.get('dropped_error')}, "
            f"shutdown={snapshot.get('dropped_shutdown')})"
        )
    diagnostics(
        "proxy: telemetry delivery: "
        f"enqueued={snapshot.get('enqueued')} "
        f"delivered={snapshot.get('delivered')} "
        f"dropped={snapshot.get('dropped')} "
        f"pending={snapshot.get('pending')}"
    )


def run_proxy(
    *,
    agent_cmd: Sequence[str],
    agent_digest: str,
    spool_endpoint: Optional[str] = None,
    capability: Optional[str] = None,
    policy: Optional[PrivacyPolicy] = None,
    policy_digest: Optional[str] = None,
    adapter_name: Optional[str] = None,
    runtime_root: Optional[str] = None,
    proxy_digest: str = "",
    emitter_id: Optional[str] = None,
    agent_env: Optional[Mapping[str, str]] = None,
    host_read=None,
    host_write=None,
    diagnostics=None,
) -> int:
    """Run one proxy session; returns a documented exit code.

    ``policy`` is the frozen study policy; ``policy_digest`` is the digest the
    registration declared for it. A mismatch between the two (or with the
    policy's own recomputed fields) is a usage error before anything is
    forwarded or spooled.

    ``emitter_id`` is the process's canonical emitter identity. When omitted, a
    fresh ``acp-proxy:<8 hex>`` id is generated once per process: the sequence
    allocator restarts at 1 on every launch, so a constant id would collide on
    the server's ``(research_session_id, emitter_id, emitter_sequence)`` unique
    constraint across the one-process-per-chat proxy fleet.
    """
    host_read = host_read if host_read is not None else sys.stdin.buffer
    host_write = host_write if host_write is not None else sys.stdout.buffer
    diag = diagnostics or (lambda message: print(message, file=sys.stderr))
    emitter_id = emitter_id or generate_emitter_id()
    policy = policy or PrivacyPolicy.default()

    if not agent_cmd:
        diag("proxy: agent command is empty")
        return EXIT_USAGE

    digest_error = policy_digest_mismatch(policy, policy_digest)
    if digest_error is not None:
        diag(f"proxy: {digest_error}")
        return EXIT_USAGE

    try:
        artifact = verify_artifact(agent_cmd[0], agent_digest, root=runtime_root)
    except (UnsafeAgentPathError, ArtifactVerificationError) as error:
        diag(f"proxy: refusing to launch: {error}")
        return EXIT_ARTIFACT

    if spool_endpoint is not None and not capability:
        diag("proxy: --capability is required with --spool-endpoint")
        return EXIT_USAGE

    try:
        adapter = get_adapter(adapter_name)
    except KeyError as error:
        diag(f"proxy: {error}")
        return EXIT_USAGE

    # One startup line records the selected adapter and the policy digest; it
    # never carries payload content. BYOA env keys are non-secret names.
    diag(
        "proxy: telemetry policy active: "
        f"adapter={adapter_name if adapter is not None else 'generic'} "
        f"policy_digest={policy.policy_digest or 'default'} "
        f"agent_env={sorted((agent_env or {}).keys())}"
    )
    gate = PrivacyGate(policy)
    # One allocator for the whole process: normalized events and proxy-owned
    # lifecycle events (parse failures, agent crashes) must never reuse a
    # sequence for the same emitter.
    allocator = SequenceAllocator()
    normalizer = ProxyNormalizer(
        adapter=adapter, emitter_id=emitter_id, allocator=allocator
    )
    # Production observer: bounded records, no transcript capture. Raw parsed
    # payloads are released after the callback normalizes them.
    observer = Observer()
    spool: Optional[LocalSpoolClient] = None
    if spool_endpoint is not None:
        spool = LocalSpoolClient(
            spool_endpoint,
            capability,
            proxy_digest=proxy_digest,
            emitter_id=emitter_id,
            diagnostics=diag,
        )

    # One bounded worker performs the spool I/O so the forwarding pump never
    # waits on the network. Its consumer calls the existing `_deliver` path.
    delivery: Optional[DeliveryQueue[DeliveryBatch]] = None

    def dispatch(observed: ObservedAcpMessageV1, events: Sequence) -> None:
        """Route normalized, privacy-gated events off the forwarding thread."""
        if not events or delivery is None:
            return
        session_id = next(
            (
                event.payload.get(SESSION_ID_KEY)
                for event in events
                if SESSION_ID_KEY in event.payload
            ),
            None,
        )
        delivery.enqueue(
            DeliveryBatch(
                events=tuple(events),
                direction=observed.direction.value,
                session_id=session_id,
            )
        )

    def handle(observed: ObservedAcpMessageV1) -> None:
        if not observed.ok:
            error_kind = (observed.parse_error or "unknown").split(":", 1)[0]
            event = gate.parse_failed(
                observed,
                parse_error_kind=error_kind,
                occurred_at=observed.receive_wall_time,
                emitter_id=emitter_id,
                allocator=allocator,
            )
            dispatch(observed, _deliverable([event], diag))
            diag(f"proxy: malformed protocol frame ({observed.error_code})")
            return
        events = [gate.filter(event) for event in normalizer.normalize_observed(observed)]
        dispatch(observed, _deliverable(events, diag))

    observer.on_observation = handle

    process = ProxyProcess(
        [str(artifact), *list(agent_cmd[1:])],
        env_overrides=dict(agent_env or {}),
    )
    try:
        process.start()
    except OSError as error:
        diag(f"proxy: failed to launch agent: {error}")
        return EXIT_ARTIFACT

    if spool is not None:
        delivery = DeliveryQueue(
            lambda batch: _deliver(list(batch.events), spool, diag),
            diagnostics=diag,
        )

    forwarder = AcpForwarder(observer=observer, diagnostics=diag)
    forwarder.run(
        host_read,
        host_write,
        process.stdout,
        process.stdin,
        terminate_agent=process.terminate,
    )

    # Shutdown: flush queued telemetry within a bounded window, then report
    # exactly what was delivered and what was lost.
    if delivery is not None:
        _log_delivery(delivery.close(timeout=DEFAULT_CLOSE_TIMEOUT_SECONDS), diag)

    return_code = (
        process.wait(timeout=5)
        if process.state != ProxyState.STOPPED
        else process.returncode
    )

    malformed = observer.malformed_count > 0
    crashed = return_code is not None and return_code != 0
    if crashed:
        event = agent_crashed_event(
            occurred_at=datetime.now(timezone.utc),
            exit_status=return_code,
            emitter_id=emitter_id,
            allocator=allocator,
        )
        _deliver(_deliverable([gate.filter(event)], diag), spool, diag)
        diag(f"proxy: agent exited unexpectedly with status {return_code}")
        return EXIT_AGENT_CRASH
    if malformed:
        return EXIT_PARSE_FAILURE
    return EXIT_OK


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Parse ``argv`` and run the proxy."""
    args = build_parser().parse_args(argv)
    try:
        policy = _load_policy(args.telemetry_policy)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"proxy: invalid telemetry policy: {error}", file=sys.stderr)
        return EXIT_USAGE
    capability, capability_error = resolve_capability_with_retry(
        args.capability,
        args.capability_file,
        required=args.spool_endpoint is not None,
    )
    if capability_error is not None:
        print(capability_error, file=sys.stderr)
        return EXIT_USAGE
    if not args.agent_cmd:
        print("proxy: --agent-cmd requires at least one agent argument", file=sys.stderr)
        return EXIT_USAGE
    agent_env, agent_env_error = _agent_environment(args.agent_env)
    if agent_env_error is not None:
        print(f"proxy: {agent_env_error}", file=sys.stderr)
        return EXIT_USAGE
    return run_proxy(
        agent_cmd=args.agent_cmd,
        agent_digest=args.agent_digest,
        spool_endpoint=args.spool_endpoint,
        capability=capability,
        policy=policy,
        policy_digest=args.telemetry_policy_digest,
        adapter_name=args.adapter,
        runtime_root=args.runtime_root,
        proxy_digest=args.proxy_digest or "",
        # An explicit --emitter-id always wins; otherwise run_proxy mints one.
        emitter_id=args.emitter_id,
        agent_env=agent_env,
    )


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())
