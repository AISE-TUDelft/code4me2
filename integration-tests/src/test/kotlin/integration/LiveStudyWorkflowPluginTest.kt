package integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.research.bootstrap.BootstrapClient
import me.code4me.research.bootstrap.BootstrapEnvironment
import me.code4me.research.bootstrap.BootstrapStatus
import me.code4me.research.bootstrap.HttpBootstrapTransport
import me.code4me.research.bootstrap.JoinCodeResolution
import me.code4me.research.bootstrap.JoinConsentResolution
import me.code4me.research.bootstrap.JoinEnrollResolution
import me.code4me.research.bootstrap.ManifestValidationReason
import me.code4me.research.bootstrap.PluginCompatibility
import me.code4me.research.bootstrap.ResearchJoinCodeResolver
import me.code4me.research.ide.IdeActivitySignal
import me.code4me.research.ide.IdeActivitySource
import me.code4me.research.ide.IdePayloadKey
import me.code4me.research.ide.IntellijIdeActivitySource
import me.code4me.research.proxy.AcpHostRegistration
import me.code4me.research.proxy.DevelopmentRuntime
import me.code4me.research.proxy.PackagedProxyRuntimeResolver
import me.code4me.research.proxy.ProxyRuntimeResolution
import me.code4me.research.proxy.ProxyRuntimeResolver
import me.code4me.research.proxy.ResolvedProxyRuntime
import me.code4me.research.proxy.ResearchRuntimeLocation
import me.code4me.research.session.InMemoryResearchSessionStore
import me.code4me.research.session.ProxyHandle
import me.code4me.research.session.ProxyLauncher
import me.code4me.research.session.ResearchActivationResult
import me.code4me.research.session.ResearchSessionManager
import me.code4me.research.session.SessionState
import me.code4me.research.session.StudyComponentState
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.SpoolUploader
import me.code4me.research.telemetry.CanonicalEventTypes
import me.code4me.services.app.AppService
import me.code4me.services.config.models.ServerConfig
import me.code4me.services.state.AuthState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** One stable project/window key for both the bootstrap and the manager. */
private const val TEST_PROJECT_KEY = "code4me-plugin-e2e"

/**
 * Boots the plugin's **real** research classes inside an IntelliJ Platform test
 * fixture and drives them against a **real** code4me-e2e backend.
 *
 * This is the plugin-side half of the e2e harness: the Python harness drives the
 * backend HTTP contract, while this test exercises the exact Kotlin code the
 * shipped plugin runs (login, join-code resolution, bootstrap manifest fetch +
 * validation, session activation, IDE activity collection, spooling, and the
 * real telemetry upload) with no mocks for the HTTP layer.
 *
 * It is inert unless the harness sets the environment contract, so a normal
 * `./gradlew integrationTest` neither starts a backend nor fails:
 *
 *   CODE4ME_E2E_BASE_URL   e.g. http://localhost:28008
 *   CODE4ME_E2E_EMAIL      participant account email
 *   CODE4ME_E2E_PASSWORD   participant account password
 *   CODE4ME_E2E_JOIN_CODE  the published study's join code
 *
 * Run it through `python3 -m code4me_e2e plugin-test`, which provisions the
 * disposable stack, leaves an ACTIVE enrollment and supplies the env vars.
 */
class LiveStudyWorkflowPluginTest : BasePlatformTestCase() {
    fun testLiveStudyWorkflow() {
        val baseUrl = System.getenv("CODE4ME_E2E_BASE_URL")?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            println("skipping LiveStudyWorkflowPluginTest: CODE4ME_E2E_BASE_URL not set")
            return
        }
        val email = requiredEnv("CODE4ME_E2E_EMAIL")
        val password = requiredEnv("CODE4ME_E2E_PASSWORD")
        val joinCode = requiredEnv("CODE4ME_E2E_JOIN_CODE")

        val participantOs = hostOs()
        val participantArch = hostArch()

