package me.code4me.research.session

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.research.bootstrap.BootstrapEnvironment
import me.code4me.research.bootstrap.BootstrapTransport
import me.code4me.research.bootstrap.BootstrapTransportResult
import me.code4me.research.bootstrap.EnrollmentDiscovery
import me.code4me.research.bootstrap.HttpBootstrapTransport
import me.code4me.research.bootstrap.PluginCompatibility
import me.code4me.research.bootstrap.ResearchJoinCodeResolver
import me.code4me.research.actions.ResearchEnrollmentSettings
import me.code4me.research.ide.IntellijIdeActivitySource
import me.code4me.research.proxy.AcpHostRegistration
import me.code4me.research.proxy.PackagedProxyRuntimeResolver
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.SpoolUploadResult
import me.code4me.research.session.ParticipantStudyStateV1
import me.code4me.services.config.getConfig
import me.code4me.services.config.models.ServerConfig
import okhttp3.Call
import java.net.URI
import java.nio.file.Path

/**
 * Thin, project-scoped lifecycle owner for [ResearchSessionManager] (Issue 10).
 *
 * The service exists only to bind the manager to the IntelliJ project lifetime
 * and to the public IDE activity source. All research logic stays in the pure
 * manager. Nothing is collected or launched until [activate] succeeds, so a
 * build without an active study never starts research behaviour.
 *
 * The manager is created lazily and stopped in [dispose], which the platform
 * calls when the project closes.
 */
sealed interface ResearchReconciliationResult {
    /** The research proxy owns ACP setup for this project. */
    data class StudyOwned(val activation: ResearchActivationResult?) : ResearchReconciliationResult {
        val shouldRetry: Boolean
            get() =
                activation is ResearchActivationResult.Retryable ||
                    activation is ResearchActivationResult.Failed ||
                    (activation is ResearchActivationResult.Blocked &&
                        activation.reason == StudyBlockReason.RUNTIME_UNAVAILABLE)
    }

    /** The signed-in account has no research enrollment. */
    data object NoEnrollment : ResearchReconciliationResult

    /** A terminal enrollment no longer owns ACP setup. */
    data class Terminal(val status: String) : ResearchReconciliationResult

    /** Membership could not be decided, so ordinary setup must remain fenced. */
    data class Unavailable(val message: String) : ResearchReconciliationResult
}

