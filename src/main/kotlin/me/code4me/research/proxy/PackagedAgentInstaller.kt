package me.code4me.research.proxy

import me.code4me.research.bootstrap.AgentReleaseRef
import me.code4me.research.runtime.ContentHasher
import me.code4me.services.agent.ManagedRuntimeInstaller
import me.code4me.services.agent.RuntimeInstallResult
import me.code4me.services.agent.RuntimeArtifact

/**
 * Outcome of installing the single packaged research agent.
 *
 * [Ready] carries the verified executable argv (the exact command the ACP entry
 * pins) and the executable's own SHA-256 (the proxy's `--agent-digest`).
 * [Blocked] is terminal: no fallback (PATH, npm, source) is ever attempted.
 */
sealed interface PackagedAgentInstall {
    data class Ready(val argv: List<String>, val digest: String, val artifact: RuntimeArtifact? = null) : PackagedAgentInstall

    data class Blocked(val detail: String, val retryable: Boolean = false) : PackagedAgentInstall
}

/**
 * The seam that prepares the assigned research agent for a bootstrap pin.
 *
 * The study's immutable archive identity selects a verified cache entry or the
 * exact public release download, never an archive bundled in the plugin.
 * Installation never executes bytes; a fresh bootstrap authorizes the
 * subsequent native self-check and launch.
 */
fun interface PackagedAgentInstaller {
    /**
     * Legacy pin-only transports have no public release URL. Production blocks
     * them; a study needs [prepare] with its assigned artifact metadata.
     */
    fun install(pin: String): PackagedAgentInstall

    fun prepare(release: AgentReleaseRef, progress: (Long, Long) -> Unit, isCurrent: () -> Boolean): PackagedAgentInstall =
        install(release.normalizedArtifactDigest.orEmpty())

    /** Called only after a fresh, verified bootstrap authorizes execution. */
    fun validate(ready: PackagedAgentInstall.Ready): String? = null

    companion object {
        /** Installs the assignment's pinned archive from the verified cache or its exact release download. */
        val PRODUCTION: PackagedAgentInstaller = ProductionPackagedAgentInstaller()
    }
}

/**
 * Uses the same verified installer as the ordinary managed-agent path.
 *
 * @param installerProvider supplies the installer lazily so constructing the
 * seam never touches the IDE's system path; tests inject an installer rooted at
 * a temporary directory with in-memory resources.
 */
internal class ProductionPackagedAgentInstaller(
    private val installerProvider: () -> ManagedRuntimeInstaller = { ManagedRuntimeInstaller() },
) : PackagedAgentInstaller {
    private val installer: ManagedRuntimeInstaller by lazy(installerProvider)

    override fun install(pin: String): PackagedAgentInstall = PackagedAgentInstall.Blocked(
        "The assigned study agent needs public release metadata; retry preparation.",
    )

    override fun prepare(release: AgentReleaseRef, progress: (Long, Long) -> Unit, isCurrent: () -> Boolean): PackagedAgentInstall {
        val pin = release.normalizedArtifactDigest ?: return PackagedAgentInstall.Blocked("The study's agent checksum is invalid.")
        val artifact = release.artifact?.takeIf { it.managedProtocol.isNotBlank() && it.executable.isNotBlank() }
            ?: return PackagedAgentInstall.Blocked("This study needs an agent release with download and protocol metadata. Contact the research team.")
        if (artifact.sha256.lowercase() != pin) return PackagedAgentInstall.Blocked("The agent archive does not match the study's checksum.")
        return when (val result = installer.ensureInstalled(
            artifact, progress = progress, isCurrent = isCurrent, requirePublicRelease = true,
        )) {
            is RuntimeInstallResult.Ready -> PackagedAgentInstall.Ready(
                listOf(result.executable.toString(), "--managed"), ContentHasher.STREAMING.sha256(result.executable), result.artifact,
            )
            is RuntimeInstallResult.Unavailable -> PackagedAgentInstall.Blocked(result.message)
            is RuntimeInstallResult.Failed -> PackagedAgentInstall.Blocked(result.message, result.retryable)
        }
    }

    override fun validate(ready: PackagedAgentInstall.Ready): String? =
        ManagedRuntimeInstaller.selfCheck(java.nio.file.Path.of(ready.argv.first()), ready.artifact?.version)
}
