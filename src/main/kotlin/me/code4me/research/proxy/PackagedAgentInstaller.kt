package me.code4me.research.proxy

import me.code4me.research.bootstrap.normalizeSha256Hex
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
 * The one seam that installs the packaged research agent for a bootstrap pin.
 *
 * The study's immutable archive identity selects a matching bundle, verified
 * cache, or exact public release download. Installation never executes bytes;
 * a fresh bootstrap authorizes the subsequent native self-check and launch.
 */
fun interface PackagedAgentInstaller {
    /**
     * Install the bundled agent for the 64-hex [pin] (the bootstrap manifest's
     * `agent_release.artifact_digest`). The pin is normalized and compared with
     * the recipe's declared archive digest before any write.
     */
    fun install(pin: String): PackagedAgentInstall

    fun prepare(release: AgentReleaseRef, progress: (Long, Long) -> Unit, isCurrent: () -> Boolean): PackagedAgentInstall =
        install(release.normalizedArtifactDigest.orEmpty())

    /** Called only after a fresh, verified bootstrap authorizes execution. */
    fun validate(ready: PackagedAgentInstall.Ready): String? = null

    /**
     * The adapter digest the shipped recipe declares, or `null` when the recipe
     * declares none. Never invented: an adapter-less recipe returns `null` and
     * the caller skips the adapter pin check.
     */
    fun recipeAdapterDigest(): String? = null

    companion object {
        /** The plugin's own packaged runtime. */
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

    override fun install(pin: String): PackagedAgentInstall {
        val installer =
            try {
                this.installer
            } catch (exception: Exception) {
                return PackagedAgentInstall.Blocked("the bundled runtime is not available: ${exception.message}")
            } catch (exception: LinkageError) {
                return PackagedAgentInstall.Blocked("the bundled runtime is not available: ${exception.message}")
            }
        val artifact =
            try {
                installer.selectArtifact()
            } catch (exception: Exception) {
                return PackagedAgentInstall.Blocked("the bundled runtime manifest is invalid: ${exception.message}")
            } ?: return PackagedAgentInstall.Blocked(
                "the bundled runtime has no agent for " +
                    "${ManagedRuntimeInstaller.platformId()}-${ManagedRuntimeInstaller.architectureId()}",
            )

        val normalizedPin =
            normalizeSha256Hex(pin)
                ?: return PackagedAgentInstall.Blocked(
                    "the bootstrap pin is not a 64-hex sha256; refusing to install the bundled agent",
                )

        if (artifact.sha256.lowercase() != normalizedPin) {
            return PackagedAgentInstall.Blocked(
                "the bundled agent runtime ${artifact.version} (sha256 ${artifact.sha256.take(12)}…) " +
                    "does not match the pinned release archive ${normalizedPin.take(12)}…; " +
                    "rebuild the plugin from the release's runtime manifest and archive",
            )
        }

        return when (val result = installer.ensureInstalled()) {
            is RuntimeInstallResult.Ready ->
                PackagedAgentInstall.Ready(
                    argv = listOf(result.executable.toString(), "--managed"),
                    digest = ContentHasher.STREAMING.sha256(result.executable),
                    artifact = result.artifact,
                )
            is RuntimeInstallResult.Unavailable -> PackagedAgentInstall.Blocked(result.message)
            is RuntimeInstallResult.Failed -> PackagedAgentInstall.Blocked(result.message, result.retryable)
        }
    }

    override fun prepare(release: AgentReleaseRef, progress: (Long, Long) -> Unit, isCurrent: () -> Boolean): PackagedAgentInstall {
        val pin = release.normalizedArtifactDigest ?: return PackagedAgentInstall.Blocked("The study's agent checksum is invalid.")
        val artifact = release.artifact?.takeIf { it.managedProtocol.isNotBlank() && it.executable.isNotBlank() }
            ?: installer.selectArtifact()?.takeIf { it.sha256.equals(pin, ignoreCase = true) }
            ?: return PackagedAgentInstall.Blocked("This study needs an agent release with download and protocol metadata. Contact the research team.")
        if (artifact.sha256.lowercase() != pin) return PackagedAgentInstall.Blocked("The agent archive does not match the study's checksum.")
        return when (val result = installer.ensureInstalled(artifact, progress = progress, isCurrent = isCurrent)) {
            is RuntimeInstallResult.Ready -> PackagedAgentInstall.Ready(
                listOf(result.executable.toString(), "--managed"), ContentHasher.STREAMING.sha256(result.executable), result.artifact,
            )
            is RuntimeInstallResult.Unavailable -> PackagedAgentInstall.Blocked(result.message)
            is RuntimeInstallResult.Failed -> PackagedAgentInstall.Blocked(result.message, result.retryable)
        }
    }

    override fun validate(ready: PackagedAgentInstall.Ready): String? =
        ManagedRuntimeInstaller.selfCheck(java.nio.file.Path.of(ready.argv.first()), ready.artifact?.version)

    override fun recipeAdapterDigest(): String? =
        try {
            installer.selectArtifact()?.adapterDigest
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
}