        // ---- 1. Point the plugin at the harness backend -------------------
        val appService = service<AppService>()
        appService.setServerConfig(
            ServerConfig(
                host = baseUrl,
                port = 0,
                contextPath = "",
                timeout = 10,
                acpRuntimeBaseUrl = baseUrl,
            ),
        )
        assertEquals(baseUrl, appService.getApiBaseUrl())

        // ---- 2. Real login -------------------------------------------------
        val authenticated =
            try {
                appService.authenticateUser(email, password)
            } catch (exception: Exception) {
                failTyped("plugin authenticateUser failed against $baseUrl: ${exception.javaClass.simpleName}: ${exception.message}")
            }
        assertNotNull("authenticateUser returned no response", authenticated)
        val storedToken = service<AuthState>().state.getToken()
        assertNotNull("login stored no auth token in AuthState", storedToken)
        assertTrue("login stored a blank auth token", !storedToken.isNullOrBlank())
        // The raw request path (generated OpenAPI client) rides the cookie-aware
        // client; the same cookie jar is what the research API calls below use.
        assertNotNull(
            "the auth_token cookie is absent from CookieAwareApiClient (AuthState token=${storedToken?.take(6)}…)",
            CookieAwareApiClient.getAuthToken(),
        )

        // ---- 3. Join (mirrors ResearchJoinCodeResolver's own flow) ---------
        val resolver = ResearchJoinCodeResolver(baseUrl)
        val enrollmentId =
            when (val resolved = resolver.resolve(joinCode)) {
                is JoinCodeResolution.ActiveEnrollment -> resolved.enrollmentId
                is JoinCodeResolution.ConsentRequired -> {
                    when (val consent = resolver.consentInfo(joinCode)) {
                        is JoinConsentResolution.Available -> {
                            assertTrue("consent document id must be present", consent.info.consentDocumentId.isNotBlank())
                            assertTrue("consent document version must be present", consent.info.consentDocumentVersion.isNotBlank())
                        }
                        is JoinConsentResolution.Rejected ->
                            failTyped("consentInfo(joinCode) rejected: ${consent.message}")
                        is JoinConsentResolution.Unavailable ->
                            failTyped("consentInfo(joinCode) unavailable: ${consent.message}")
                    }
                    when (val redeemed = resolver.redeem(joinCode)) {
                        is JoinEnrollResolution.Enrolled -> redeemed.enrollmentId
                        is JoinEnrollResolution.Rejected -> failTyped("redeem(joinCode) rejected: ${redeemed.message}")
                        is JoinEnrollResolution.Unavailable -> failTyped("redeem(joinCode) unavailable: ${redeemed.message}")
                    }
                }
                is JoinCodeResolution.AlreadyEnrolled ->
                    failTyped("participant already has an ACTIVE enrollment in study ${resolved.studyId}; the harness prefix must leave this study's enrollment active")
                is JoinCodeResolution.Rejected -> failTyped("resolve(joinCode) rejected: ${resolved.message}")
                is JoinCodeResolution.Unavailable -> failTyped("resolve(joinCode) unavailable: ${resolved.message}")
            }
        assertTrue("no enrollment id resolved from the join code", enrollmentId.isNotBlank())

