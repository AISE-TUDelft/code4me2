package me.code4me.research.proxy

import me.code4me.research.runtime.ContentHasher
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * BYOA ("bring your own agent") resolution (Issue 11 follow-up).
 *
 * When a study condition pins `distribution_mode: BYOA_EXTERNAL`, the agent is
 * participant-installed rather than shipped as a digest-pinned plugin artifact.
 * Resolution is still fail-closed: the agent is only ever an executable that was
 * explicitly configured, explicitly pinned by the release metadata, or discovered
 * through the documented order below. There is **no** silent fallback: a missing
 * agent is a typed [ByoaAgentResolution.NotFound] the manager turns into an
 * `AGENT_NOT_FOUND` block so the participant can install it.
 *
 * Documented discovery order (first match wins):
 *
 * 1. the participant's configured command (settings; an absolute path or a name);
 * 2. the release metadata's explicit command (an absolute path or a name);
 * 3. the release metadata's logical package (for example `goose`/`codex`), whose
 *    candidate executable names are looked up in `PATH` and then known
 *    per-user install locations.
 *
 * A bare name is never treated as a path: it is looked up in `PATH` and then in
 * [DefaultByoaAgentResolver.knownLocations]. Whatever was used is recorded in
 * [ObservedAgentIdentity.source] so analysis can distinguish a BYOA exposure.
 *
 * PATH is consulted **only** here. The PACKAGED path in
 * [PackagedProxyRuntimeResolver] never consults `PATH`, `npm`, or a global install.
 */

/** Where a resolved BYOA agent executable was located. */
enum class AgentDiscoverySource(val wireValue: String) {
    /** The participant's configured command (settings). */
    CONFIGURED("CONFIGURED"),

    /** The explicit command pinned by the release metadata. */
    RELEASE_COMMAND("RELEASE_COMMAND"),

    /** Discovered on the process `PATH`. */
    PATH("PATH"),

    /** Discovered under a documented known install location. */
    KNOWN_LOCATION("KNOWN_LOCATION"),
}

/**
 * The observed identity of a participant-installed agent.
 *
 * [executable] is the absolute path actually resolved. The path is recorded
 * locally for diagnostics only; exposures report the path-free [digest] and
 * [version].
 */
data class ObservedAgentIdentity(
    val executable: Path,
    val digest: String,
    val version: String?,
    val source: AgentDiscoverySource,
)

/** One BYOA resolution request, built from release metadata and local settings. */
data class ByoaAgentSpec(
    /** Explicit command pinned by the release metadata (name or path), if any. */
    val command: String? = null,
    /** Argument array appended after the resolved executable. Never a shell string. */
    val commandArgs: List<String> = emptyList(),
    /** Logical participant-installed package used for discovery, if any. */
    val agentPackage: String? = null,
    /** Participant-configured command/path override (settings), if any. */
    val configuredCommand: String? = null,
) {
    init {
        require(commandArgs.none { it.indexOf('\u0000') >= 0 }) { "command args must not contain NUL" }
    }
}

/** Typed BYOA resolution outcome; never throws. */
sealed interface ByoaAgentResolution {
    /** The agent was found; [argv] is the full executable argv array. */
    data class Resolved(
        val identity: ObservedAgentIdentity,
        val argv: List<String>,
    ) : ByoaAgentResolution

    /** No agent could be found; the caller must block with `AGENT_NOT_FOUND`. */
    data class NotFound(val detail: String) : ByoaAgentResolution
}

/** Resolves a participant-installed agent. Injectable so activation is testable. */
fun interface ByoaAgentResolver {
    fun resolve(spec: ByoaAgentSpec): ByoaAgentResolution

    companion object {
        val DEFAULT: ByoaAgentResolver = DefaultByoaAgentResolver()
    }
}

/** Candidate executable names for a documented logical package. */
internal fun byoaPackageExecutables(agentPackage: String?): List<String> {
    val normalized = agentPackage?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "goose" -> listOf("goose")
        "codex" -> listOf("codex", "codex-acp")
        "claude", "claude-code" -> listOf("claude")
        "" -> emptyList()
        else -> listOf(normalized)
    }
}

/** Documented known install locations, in discovery order. */
internal fun byoaKnownLocations(): List<Path> {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    val locations = mutableListOf<Path>()
    home?.let {
        locations.add(it.resolve(".local").resolve("bin"))
        locations.add(it.resolve(".cargo").resolve("bin"))
        locations.add(it.resolve(".npm-global").resolve("bin"))
        locations.add(it.resolve("go").resolve("bin"))
        locations.add(it.resolve(".local").resolve("share").resolve("goose").resolve("bin"))
    }
    locations.add(Path.of("/usr/local/bin"))
    locations.add(Path.of("/opt/homebrew/bin"))
    locations.add(Path.of("/usr/bin"))
    return locations
}