class ResearchSessionService internal constructor(
    private val project: Project,
    private val discoveryOverride: (() -> EnrollmentDiscovery)?,
    private val managerFactoryOverride: (() -> ResearchSessionManager)?,
    private val enrollmentSettingsOverride: ResearchEnrollmentSettings?,
) : Disposable {
    constructor(project: Project) : this(project, null, null, null)

    @Volatile private var manager: ResearchSessionManager? = null
    @Volatile private var eventExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var disposed = false

    /** The lazily created manager (created on first use). */
    fun manager(): ResearchSessionManager =
        managerOrNull() ?: error("research session service has been disposed")

    private fun managerOrNull(): ResearchSessionManager? = synchronized(this) {
        if (disposed) null else manager ?: buildManager().also { manager = it }
    }

    /** Participant-visible state; safe to call before any activation. */
    fun state(): ParticipantStudyStateV1 = manager().state()

    /** Request manifest validation and session start for [enrollmentId]. */
    fun activate(enrollmentId: String): ResearchActivationResult =
        managerOrNull()?.activate(enrollmentId)
            ?: ResearchActivationResult.Blocked(StudyBlockReason.REVOKED, "research session service has been disposed")


    /**
     * Stop this context's live delivery and quarantine its spool so nothing can
     * upload under the next account (logout/account switch, Issue 03 F13).
     */
    fun quarantine(): Path? =
        try {
            manager().quarantineSpool()
        } catch (_: Exception) {
            null
        }

    /** The server's active enrollment id for this account, or `null`. */
    fun activeServerEnrollmentId(): String? {
        val baseUrl = resolveConfiguredBaseUrl() ?: return null
        return (ResearchJoinCodeResolver(baseUrl).discover() as? EnrollmentDiscovery.Active)?.enrollmentId
    }

    /**
     * Whether a research study currently owns this project's participant setup.
     *
     * Reads only the participant state of this service (session, held manifest,
     * typed block, or live collection), so the check is I/O-free and safe on a
     * background thread. Never throws: any failure is reported as `false` so
     * ordinary managed setup is unaffected.
     */
    fun hasStudyContext(): Boolean =
        try {
            state().holdsStudyContext
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

    /**
     * Membership authority for the manager's pre-bootstrap gate: the signed-in
     * account's enrollment state from `GET /api/research/participants/me`.
     * Never throws; an unconfigured backend or a failed check yields
     * [EnrollmentDiscovery.Unavailable] so the bootstrap API stays
     * authoritative.
     */
    private fun discoverEnrollment(): EnrollmentDiscovery {
        discoveryOverride?.let { return it() }
        val baseUrl = resolveConfiguredBaseUrl() ?: return EnrollmentDiscovery.Unavailable("research backend is not configured")
        return try {
            ResearchJoinCodeResolver(baseUrl).discover()
        } catch (_: Exception) {
            EnrollmentDiscovery.Unavailable("enrollment discovery failed")
        }
    }

    /**
     * Discover the account's server-side membership and act on it (Issue 03 E09).
     *
     * The project-local enrollment id is a hint only: the server's active
     * enrollment wins and replaces it; a terminal enrollment clears the hint and
     * returns blocked; no enrollment clears a stale hint and leaves the component
     * inactive without error; an unreachable server keeps the hint and returns
     * a retryable typed result so ordinary ACP setup remains fenced.
     */
    fun reconcileFromServer(): ResearchReconciliationResult {
        val settings = enrollmentSettings()
        val discovery = discoverEnrollment()
        if (disposed) return ResearchReconciliationResult.Unavailable("research session service has been disposed")
        return when (discovery) {
            is EnrollmentDiscovery.Active -> {
                settings?.setEnrollmentId(discovery.enrollmentId)
                val currentManager = manager
                val currentState = currentManager?.state()
                if (
                    currentState?.enrollmentId == discovery.enrollmentId &&
                    currentManager?.isActive == true
                ) {
                    // A provisioned study can still be NOT_STARTED until the first
                    // qualifying activity. Its participant state then cannot launch,
                    // but reactivating it would tear down a healthy proxy and mint
                    // another agent run on every auth-bridge retry.
                    ResearchReconciliationResult.StudyOwned(activation = null)
                } else {
                    ResearchReconciliationResult.StudyOwned(activate(discovery.enrollmentId))
                }
            }
            is EnrollmentDiscovery.Terminal -> {
                settings?.clear()
                resetManager(quarantine = true)
                ResearchReconciliationResult.Terminal(discovery.status)
            }
            EnrollmentDiscovery.None -> {
                // No membership on the server (including a stale hint from a
                // different account): clear the local id and stay inactive.
                settings?.clear()
                resetManager(quarantine = true)
                ResearchReconciliationResult.NoEnrollment
            }
            is EnrollmentDiscovery.Unavailable -> ResearchReconciliationResult.Unavailable(discovery.message)
        }
    }

    /** Compatibility wrapper retained for callers that only need the activation result. */
    fun reactivateFromServer(): ResearchActivationResult? =
        (reconcileFromServer() as? ResearchReconciliationResult.StudyOwned)?.activation

    /** Stop any running participant session (idempotent). */
    fun stop(): ResearchStopResult = manager().stop()

    /** Logout/account-switch cleanup; a later login receives a fresh manager. */
    fun onLogout() {
        resetManager(quarantine = true)
    }

    override fun dispose() {
        resetManager(quarantine = false, terminal = true)
    }

    /** Stable, opaque project/window key used for context + spool scoping. */
    private fun projectKeyForSession(): String = project.locationHash.ifBlank { project.name }

    /**
     * Context-scoped capability file: the configured path plus this window's
     * opaque context suffix, so two windows never overwrite each other's local
     * IPC credential.
     */
    private fun contextScopedCapabilityFile(configured: Path, contextId: String): Path {
        val name = configured.fileName?.toString().orEmpty().ifBlank { "capability" }
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        val scoped = "$stem-${contextId.removePrefix("ctx-").take(12)}$extension"
        return configured.parent?.resolve(scoped) ?: Path.of(scoped)
    }

    private fun buildManager(): ResearchSessionManager {
        managerFactoryOverride?.let { return it() }
        val contextId = ResearchSessionManager.opaqueContextId(projectKeyForSession())
        // IDE events are processed off the UI thread (see the manager's
        // eventExecutor): one daemon thread keeps their order.
        val executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "code4me-research-events").apply { isDaemon = true }
            }
        eventExecutor?.shutdown()
        eventExecutor = executor
        return ResearchSessionManager(
            eventExecutor = executor,
            projectKey = projectKeyForSession(),
            transport = participantTransport(resolveConfiguredBaseUrl(), { environment() }),
            compatibility = pluginCompatibility(),
            spoolProvider = { enrollmentId -> DurableSpool(spoolDirectory(enrollmentId)) },
            source = project.getService(IntellijIdeActivitySource::class.java),
            proxyRuntimeResolver =
                PackagedProxyRuntimeResolver.forPlugin(
                    allowDevelopmentRuntime = runtimeSettings()?.allowDevelopmentRuntime() ?: false,
                    developmentInterpreter = runtimeSettings()?.developmentInterpreter(),
                ),
            // Context-scoped entry: one window's registration is never removed by
            // another window's close.
            acpHostRegistration =
                AcpHostRegistration(
                    AcpHostRegistration.defaultRegistryPath(),
                    entryName = AcpHostRegistration.contextEntryName(contextId),
                ),
            agentEnvProvider = { managedAgentEnv() },
            capabilityFilePathProvider = {
                runtimeSettings()?.capabilityFilePath()?.let { contextScopedCapabilityFile(it, contextId) }
            },
            byoaAgentCommandProvider = { runtimeSettings()?.byoaAgentCommand() },
            serverBaseUrlProvider = { resolveConfiguredBaseUrl() },
            environmentProvider = { environment() },
            enrollmentDiscoveryProvider = { discoverEnrollment() },
            sessionStore = FileResearchSessionStore(),
            httpClient = CookieAwareApiClient.sharedOkHttpClient,
        )
    }

    private fun enrollmentSettings(): ResearchEnrollmentSettings? {
        enrollmentSettingsOverride?.let { return it }
        return try {
            project.getService(ResearchEnrollmentSettings::class.java)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun resetManager(quarantine: Boolean, terminal: Boolean = false) {
        val (previous, executor) = synchronized(this) {
            if (terminal) disposed = true
            val current = manager.also { manager = null }
            current to eventExecutor.also { eventExecutor = null }
        }
        if (previous == null) {
            executor?.shutdown()
            return
        }
        // A plain project close keeps the spool endpoint up briefly for the
        // assistant's agent processes to flush; a logout does not.
        val ipcGraceMs = if (terminal && !quarantine) ResearchSessionManager.IPC_CLOSE_GRACE_MS else 0L
        runCatching { previous.stop(ipcGraceMs) }
        if (quarantine) runCatching { previous.quarantineSpool() }
        // Queued events still append to the durable spool; the thread ends after them.
        executor?.shutdown()
    }

    /** Project runtime settings; a research misconfiguration never breaks the service. */
    private fun runtimeSettings(): ResearchRuntimeSettings? =
        try {
            project.getService(ResearchRuntimeSettings::class.java)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    /**
     * Best-effort resolution of the configured backend origin. A missing/partial
     * configuration (or an uninitialised service) yields `null`, which keeps the
     * participant on the retryable stub instead of guessing an endpoint.
     */
    private fun resolveConfiguredBaseUrl(): String? =
        try {
            resolveBaseUrl(getConfig().getServerConfig())
        } catch (_: Exception) {
            null
        }

    /**
     * Environment the real managed Code4Me agent needs: the same bridge directory
     * [me.code4me.services.agent.ParticipantAgentSetupService] registers it with.
     * Exception-safe: any failure yields an empty map so activation is unaffected.
     */
    private fun managedAgentEnv(): Map<String, String> =
        try {
            mapOf("CODE4ME_BRIDGE_DIR" to Path.of(PathManager.getSystemPath(), "code4me", "bridges").toString())
        } catch (_: Exception) {
            emptyMap()
        }

    private fun spoolDirectory(enrollmentId: String): Path =
        Path.of(
            PathManager.getSystemPath(),
            "code4me",
            "research",
            ResearchSessionManager.opaqueSpoolKey(enrollmentId),
        )

    companion object {
        private const val PLUGIN_ID = "me.code4me"
        private const val PARTICIPANT_AUDIENCE = "research-runtime"
        private const val NOT_CONFIGURED_MESSAGE = "Bootstrap transport is not configured for this build."

        /** Non-identifying host kind reported to the bootstrap API. */
        const val HOST_KIND: String = "IntelliJ IDEA"

        fun getInstance(project: Project): ResearchSessionService = project.service()

        /**
         * Build the participant bootstrap transport.
         *
         * A non-blank [baseUrl] yields a real [HttpBootstrapTransport]; otherwise
         * the historical retryable stub is returned so activation surfaces as
         * [ResearchActivationResult.Retryable] instead of collecting against an
         * unvalidated manifest. Pure and injectable so it is testable off-IDE.
         */
        fun participantTransport(
            baseUrl: String?,
            environment: () -> BootstrapEnvironment,
            client: Call.Factory = CookieAwareApiClient.sharedOkHttpClient,
        ): BootstrapTransport =
            if (baseUrl.isNullOrBlank()) {
                BootstrapTransport { _, _ ->
                    BootstrapTransportResult.Failure(NOT_CONFIGURED_MESSAGE, retryable = true)
                }
            } else {
                HttpBootstrapTransport(
                    baseUrl = baseUrl,
                    httpClient = client,
                    environment = environment,
                )
            }

        /**
         * Resolve the backend origin from [server].
         *
         * [ServerConfig.acpRuntimeBaseUrl] wins when set (the agent/plugin may need
         * a different reachable host). Otherwise the server `host` is normalised:
         * a scheme is added when missing, the configured port is applied when the
         * host does not already carry one, and the context path is appended when
         * the host has no path of its own.
         */
        fun resolveBaseUrl(server: ServerConfig?): String? {
            if (server == null) return null
            val runtimeBaseUrl = server.acpRuntimeBaseUrl?.trim()
            if (!runtimeBaseUrl.isNullOrBlank()) return runtimeBaseUrl.trimEnd('/')

            val host = server.host.trim()
            if (host.isEmpty()) return null
            val withScheme =
                if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
            val uri =
                try {
                    URI(withScheme)
                } catch (_: Exception) {
                    return null
                }
            val scheme = uri.scheme ?: "http"
            val hostName = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else server.port
            val authority = if (port > 0) "$hostName:$port" else hostName
            val hostPath = uri.path.orEmpty().trimEnd('/')
            val contextPath = server.contextPath.trim().trim('/')
            val path =
                when {
                    hostPath.isNotEmpty() -> hostPath
                    contextPath.isNotEmpty() -> "/$contextPath"
                    else -> ""
                }
            return "$scheme://$authority$path"
        }

        /** Best-effort host tuple; never reads editor/project content. */
        fun environment(): BootstrapEnvironment =
            BootstrapEnvironment(
                os = safeEnvironmentValue { canonicalOs(System.getProperty("os.name")) },
                arch = safeEnvironmentValue { canonicalArch(System.getProperty("os.arch")) },
                ideBuild = safeEnvironmentValue { ApplicationInfo.getInstance().getBuild().asString() },
                pluginVersion =
                    safeEnvironmentValue {
                        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
                    },
                hostKind = HOST_KIND,
            )

        /** Local plugin/environment compatibility tuple evaluated against a manifest. */
        fun pluginCompatibility(): PluginCompatibility {
            val version = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "0.0.0"
            return PluginCompatibility(
                pluginVersion = version,
                expectedAudience = PARTICIPANT_AUDIENCE,
            )
        }

        private fun safeEnvironmentValue(read: () -> String?): String? =
            try {
                read()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }

        /**
         * Canonical OS name matching the backend registry (`macos`/`windows`/
         * `linux`), so a cosmetic `os.name` difference never blocks bootstrap.
         */
        private fun canonicalOs(raw: String?): String? {
            val name = raw?.lowercase() ?: return null
            return when {
                "mac" in name || name == "darwin" -> "macos"
                name.startsWith("win") -> "windows"
                "linux" in name -> "linux"
                else -> name
            }
        }

        /** Canonical architecture matching the backend registry (`aarch64`/`x64`). */
        private fun canonicalArch(raw: String?): String? {
            val arch = raw?.lowercase() ?: return null
            return when (arch) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64", "x64" -> "x64"
                else -> arch
            }
        }
    }
}