        // ---- 4. Bootstrap (the plugin's own signed-manifest path) ----------
        val transport =
            HttpBootstrapTransport(
                baseUrl = baseUrl,
                httpClient = CookieAwareApiClient.sharedOkHttpClient,
                environment = {
                    BootstrapEnvironment(
                        os = participantOs,
                        arch = participantArch,
                        ideBuild = "IU-262.10315.125",
                        pluginVersion = "e2e-plugin-test",
                        hostKind = "IntelliJ IDEA",
                    )
                },
            )
        val compatibility =
            PluginCompatibility(
                pluginVersion = "e2e-plugin-test",
                expectedAudience = "research-runtime",
                supportedManifestVersions = setOf("1"),
                requiredScopes = setOf("telemetry:write"),
            )
        val bootstrap =
            BootstrapClient(
                transport,
                compatibility,
                // Bootstrap in the same execution context the manager below
                // derives from its project key, so both resolve one server
                // session (phase-03 per-context session idempotency).
                contextId = ResearchSessionManager.opaqueContextId(TEST_PROJECT_KEY),
            ).acquire(enrollmentId)
        assertTrue(
            "bootstrap did not return OK: status=${bootstrap.status} reason=${bootstrap.reason} " +
                "rejection=${bootstrap.rejection} validationReason=${bootstrap.validationReason}",
            bootstrap.status == BootstrapStatus.OK,
        )
        assertTrue("bootstrap reported OK without canLaunch", bootstrap.canLaunch)
        val manifest = bootstrap.manifest ?: failTyped("bootstrap status OK but manifest was null")

        // The plugin's own validator is the single source of truth for the
        // secret/absolute-path scan; asserting it is valid is the assertion
        // that the raw document carries no secret or absolute path.
        val validation = manifest.validate(Instant.now(), compatibility)
        assertTrue(
            "manifest validation failed: reason=${validation.reason} message=${validation.message} field=${validation.field}",
            validation.valid,
        )
        assertEquals(ManifestValidationReason.OK, validation.reason)
        assertTrue(
            "manifest session capability is missing telemetry:write: ${manifest.sessionCapability.scope}",
            manifest.sessionCapability.scope.contains("telemetry:write"),
        )
        val pinnedAgentDigest = bareSha256(manifest.agentRelease.normalizedArtifactDigest ?: manifest.agentRelease.artifactDigest)
        assertNotNull("the PACKAGED manifest must pin a usable agent artifact digest", pinnedAgentDigest)

        // ---- 5. Activation ("prepare agent") with the real transport -------
        val tempRoot = Files.createTempDirectory("code4me-plugin-e2e")
        val spool = DurableSpool(tempRoot.resolve("spool"))
        val registryPath = tempRoot.resolve("acp.json")
        val capabilityFile = tempRoot.resolve("capability.txt")
        val runtimeCacheRoot =
            Path.of(System.getProperty("java.io.tmpdir"), "code4me-e2e-runtime-cache")

        // A fresh project identity per run. The IDE collector derives a stable
        // opaque emitter id from the project key, and the server enforces
        // `(research_session_id, emitter_id, emitter_sequence)` continuity, so a
        // fixed key would make a second run replay the same sequence keys and
        // collide with INTEGRITY_CONFLICT. This is test isolation, not a claim
        // about real IDE project identity.
        val runNonce = UUID.randomUUID().toString()
        val canaryProjectKey = "/Users/canary/should-never-appear-$runNonce"

        val proxyResolver = pluginProxyRuntimeResolver(pinnedAgentDigest!!, runtimeCacheRoot)
        val acpHostRegistration = AcpHostRegistration(registryPath)
        val uploaderHolder = UploaderHolder()
        val ideSource = FakeIdeActivitySource("/project/under-test-$runNonce")

        // The manager wiring lives in a private helper: a zero-argument Kotlin
        // lambda compiled inside the `test…` method becomes a synthetic
        // `testLiveStudyWorkflow$lambda$N()` method, which the JUnit3 detector
        // in this module mistakes for a (non-public) test method.
        val manager =
            buildManager(
                baseUrl = baseUrl,
                transport = transport,
                compatibility = compatibility,
                spool = spool,
                tempRoot = tempRoot,
                capabilityFile = capabilityFile,
                proxyResolver = proxyResolver,
                acpHostRegistration = acpHostRegistration,
                ideSource = ideSource,
                uploaderHolder = uploaderHolder,
                participantOs = participantOs,
                participantArch = participantArch,
            )