/**
 * Default BYOA resolver: pure JVM, injectable environment, bounded `--version`
 * probe. It never mutates the filesystem and never falls back to a packaged
 * artifact.
 */
class DefaultByoaAgentResolver(
    private val pathEnv: String? = System.getenv("PATH"),
    private val knownLocations: List<Path> = byoaKnownLocations(),
    private val isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    private val hasher: ContentHasher = ContentHasher.STREAMING,
    private val versionProbe: (Path) -> String? = ::probeVersion,
) : ByoaAgentResolver {
    override fun resolve(spec: ByoaAgentSpec): ByoaAgentResolution {
        val configured = spec.configuredCommand?.takeIf { it.isNotBlank() }
        val explicit = configured ?: spec.command?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            val source = if (configured != null) AgentDiscoverySource.CONFIGURED else AgentDiscoverySource.RELEASE_COMMAND
            val located = locate(explicit, source)
                ?: return ByoaAgentResolution.NotFound(
                    if (configured != null) {
                        "the configured BYOA agent command '$explicit' was not found or is not executable"
                    } else {
                        "the BYOA agent command '$explicit' pinned by the release was not found or is not executable"
                    },
                )
            return located.toResolution(spec)
        }

        val candidates = byoaPackageExecutables(spec.agentPackage)
        if (candidates.isEmpty()) {
            return ByoaAgentResolution.NotFound(
                "the BYOA release declares neither a command nor an agent package to discover",
            )
        }
        for (candidate in candidates) {
            val located = locate(candidate, AgentDiscoverySource.PATH) ?: continue
            return located.toResolution(spec)
        }
        return ByoaAgentResolution.NotFound(
            "the BYOA agent package '${spec.agentPackage}' was not found on PATH or in any known location",
        )
    }

    private data class Located(val executable: Path, val source: AgentDiscoverySource)

    private fun Located.toResolution(spec: ByoaAgentSpec): ByoaAgentResolution {
        val digest =
            try {
                hasher.sha256(executable)
            } catch (exception: Exception) {
                return ByoaAgentResolution.NotFound("the BYOA agent executable could not be hashed: ${exception.message}")
            }
        val version =
            try {
                versionProbe(executable)
            } catch (_: Exception) {
                null
            }
        return ByoaAgentResolution.Resolved(
            identity = ObservedAgentIdentity(executable = executable, digest = digest, version = version, source = source),
            argv = listOf(executable.toString()) + spec.commandArgs,
        )
    }

    /**
     * Locate [candidate]. A value with a path separator (or an absolute path) is
     * used directly; anything else is a bare name looked up in `PATH` and then
     * [knownLocations]. [directSource] labels an explicit path lookup.
     */
    private fun locate(
        candidate: String,
        directSource: AgentDiscoverySource,
    ): Located? {
        val direct =
            try {
                Path.of(candidate)
            } catch (_: Exception) {
                null
            }
        if (direct != null && (direct.isAbsolute || candidate.contains('/') || candidate.contains('\\'))) {
            return if (isExecutable(direct)) Located(direct.toAbsolutePath().normalize(), directSource) else null
        }
        for (directory in pathDirectories()) {
            val path = directory.resolve(candidate)
            if (isExecutable(path)) return Located(path.toAbsolutePath().normalize(), AgentDiscoverySource.PATH)
        }
        for (base in knownLocations) {
            val path = base.resolve(candidate)
            if (isExecutable(path)) return Located(path.toAbsolutePath().normalize(), AgentDiscoverySource.KNOWN_LOCATION)
        }
        return null
    }

    private fun pathDirectories(): List<Path> =
        pathEnv
            .orEmpty()
            .split(File.pathSeparatorChar)
            .mapNotNull { it.takeIf { entry -> entry.isNotBlank() } }
            .mapNotNull { entry -> runCatching { Path.of(entry) }.getOrNull() }
}

/**
 * Best-effort `<executable> --version` probe. Returns the first non-blank line,
 * or `null` when the probe fails, times out, or produces nothing usable. It is
 * never allowed to block or throw into resolution.
 */
internal fun probeVersion(
    executable: Path,
    timeout: Duration = Duration.ofSeconds(5),
): String? =
    try {
        val process =
            ProcessBuilder(executable.toString(), "--version")
                .redirectErrorStream(true)
                .start()
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }
