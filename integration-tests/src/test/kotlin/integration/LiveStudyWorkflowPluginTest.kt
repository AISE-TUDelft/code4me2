package integration

import com.intellij.testFramework.HeavyPlatformTestCase
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.research.bootstrap.*
import me.code4me.research.proxy.HostPlatform
import me.code4me.research.spool.DurableSpool
import me.code4me.research.spool.SpoolUploader
import me.code4me.research.telemetry.*
import me.code4me.services.app.getAppService
import me.code4me.services.config.models.ServerConfig
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/** Real HTTP and plugin code against the harness-owned disposable backend. */
class LiveStudyWorkflowPluginTest : HeavyPlatformTestCase() {
    fun testLoginBootstrapAndTelemetryDelivery() {
        val baseUrl = System.getenv("CODE4ME_E2E_BASE_URL").orEmpty()
        assumeTrue("CODE4ME_E2E_BASE_URL is required for live tests", baseUrl.isNotBlank())
        val app = getAppService()
        CookieAwareApiClient.cookieManager.cookieStore.removeAll()
        app.setServerConfig(ServerConfig(baseUrl, 0, "", 30, baseUrl))
        app.authenticateUser(System.getenv("CODE4ME_E2E_EMAIL"), System.getenv("CODE4ME_E2E_PASSWORD"))
        assertFalse("login must store the server's auth cookie", CookieAwareApiClient.getAuthToken().isNullOrBlank())
        val enrollment = ResearchJoinCodeResolver(baseUrl).discover()
        assertTrue("the enrolled account must be discovered", enrollment is EnrollmentDiscovery.Active)
        val bootstrap = BootstrapClient(
            HttpBootstrapTransport(baseUrl, environment = {
                BootstrapEnvironment(HostPlatform.os(), HostPlatform.arch(), pluginVersion = "e2e", hostKind = "IntelliJ IDEA")
            }),
            PluginCompatibility("e2e", "research-runtime"),
            contextId = "e2e-kotlin-${UUID.randomUUID()}",
        ).acquire((enrollment as EnrollmentDiscovery.Active).enrollmentId)
        assertEquals("bootstrap must validate the live server manifest", BootstrapStatus.OK, bootstrap.status)
        val manifest = requireNotNull(bootstrap.manifest)
        val directory = Files.createTempDirectory("code4me-live-spool")
        try {
            val spool = DurableSpool(directory)
            val event = CanonicalEvent(
                eventId = UUID.randomUUID().toString(), eventType = "tool.started", source = EventSource.ACP,
                studyId = manifest.studyId, enrollmentId = manifest.enrollmentId,
                researchSessionId = manifest.researchSession.researchSessionId,
                occurredAt = Instant.now().toString(), emitterId = "e2e-kotlin-fixture", emitterSequence = 1,
                lifecycleState = "started", payload = mapOf("status" to "started"),
                provenance = Provenance(EventSource.ACP, normalizerVersion = "e2e", fidelity = CanonicalFidelity.NORMALIZED),
            )
            spool.append(event)
            // Reopen from disk, proving delivery isn't only an in-memory append.
            val reopened = DurableSpool(directory)
            val uploader = SpoolUploader(reopened, baseUrl, manifest.sessionCapabilityObject(),
                "e2e-kotlin-fixture", CookieAwareApiClient.sharedOkHttpClient)
            val delivered = uploader.uploadOnce()
            assertTrue("upload must perform an HTTP request", delivered.attempted)
            assertEquals("server must acknowledge the event", 1, delivered.acknowledged)
            assertEquals("no event may be rejected", 0, delivered.rejected)
            assertEquals("acknowledged event must leave the pending spool", 0, delivered.pendingCount)
        } finally {
            directory.toFile().deleteRecursively()
            CookieAwareApiClient.cookieManager.cookieStore.removeAll()
        }
    }
}