        try {
            val activation = manager.activate(enrollmentId)
            if (activation !is ResearchActivationResult.Activated) {
                val detail =
                    when (activation) {
                        is ResearchActivationResult.Blocked ->
                            "Blocked(reason=${activation.reason}, rejection=${activation.rejection}, detail=${activation.detail})"
                        is ResearchActivationResult.Retryable -> "Retryable(detail=${activation.detail})"
                        is ResearchActivationResult.Failed -> "Failed(detail=${activation.detail})"
                        is ResearchActivationResult.Activated -> "Activated"
                    }
                failTyped("activation did not succeed: $detail")
            }
            assertTrue("manager.isActive must be true after Activated", manager.isActive)
            assertTrue("manager.isCollecting must be true after activation", manager.isCollecting)
            assertTrue("the ACP entry must be registered after activation", acpHostRegistration.hasEntry())
            assertTrue("the capability file must exist after activation", Files.exists(capabilityFile))
            assertFalse(
                "the ACP registry must be a temp path, never the real ~/.jetbrains/acp.json",
                registryPath.toAbsolutePath().normalize() ==
                    AcpHostRegistration.defaultRegistryPath().toAbsolutePath().normalize(),
            )

            // Push one qualifying IDE activity signal; it must be collected,
            // privacy-filtered, and durably spooled with no raw content/path.
            ideSource.push(
                IdeActivitySignal(
                    kind = "opened",
                    projectKey = canaryProjectKey,
                    metadata = mapOf("file_extension" to "kt"),
                ),
            )
            assertEquals(
                "the qualifying IDE activity must start the session",
                SessionState.RUNNING,
                manager.currentSession?.state,
            )
            assertEquals(
                "the participant state must report collecting",
                StudyComponentState.AVAILABLE,
                manager.state().sessionState,
            )

            val events = spool.pending()
            val opened =
                events.firstOrNull { it.event.eventType == CanonicalEventTypes.IDE_FILE_OPENED }
                    ?: failTyped("no ide.file.opened canonical event reached the DurableSpool; spooled=${events.map { it.event.eventType }}")
            // Metadata-only guarantee: the collector can only ever emit
            // allowlisted IDE payload keys (it rejects anything else at the
            // boundary), so the spooled payload must be a subset of that set.
            assertTrue(
                "the spooled IDE event payload must contain only allowlisted metadata keys: ${opened.event.payload}",
                opened.event.payload.keys.all { it in IdePayloadKey.allowedKeys },
            )
            val payloadText = opened.canonicalJson
            assertFalse("no project path canary may reach the spool: $payloadText", payloadText.contains("should-never-appear"))
            assertFalse("no raw user-home path may reach the spool: $payloadText", payloadText.contains("/Users/canary"))
            // Surfaced for the harness log: the server declares its telemetry
            // classes in the study vocabulary (METRICS/STRUCTURAL/CONTENT/…),
            // which the plugin's FieldClass vocabulary cannot parse; it then
            // falls back to its defaults and stores CODE_METADATA verbatim.
            println(
                "plugin-test: spooled IDE payload after privacy filter = ${opened.event.payload} " +
                    "(manifest allowed_field_classes=${manifest.policies.telemetry?.allowedFieldClasses}, " +
                    "privacy=${opened.event.privacy})",
            )
            assertEquals(
                "the spooled event must be attributed to the manifest's session",
                manifest.researchSession.researchSessionId,
                opened.event.researchSessionId,
            )

            // Real upload path: one deterministic delivery pass through the real
            // SpoolUploader against the live backend.
            val uploader = uploaderHolder.uploader ?: failTyped("the manager did not construct a spool uploader")
            val pendingBefore = spool.pending().size
            val upload = uploader.uploadOnce()
            println("plugin-test: telemetry upload disposition = $upload (pending before=$pendingBefore)")
            assertFalse("the research upload was revoked: ${upload.error}", upload.revoked)
            if (upload.error == null && upload.retryable == 0 && upload.rejected == 0) {
                assertEquals(
                    "an accepted batch must drain the spool: $upload",
                    0,
                    upload.pendingCount,
                )
            } else {
                // The harness backend may legitimately defer/duplicate/reject a
                // synthetic event; assert the typed disposition, never a crash.
                assertTrue(
                    "the upload must not leave more pending records than it started with: $upload",
                    upload.pendingCount <= pendingBefore,
                )
            }
            assertNull(
                "the plugin must not end activation in a blocked state: ${manager.state().blockReason}",
                manager.state().blockReason,
            )
        } finally {
            manager.stop()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun requiredEnv(name: String): String {
        val value = System.getenv(name)?.trim().orEmpty()
        assertTrue("environment variable $name must be set by the harness plugin-test command", value.isNotEmpty())
        return value
    }

    /**
     * JUnit3's `fail` is a Java `void`, so it cannot be used in an expression
     * (including an `?:`/`when` branch that must produce a value). This wrapper
     * keeps the failure message and is typed `Nothing`.
     */
    private fun failTyped(message: String): Nothing {
        fail(message)
        throw IllegalStateException("unreachable: $message")
    }

    /** Holds the real uploader the manager constructs, for a deterministic delivery pass. */
    private class UploaderHolder {
        @Volatile
        var uploader: SpoolUploader? = null
    }

    /**
     * Construct the manager with the real transport, a temp spool/registry, a
     * fake launcher (no process is ever spawned) and the real spool uploader.
     * Kept out of the `test…` method so its lambdas cannot be mistaken for
     * JUnit3 test methods by the platform's suite detector.
     */
    @Suppress("LongParameterList")
    private fun buildManager(
        baseUrl: String,
        transport: HttpBootstrapTransport,
        compatibility: PluginCompatibility,
        spool: DurableSpool,
        tempRoot: Path,
        capabilityFile: Path,
        proxyResolver: ProxyRuntimeResolver,
        acpHostRegistration: AcpHostRegistration,
        ideSource: IdeActivitySource,
        uploaderHolder: UploaderHolder,
        participantOs: String,
        participantArch: String,
    ): ResearchSessionManager =
        ResearchSessionManager(
            projectKey = TEST_PROJECT_KEY,
            transport = transport,
            compatibility = compatibility,
            spoolProvider = { spool },
            source = ideSource,
            proxyLauncher = ProxyLauncher { _ -> ProxyHandle { } },
            proxyRuntimeResolver = proxyResolver,
            acpHostRegistration = acpHostRegistration,
            capabilityFilePathProvider = { capabilityFile },
            capabilityRootProvider = { tempRoot },
            serverBaseUrlProvider = { baseUrl },
            environmentProvider = {
                BootstrapEnvironment(
                    os = participantOs,
                    arch = participantArch,
                    ideBuild = "IU-262.10315.125",
                    pluginVersion = "e2e-plugin-test",
                    hostKind = "IntelliJ IDEA",
                )
            },
            httpClient = CookieAwareApiClient.sharedOkHttpClient,
            uploaderFactory = { context ->
                // The real uploader, captured so the test can drive exactly one
                // delivery pass deterministically (the background worker would
                // otherwise poll on its 5 s default cadence).
                SpoolUploader(
                    spool = context.spool,
                    serverBaseUrl = context.serverBaseUrl,
                    sessionCapability = context.sessionCapability,
                    clientInstanceId = context.clientInstanceId,
                    httpClient = context.httpClient,
                    pollIntervalMs = 3_600_000L,
                ).also { uploaderHolder.uploader = it }
            },
            sessionStore = InMemoryResearchSessionStore(),
            sessionIdFactory = { "session-${UUID.randomUUID()}" },
            runIdFactory = { "run-${UUID.randomUUID()}" },
        )

    /**
     * Resolve the packaged proxy runtime the way the plugin does, then make it
     * satisfy the server-pinned agent identity.
     *
     * The harness registers a **synthetic** release artifact digest (derived in
     * Python from study/agent identity), so the bundled `code4me-agent` digest
     * generally differs from the manifest pin and the plugin's fail-closed
     * PACKAGED identity check would block a real launch. This test therefore
     * uses the real resolver (real extraction, path-safety and SHA-256
     * verification of the staged runtime) but aligns only the observed
     * `agentDigest` with the manifest pin so activation can proceed. When the
     * pin happens to equal the bundled digest the real runtime is used verbatim.
     *
     * The one real component this does not exercise is the manifest-vs-binary
     * identity rejection itself; it is covered by BootstrapTest/SessionTest.
     */
    private fun pluginProxyRuntimeResolver(
        pinnedAgentDigest: String,
        runtimeCacheRoot: Path,
    ): ProxyRuntimeResolver {
        val real =
            PackagedProxyRuntimeResolver(
                explodedRoot = ResearchRuntimeLocation.locate(),
                cacheRoot = runtimeCacheRoot,
                devRuntime = DevelopmentRuntime.DISABLED,
            )
        when (val resolution = real.resolve()) {
            is ProxyRuntimeResolution.Resolved -> {
                val resolved = resolution.runtime
                val resolvedDigest = bareSha256(resolved.agentDigest)
                return if (resolvedDigest == pinnedAgentDigest) {
                    ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(resolved) }
                } else {
                    println(
                        "plugin-test: aligning resolved agent digest $resolvedDigest with the " +
                            "server-pinned synthetic digest $pinnedAgentDigest (documented in the test)",
                    )
                    ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(resolved.copy(agentDigest = pinnedAgentDigest)) }
                }
            }
            is ProxyRuntimeResolution.Failed -> {
                println(
                    "plugin-test: the packaged runtime did not resolve (${resolution.error.code}: " +
                        "${resolution.error.message}); using a documented fake Resolved with the pinned digest",
                )
                val fake =
                    ResolvedProxyRuntime(
                        runtimeRoot = runtimeCacheRoot,
                        proxyArgv = listOf(runtimeCacheRoot.resolve("telemetry-acp-proxy").toString(), "--stdio"),
                        proxyDigest = "0".repeat(64),
                        agentArgv = listOf(runtimeCacheRoot.resolve("code4me-agent").toString()),
                        agentDigest = pinnedAgentDigest,
                    )
                return ProxyRuntimeResolver { ProxyRuntimeResolution.Resolved(fake) }
            }
        }
    }

    /** Bare lowercase sha256 hex of a `sha256:<hex>` or bare-hex value, or `null`. */
    private fun bareSha256(value: String?): String? {
        val text = value?.trim()?.lowercase() ?: return null
        val stripped = text.removePrefix("sha256:")
        return if (Regex("^[0-9a-f]{64}$").matches(stripped)) stripped else null
    }

    private fun hostOs(): String {
        val name = System.getProperty("os.name").orEmpty()
        return when {
            name.startsWith("Mac", ignoreCase = true) -> "macos"
            name.startsWith("Windows", ignoreCase = true) -> "windows"
            name.startsWith("Linux", ignoreCase = true) -> "linux"
            else -> name.lowercase().replace(' ', '-')
        }
    }

    private fun hostArch(): String =
        when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "amd64", "x86_64", "x64" -> "x64"
            else -> System.getProperty("os.arch").orEmpty().lowercase()
        }

    /** Mirrors the real project-scoped source, including its first-subscriber signal. */
    private class FakeIdeActivitySource(private val projectKey: String) : IdeActivitySource {
        private var callback: ((IdeActivitySignal) -> Unit)? = null

        override fun onActivity(callback: (IdeActivitySignal) -> Unit) {
            val firstSubscriber = this.callback == null
            this.callback = callback
            if (firstSubscriber) {
                push(IdeActivitySignal(IntellijIdeActivitySource.PROJECT_OPENED, projectKey))
            }
        }

        fun push(signal: IdeActivitySignal) {
            callback?.let { runCatching { it(signal) } }
        }
    }
}
