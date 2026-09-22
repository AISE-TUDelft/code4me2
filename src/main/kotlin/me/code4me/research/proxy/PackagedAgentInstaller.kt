package me.code4me.research.proxy

import me.code4me.research.bootstrap.normalizeSha256Hex
import me.code4me.research.runtime.ContentHasher
import me.code4me.services.agent.ManagedRuntimeInstaller
import me.code4me.services.agent.RuntimeInstallResult

/**
 * Outcome of installing the single packaged research agent.
 *
 * [Ready] carries the verified executable argv (the exact command the ACP entry
 * pins) and the executable's own SHA-256 (the proxy's `--agent-digest`).
 * [Blocked] is terminal: no fallback (PATH, npm, source) is ever attempted.
 */
sealed interface PackagedAgentInstall {
    data class Ready(val argv: List<String>, val digest: String) : PackagedAgentInstall

    data class Blocked(val detail: String) : PackagedAgentInstall
}

/**
 * The one seam that installs the packaged research agent for a bootstrap pin.
 *
 * There is exactly one agent artifact identity: the shipped recipe
 * (`code4me-runtime/manifest.json`) and its ZIP, installed by
 * [ManagedRuntimeInstaller] and verified against the bootstrap manifest's pinned
 * archive digest before any byte is written. A missing or mismatched artifact
 * blocks with an actionable message; it never degrades to a host agent.
 */
fun interface PackagedAgentInstaller {
    /**
     * Install the bundled agent for the 64-hex [pin] (the bootstrap manifest's
     * `agent_release.artifact_digest`). The pin is normalized and compared with
     * the recipe's declared archive digest before any write.
     */
    fun install(pin: String): PackagedAgentInstall

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
 * The production [PackagedAgentInstaller]: the plugin's own bundled recipe and
 * archive, installed through the same [ManagedRuntimeInstaller] the non-research
 * managed path uses.
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
                )
            is RuntimeInstallResult.Unavailable -> PackagedAgentInstall.Blocked(result.message)
            is RuntimeInstallResult.Failed -> PackagedAgentInstall.Blocked(result.message)
        }
    }

    override fun recipeAdapterDigest(): String? =
        try {
            installer.selectArtifact()?.adapterDigest
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
}
