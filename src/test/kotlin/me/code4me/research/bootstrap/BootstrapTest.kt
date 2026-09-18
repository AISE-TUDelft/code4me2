package me.code4me.research.bootstrap

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.code4me.research.telemetry.canonicalJson
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// --------------------------------------------------------------------------
// BootstrapClientTest.kt
// --------------------------------------------------------------------------

class BootstrapClientTest {
    private class FakeTransport(
        private val responses: MutableList<BootstrapTransportResult>,
    ) : BootstrapTransport {
        var callCount = 0
        val requestedEnrollmentIds = mutableListOf<String>()

        override fun fetch(enrollmentId: String, contextId: String): BootstrapTransportResult {
            callCount++
            requestedEnrollmentIds.add(enrollmentId)
            return responses.removeAt(0)
        }
    }

    private fun client(
        transport: BootstrapTransport,
        cache: ManifestCache = InMemoryManifestCache(),
        now: () -> Instant = { VALID_NOW },
    ): BootstrapClient =
        BootstrapClient(
            transport = transport,
            compatibility = compatibility(),
            cache = cache,
            clock = now,
        )

    @Test
    fun `first fetch validates and returns a launchable manifest`() {
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Success(manifestJson())))
        val result = client(transport).acquire("enrollment-1")

        assertEquals(BootstrapStatus.OK, result.status)
        assertTrue(result.canLaunch)
        assertNotNull(result.manifest)
        assertFalse(result.fromCache)
        assertEquals(1, transport.callCount)
        assertEquals(listOf("enrollment-1"), transport.requestedEnrollmentIds)
    }

    @Test
    fun `cached manifest within expiry is reused without a transport call`() {
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Success(manifestJson())))
        val client = client(transport)

        val first = client.acquire("enrollment-1")
        val second = client.acquire("enrollment-1")

        assertEquals(BootstrapStatus.OK, first.status)
        assertFalse(first.fromCache)
        assertEquals(BootstrapStatus.OK, second.status)
        assertTrue(second.fromCache)
        assertEquals(first.manifest, second.manifest)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `distinct execution contexts never share a cached manifest`() {
        val transport =
            FakeTransport(
                mutableListOf(
                    BootstrapTransportResult.Success(manifestJson()),
                    BootstrapTransportResult.Success(manifestJson()),
                ),
            )
        val client = client(transport)

        val firstContext = client.acquire("enrollment-1", "ctx-a")
        val reused = client.acquire("enrollment-1", "ctx-a")
        val secondContext = client.acquire("enrollment-1", "ctx-b")

        assertEquals(BootstrapStatus.OK, firstContext.status)
        assertTrue(reused.fromCache)
        assertEquals(BootstrapStatus.OK, secondContext.status)
        // ctx-a cached once; ctx-b fetched separately.
        assertFalse(secondContext.fromCache)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `expired cache entry triggers a refresh`() {
        val cache = InMemoryManifestCache()
        val expired = BootstrapManifest.parse(manifestJson(overrides = mapOf("expires_at" to "2026-01-01T00:00:01Z")))
        cache.put("enrollment-1|default", expired)

        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Success(manifestJson())))
        val client = client(transport, cache)

        val result = client.acquire("enrollment-1")

        assertEquals(BootstrapStatus.OK, result.status)
        assertEquals(1, transport.callCount)
        assertFalse(result.fromCache)
        assertTrue(result.manifest!!.expiresAt > expired.expiresAt)
        assertEquals(result.manifest, cache.get("enrollment-1|default"))
    }

    @Test
    fun `refresh returning another expired manifest asks for refresh without blocking`() {
        val transport =
            FakeTransport(
                mutableListOf(
                    BootstrapTransportResult.Success(
                        manifestJson(overrides = mapOf("expires_at" to "2026-01-01T00:00:01Z")),
                    ),
                ),
            )

        val result = client(transport).acquire("enrollment-1")

        assertEquals(BootstrapStatus.REFRESH_REQUIRED, result.status)
        assertFalse(result.canLaunch)
        assertFalse(result.manifest == null)
        assertEquals(ManifestValidationReason.EXPIRED, result.validationReason)
    }

    @Test
    fun `revoked enrollment is blocked and the cache is cleared`() {
        val cache = InMemoryManifestCache()
        // Cache holds a stale (expired) entry, so the client refreshes and learns of revocation.
        cache.put(
            "enrollment-1|default",
            BootstrapManifest.parse(manifestJson(overrides = mapOf("expires_at" to "2026-01-01T00:00:01Z"))),
        )
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Revoked("withdrawn")))

        val result = client(transport, cache).acquire("enrollment-1")

        assertEquals(BootstrapStatus.BLOCKED, result.status)
        assertNull(result.manifest)
        assertFalse(result.canLaunch)
        assertEquals("withdrawn", result.reason)
        assertNull(cache.get("enrollment-1|default"))
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `transport failure returns a typed retryable result`() {
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Failure("timeout", retryable = true)))

        val result = client(transport).acquire("enrollment-1")

        assertEquals(BootstrapStatus.RETRYABLE, result.status)
        assertNull(result.manifest)
        assertFalse(result.canLaunch)
        assertEquals("timeout", result.reason)
    }

    @Test
    fun `non-retryable transport failure is blocked`() {
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Failure("forbidden", retryable = false)))

        val result = client(transport).acquire("enrollment-1")

        assertEquals(BootstrapStatus.BLOCKED, result.status)
        assertNull(result.manifest)
    }

    @Test
    fun `malformed manifest is blocked rather than launched`() {
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Success("{not json")))

        val result = client(transport).acquire("enrollment-1")

        assertEquals(BootstrapStatus.BLOCKED, result.status)
        assertEquals(ManifestValidationReason.MALFORMED, result.validationReason)
        assertNull(result.manifest)
    }

    @Test
    fun `invalid manifest is blocked and never cached`() {
        val cache = InMemoryManifestCache()
        val transport =
            FakeTransport(
                mutableListOf(BootstrapTransportResult.Success(manifestJson(digestOverride = "0".repeat(64)))),
            )

        val result = client(transport, cache).acquire("enrollment-1")

        assertEquals(BootstrapStatus.BLOCKED, result.status)
        assertEquals(ManifestValidationReason.DIGEST_MISMATCH, result.validationReason)
        assertNull(cache.get("enrollment-1|default"))
    }

    @Test
    fun `invalidate drops a cached manifest`() {
        val transport = FakeTransport(mutableListOf(BootstrapTransportResult.Success(manifestJson())))
        val client = client(transport)
        client.acquire("enrollment-1")
        assertNotNull(client.cached("enrollment-1"))

        client.invalidate("enrollment-1")

        assertNull(client.cached("enrollment-1"))
    }
}

// --------------------------------------------------------------------------
// BootstrapManifestTest.kt
// --------------------------------------------------------------------------

class BootstrapManifestTest {
    @Test
    fun `valid manifest parses and validates`() {
        val manifest = validManifest()

        assertEquals("1", manifest.manifestVersion)
        assertEquals(AUDIENCE, manifest.audience)
        assertEquals("study-1", manifest.studyId)
        assertEquals("enrollment-1", manifest.enrollmentId)
        assertEquals("session-1", manifest.researchSession.researchSessionId)
        assertEquals("assignment-1", manifest.assignment.assignmentId)
        assertEquals(VALID_ARTIFACT_DIGEST, manifest.agentRelease.artifactDigest)
        assertEquals("codex-v1", manifest.agentRelease.adapterVersion)
        assertEquals(30L, manifest.policies.privacy?.retentionDays)
        assertEquals(600L, manifest.policies.session?.idleTimeoutSeconds)
        assertTrue(manifest.sessionCapability.scope.contains("telemetry:write"))
        assertEquals("receipt-1", manifest.compatibilityReceiptRef)

        assertTrue(manifest.digestMatches())
        val validation = manifest.validate(VALID_NOW, compatibility())
        assertTrue(validation.valid, validation.message)
        assertEquals(ManifestValidationReason.OK, validation.reason)
    }

    @Test
    fun `digest excludes the manifest digest and signature`() {
        val withSignature = manifestDocument(overrides = mapOf("signature" to "deadbeef"))
        val withoutSignature = manifestDocument()
        assertEquals(
            BootstrapManifest.computeManifestDigest(withoutSignature),
            BootstrapManifest.computeManifestDigest(withSignature),
        )
    }

    @Test
    fun `canonical map emits no revision or condition keys`() {
        val manifest = validManifest()
        val canonical = manifest.toCanonicalMap()

        assertFalse(canonical.containsKey("revision_id"), canonical.keys.toString())
        assertFalse(canonical.containsKey("study_revision_id"), canonical.keys.toString())
        val assignment = canonical["assignment"] as Map<*, *>
        assertFalse(assignment.containsKey("condition_id"), assignment.keys.toString())
        assertFalse(assignment.containsKey("conditionId"), assignment.keys.toString())
    }

    @Test
    fun `expired manifest is rejected`() {
        val manifest =
            BootstrapManifest.parse(
                manifestJson(overrides = mapOf("expires_at" to "2026-01-01T00:10:00Z")),
            )

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.EXPIRED, validation.reason)
    }

    @Test
    fun `near-expiry manifest is rejected conservatively`() {
        val manifest =
            BootstrapManifest.parse(
                manifestJson(overrides = mapOf("expires_at" to "2026-01-01T00:30:20Z")),
            )

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.NEAR_EXPIRY, validation.reason)
    }

    @Test
    fun `digest mismatch is rejected`() {
        val manifest = BootstrapManifest.parse(manifestJson(digestOverride = "0".repeat(64)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.DIGEST_MISMATCH, validation.reason)
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("manifest_version" to "2")))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.SCHEMA_VERSION_UNSUPPORTED, validation.reason)
    }

    @Test
    fun `wrong audience is rejected`() {
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("audience" to "some-other-audience")))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.WRONG_AUDIENCE, validation.reason)
    }

    @Test
    fun `wrong capability audience is rejected`() {
        val capability =
            linkedMapOf<String, Any?>(
                "capability_id" to "capability-1",
                "audience" to "some-other-audience",
                "scope" to listOf("telemetry:write"),
                "issued_at" to "2026-01-01T00:00:00Z",
                "expires_at" to "2026-01-01T01:00:00Z",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("session_capability" to capability)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.WRONG_AUDIENCE, validation.reason)
    }

    @Test
    fun `missing required scope is rejected`() {
        val capability =
            linkedMapOf<String, Any?>(
                "capability_id" to "capability-1",
                "audience" to AUDIENCE,
                "scope" to listOf("session:heartbeat"),
                "issued_at" to "2026-01-01T00:00:00Z",
                "expires_at" to "2026-01-01T01:00:00Z",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("session_capability" to capability)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.SCOPE_MISSING, validation.reason)
    }

    @Test
    fun `bad artifact digest is rejected`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "codex-acp",
                "release_id" to "release-1",
                "version" to "1.2.3",
                "artifact_digest" to "NOT-A-DIGEST",
                "adapter_version" to "codex-v1",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.INVALID_ARTIFACT_DIGEST, validation.reason)
    }

    @Test
    fun `a sha256-prefixed artifact digest is accepted and normalized`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "codex-acp",
                "release_id" to "release-1",
                "version" to "1.2.3",
                "artifact_digest" to "sha256:$VALID_ARTIFACT_DIGEST",
                "adapter_version" to "codex-v1",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertTrue(validation.valid, validation.message)
        assertEquals("sha256:$VALID_ARTIFACT_DIGEST", manifest.agentRelease.artifactDigest)
        assertEquals(VALID_ARTIFACT_DIGEST, manifest.agentRelease.normalizedArtifactDigest)
    }

    @Test
    fun `the server agent_release shape without a version parses and validates`() {
        // The server's BootstrapAgentRelease pins agent_id/release_id/artifact_digest/
        // adapter_version and never emits a version; the plugin must still parse it.
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "synthetic-agent",
                "release_id" to "rel-0002",
                "artifact_digest" to "sha256:$VALID_ARTIFACT_DIGEST",
                "adapter_version" to "0.4.0",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertTrue(validation.valid, validation.message)
        assertEquals("", manifest.agentRelease.version)
        assertEquals(VALID_ARTIFACT_DIGEST, manifest.agentRelease.normalizedArtifactDigest)
    }

    @Test
    fun `a BYOA agent release without a digest parses and validates`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "goose",
                "release_id" to "rel-byoa",
                "version" to "1.0.0",
                "distribution_mode" to "BYOA_EXTERNAL",
                "agent_command" to "goose",
                "agent_command_args" to listOf("acp"),
                "agent_package" to "goose",
                "adapter_version" to "goose-v1",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertTrue(validation.valid, validation.message)
        assertTrue(manifest.agentRelease.isByoa)
        assertEquals(AgentDistributionMode.BYOA_EXTERNAL, manifest.agentRelease.distributionMode)
        assertEquals("goose", manifest.agentRelease.agentCommand)
        assertEquals(listOf("acp"), manifest.agentRelease.agentCommandArgs)
        assertEquals("goose", manifest.agentRelease.agentPackage)
        assertNull(manifest.agentRelease.normalizedArtifactDigest)
    }

    @Test
    fun `an unknown distribution mode defaults to PACKAGED`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "codex-acp",
                "release_id" to "release-1",
                "artifact_digest" to VALID_ARTIFACT_DIGEST,
                "distribution_mode" to "SOMETHING_ELSE",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        assertEquals(AgentDistributionMode.PACKAGED, manifest.agentRelease.distributionMode)
        assertFalse(manifest.agentRelease.isByoa)
        assertTrue(manifest.validate(VALID_NOW, compatibility()).valid)
    }

    @Test
    fun `the server byoa field names parse into the typed model`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "goose",
                "release_id" to "rel-byoa",
                "artifact_digest" to "",
                "distribution_mode" to "BYOA_EXTERNAL",
                "agent_command" to "goose",
                "agent_command_args" to listOf("acp"),
                "agent_package" to "goose",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        assertTrue(manifest.validate(VALID_NOW, compatibility()).valid)
        assertTrue(manifest.agentRelease.isByoa)
        assertEquals("goose", manifest.agentRelease.agentCommand)
        assertEquals(listOf("acp"), manifest.agentRelease.agentCommandArgs)
        assertEquals("goose", manifest.agentRelease.agentPackage)
    }

    @Test
    fun `a server BYOA distribution with no registered release id parses and validates`() {
        // The new distribution contract freezes a BYOA pin without a release id;
        // the bootstrap manifest projects `release_id: ""` and the package/command
        // identity. It must parse and validate, and it must carry no artifact
        // digest (there is no artifact to pin).
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "goose",
                "release_id" to "",
                "artifact_digest" to "",
                "distribution_mode" to "BYOA_EXTERNAL",
                "agent_command" to "goose",
                "agent_command_args" to listOf("acp"),
                "agent_package" to "goose",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertTrue(validation.valid, validation.message)
        assertEquals("", manifest.agentRelease.releaseId)
        assertTrue(manifest.agentRelease.isByoa)
        assertEquals("goose", manifest.agentRelease.agentCommand)
        assertEquals(listOf("acp"), manifest.agentRelease.agentCommandArgs)
        assertEquals("goose", manifest.agentRelease.agentPackage)
        assertNull(manifest.agentRelease.normalizedArtifactDigest)
    }

    @Test
    fun `a PACKAGED distribution without a release id is rejected`() {
        // A PACKAGED distribution is a digest-pinned registry release; a blank
        // release id must fail closed rather than launch an unpinned agent.
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "codex-acp",
                "release_id" to "",
                "artifact_digest" to VALID_ARTIFACT_DIGEST,
                "distribution_mode" to "PACKAGED",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.MALFORMED, validation.reason)
    }

    @Test
    fun `a BYOA release without an identity is rejected`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "mystery",
                "release_id" to "rel-byoa-empty",
                "distribution_mode" to "BYOA_EXTERNAL",
                "artifact_digest" to "",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.MALFORMED, validation.reason)
    }

    @Test
    fun `the canonical map carries the distribution mode and BYOA identity`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "goose",
                "release_id" to "rel-byoa",
                "distribution_mode" to "BYOA_EXTERNAL",
                "agent_command" to "goose",
                "agent_command_args" to listOf("acp"),
                "agent_package" to "goose",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val canonical = manifest.toCanonicalMap()["agent_release"] as Map<*, *>

        assertEquals("BYOA_EXTERNAL", canonical["distribution_mode"])
        assertEquals("goose", canonical["agent_command"])
        assertEquals(listOf("acp"), canonical["agent_command_args"])
        assertEquals("goose", canonical["agent_package"])
    }

    @Test
    fun `normalizeSha256Hex strips the prefix and rejects malformed digests`() {
        assertEquals(VALID_ARTIFACT_DIGEST, normalizeSha256Hex(VALID_ARTIFACT_DIGEST))
        assertEquals(VALID_ARTIFACT_DIGEST, normalizeSha256Hex("sha256:$VALID_ARTIFACT_DIGEST"))
        assertEquals(VALID_ARTIFACT_DIGEST, normalizeSha256Hex(" SHA256:$VALID_ARTIFACT_DIGEST "))
        assertNull(normalizeSha256Hex(null))
        assertNull(normalizeSha256Hex("   "))
        assertNull(normalizeSha256Hex("NOT-A-DIGEST"))
        assertNull(normalizeSha256Hex("sha256:short"))
    }

    @Test
    fun `unsupported adapter version is rejected`() {
        val release =
            linkedMapOf<String, Any?>(
                "agent_id" to "codex-acp",
                "release_id" to "release-1",
                "version" to "1.2.3",
                "artifact_digest" to VALID_ARTIFACT_DIGEST,
                "adapter_version" to "codex-v9",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("agent_release" to release)))

        val validation = manifest.validate(VALID_NOW, compatibility(adapterVersions = setOf("codex-v1")))

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.INCOMPATIBLE_PLUGIN, validation.reason)
    }

    @Test
    fun `every secret-shaped key is rejected`() {
        val secretKeys =
            listOf(
                "user_id",
                "email",
                "api_key",
                "token",
                "secret",
                "password",
                "credential",
                "provider_key",
                "session_token",
                "private_key",
                "access_key",
            )

        secretKeys.forEach { key ->
            val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf(key to "irrelevant")))
            val validation = manifest.validate(VALID_NOW, compatibility())
            assertFalse(validation.valid, "expected key '$key' to be rejected")
            assertEquals(ManifestValidationReason.SECRET_DETECTED, validation.reason, "key '$key'")
        }
    }

    @Test
    fun `deeply nested secret is rejected`() {
        val nested =
            linkedMapOf<String, Any?>(
                "level1" to
                    linkedMapOf<String, Any?>(
                        "level2" to linkedMapOf<String, Any?>("api_key" to "hidden"),
                    ),
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("extra" to nested)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.SECRET_DETECTED, validation.reason)
        assertTrue(validation.field!!.contains("api_key"))
    }

    @Test
    fun `inline secret values are rejected`() {
        val inlineValues =
            listOf(
                "sk-abcdefghijklmnop",
                "ghp_abcdefghijklmnop",
                "AKIAABCDEFGHIJKLMNOP",
                "authorization: Bearer abcdef1234567890",
            )

        inlineValues.forEach { inline ->
            val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("issuer" to inline)))
            val validation = manifest.validate(VALID_NOW, compatibility())
            assertFalse(validation.valid, "expected value '$inline' to be rejected")
            assertEquals(ManifestValidationReason.SECRET_DETECTED, validation.reason, "value '$inline'")
        }
    }

    @Test
    fun `email-shaped value is rejected even under a benign key`() {
        val assignment =
            linkedMapOf<String, Any?>(
                "assignment_id" to "assignment-1",
                "agent_profile_id" to "participant@example.com",
                "strategy" to "RANDOM_EQUAL",
            )
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("assignment" to assignment)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.SECRET_DETECTED, validation.reason)
    }

    @Test
    fun `absolute local paths are rejected`() {
        val paths =
            listOf(
                "/Users/someone/secret-project",
                "C:\\Users\\someone\\secret-project",
                "\\\\server\\share\\project",
            )

        paths.forEach { path ->
            val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("workspace" to path)))
            val validation = manifest.validate(VALID_NOW, compatibility())
            assertFalse(validation.valid, "expected path '$path' to be rejected")
            assertEquals(ManifestValidationReason.ABSOLUTE_PATH_DETECTED, validation.reason, "path '$path'")
        }
    }

    @Test
    fun `nested absolute path is rejected`() {
        val nested = linkedMapOf<String, Any?>("workspace" to "/home/participant/project")
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("extra" to nested)))

        val validation = manifest.validate(VALID_NOW, compatibility())

        assertFalse(validation.valid)
        assertEquals(ManifestValidationReason.ABSOLUTE_PATH_DETECTED, validation.reason)
    }

    @Test
    fun `malformed json and missing fields throw a typed parse error`() {
        assertThrows(ManifestParseException::class.java) { BootstrapManifest.parse("{not json") }
        assertThrows(ManifestParseException::class.java) { BootstrapManifest.parse("[]") }

        val document = manifestDocument().toMutableMap()
        document.remove("study_id")
        assertThrows(ManifestParseException::class.java) {
            BootstrapManifest.parse(me.code4me.research.telemetry.canonicalJson(document))
        }
    }

    @Test
    fun `absence of optional policy members is preserved as null not inferred`() {
        val manifest = BootstrapManifest.parse(manifestJson(overrides = mapOf("compatibility_receipt_ref" to null)))
        assertNull(manifest.compatibilityReceiptRef)
        val validation = manifest.validate(VALID_NOW, compatibility())
        assertTrue(validation.valid, validation.message)
    }
}

// --------------------------------------------------------------------------
// BootstrapTestFixtures.kt
// --------------------------------------------------------------------------

/** Canonical fixtures for bootstrap-manifest contract tests (Issue 05 / Issue 10). */

internal const val AUDIENCE = "research-runtime"
internal const val PLUGIN_VERSION = "0.0.1"
internal const val VALID_ARTIFACT_DIGEST =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** A `now` that sits comfortably inside the default fixture validity window. */
internal val VALID_NOW: java.time.Instant = java.time.Instant.parse("2026-01-01T00:30:00Z")

internal fun compatibility(
    audience: String = AUDIENCE,
    versions: Set<String> = setOf("1"),
    scopes: Set<String> = setOf("telemetry:write"),
    adapterVersions: Set<String> = emptySet(),
): PluginCompatibility =
    PluginCompatibility(
        pluginVersion = PLUGIN_VERSION,
        expectedAudience = audience,
        supportedManifestVersions = versions,
        requiredScopes = scopes,
        supportedAdapterVersions = adapterVersions,
    )

/** Monotonic source of unique default research-session ids for fixture documents. */
private val researchSessionIdSequence = java.util.concurrent.atomic.AtomicLong()

/** A fresh, unique research-session id that is distinct for every fixture document. */
internal fun uniqueResearchSessionId(): String = "session-${researchSessionIdSequence.incrementAndGet()}"

/**
 * Build a manifest document with a self-consistent digest.
 *
 * `overrides` are applied *before* the digest is computed, so an overridden
 * field is still covered by the digest. Use `digestOverride` to make the digest
 * intentionally wrong.
 */
internal fun manifestDocument(
    overrides: Map<String, Any?> = emptyMap(),
    digestOverride: String? = null,
    researchSessionId: String = "session-1",
): Map<String, Any?> {
    val document =
        linkedMapOf<String, Any?>(
            "manifest_version" to "1",
            "issuer" to "code4me-research",
            "audience" to AUDIENCE,
            "issued_at" to "2026-01-01T00:00:00Z",
            "expires_at" to "2026-01-01T01:00:00Z",
            "study_id" to "study-1",
            "enrollment_id" to "enrollment-1",
            "research_session" to
                linkedMapOf<String, Any?>(
                    "research_session_id" to researchSessionId,
                    "opened_at" to "2026-01-01T00:00:00Z",
                ),
            "assignment" to
                linkedMapOf<String, Any?>(
                    "assignment_id" to "assignment-1",
                    "agent_profile_id" to "profile-1",
                    "strategy" to "RANDOM_EQUAL",
                    "randomization_epoch" to 0,
                    "profile_digest" to "profile-digest-1",
                ),
            "agent_release" to
                linkedMapOf<String, Any?>(
                    "agent_id" to "codex-acp",
                    "release_id" to "release-1",
                    "version" to "1.2.3",
                    "artifact_digest" to VALID_ARTIFACT_DIGEST,
                    "adapter_version" to "codex-v1",
                ),
            "policies" to
                linkedMapOf<String, Any?>(
                    "telemetry" to
                        linkedMapOf<String, Any?>(
                            "allowed_field_classes" to listOf("SYSTEM", "BEHAVIORAL", "CODE_METADATA"),
                            "content_capture" to false,
                        ),
                    "privacy" to linkedMapOf<String, Any?>("retention_action" to "delete", "retention_days" to 30),
                    "session" to
                        linkedMapOf<String, Any?>(
                            "idle_timeout_seconds" to 600,
                            "resume_grace_seconds" to 120,
                            "heartbeat_seconds" to 30,
                        ),
                    "consent_policy_digest" to "consent-policy-digest",
                ),
            "compatibility_receipt_ref" to "receipt-1",
            "session_capability" to
                linkedMapOf<String, Any?>(
                    "capability_id" to "capability-1",
                    "audience" to AUDIENCE,
                    "scope" to listOf("telemetry:write", "session:heartbeat", "session:close"),
                    "issued_at" to "2026-01-01T00:00:00Z",
                    "expires_at" to "2026-01-01T01:00:00Z",
                ),
        )
    overrides.forEach { (key, value) -> document[key] = value }
    val digest = digestOverride ?: BootstrapManifest.computeManifestDigest(document)
    document["manifest_digest"] = digest
    return document
}

/**
 * Canonical JSON for a fixture manifest.
 *
 * `researchSessionId` defaults to a fresh, unique id per call so that two
 * independently generated manifests never accidentally share a server-issued
 * session id. Pass an explicit value when a test needs stable ids across calls.
 */
internal fun manifestJson(
    overrides: Map<String, Any?> = emptyMap(),
    digestOverride: String? = null,
    researchSessionId: String = uniqueResearchSessionId(),
): String = canonicalJson(manifestDocument(overrides, digestOverride, researchSessionId))

/** A valid parsed manifest. */
internal fun validManifest(): BootstrapManifest = BootstrapManifest.parse(manifestJson(researchSessionId = "session-1"))

// --------------------------------------------------------------------------
// HttpBootstrapTransportTest.kt
// --------------------------------------------------------------------------

/**
 * Contract tests for [HttpBootstrapTransport] against a local
 * [com.sun.net.httpserver.HttpServer] (no new test dependency).
 */
class HttpBootstrapTransportTest {
    private val json = Json { ignoreUnknownKeys = true }

    private data class Response(
        val status: Int,
        val body: String,
        val contentType: String = "application/json",
    )

    private class TestBackend(private val responder: (String) -> Response) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        @Volatile var lastMethod: String? = null

        @Volatile var lastPath: String? = null

        @Volatile var lastBody: String? = null

        @Volatile var lastCookie: String? = null

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.executor = null
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            lastMethod = exchange.requestMethod
            lastPath = exchange.requestURI.path
            lastBody = exchange.requestBody.readBytes().decodeToString()
            lastCookie = exchange.requestHeaders.getFirst("Cookie")
            val response = responder(lastBody.orEmpty())
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", response.contentType)
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }

    private var backend: TestBackend? = null

    @AfterEach
    fun tearDown() {
        backend?.stop()
        backend = null
    }

    private fun start(responder: (String) -> Response): TestBackend = TestBackend(responder).also { backend = it }

    private fun transport(
        server: TestBackend,
        client: okhttp3.Call.Factory = OkHttpClient(),
    ): HttpBootstrapTransport = HttpBootstrapTransport(server.baseUrl, httpClient = client, environment = { environment }, json = json)

    @Test
    fun `success returns a parseable manifest and sends the enrollment id and environment`() {
        val server = start { Response(201, """{"manifest":${manifestJson()}}""") }

        val result = transport(server).fetch("enrollment-1", "ctx-test")

        val success = result as BootstrapTransportResult.Success
        val parsed = BootstrapManifest.parse(success.manifestJson)
        assertEquals("study-1", parsed.studyId)
        assertEquals("enrollment-1", parsed.enrollmentId)

        assertEquals("POST", server.lastMethod)
        assertEquals("/api/research/bootstrap/research-sessions", server.lastPath)
        val body = json.parseToJsonElement(server.lastBody.orEmpty()).jsonObject
        assertEquals("enrollment-1", body.getValue("enrollment_id").jsonPrimitive.content)
        // The opaque execution context travels with every bootstrap request so
        // simultaneous windows get distinct sessions.
        assertEquals("ctx-test", body.getValue("context_id").jsonPrimitive.content)
        val reported = body.getValue("environment").jsonObject
        assertEquals("macos", reported.getValue("os").jsonPrimitive.content)
        assertEquals("aarch64", reported.getValue("arch").jsonPrimitive.content)
        assertEquals("IC-262.1234.5", reported.getValue("ide_build").jsonPrimitive.content)
        assertEquals("1.2.3", reported.getValue("plugin_version").jsonPrimitive.content)
        assertEquals("IntelliJ IDEA", reported.getValue("host_kind").jsonPrimitive.content)
    }

    @Test
    fun `trailing slashes on the base url are normalised`() {
        val server = start { Response(201, """{"manifest":${manifestJson()}}""") }

        val trailing =
            HttpBootstrapTransport("${server.baseUrl}//", httpClient = OkHttpClient(), environment = { environment }, json = json)
        val result = trailing.fetch("enrollment-1", "ctx-test")

        assertTrue(result is BootstrapTransportResult.Success)
        assertEquals("/api/research/bootstrap/research-sessions", server.lastPath)
    }

    @Test
    fun `conflict is reported as revoked with the server reason`() {
        val server = start { Response(409, """{"detail":{"reason":"assignment is not eligible"}}""") }

        val result = transport(server).fetch("enrollment-1", "ctx-test")

        val revoked = result as BootstrapTransportResult.Revoked
        assertTrue(revoked.reason.contains("assignment is not eligible"), revoked.reason)
    }

    @Test
    fun `a typed conflict carries its rejection code`() {
        val server =
            start {
                Response(
                    409,
                    """{"detail":{"code":"COMPATIBILITY_MISSING","message":"a compatibility receipt is required","field":"compatibility"}}""",
                )
            }

        val revoked = transport(server).fetch("enrollment-1", "ctx-test") as BootstrapTransportResult.Revoked

        assertEquals(BootstrapRejection.COMPATIBILITY_MISSING, revoked.rejection)
        assertTrue(revoked.reason.contains("compatibility"), revoked.reason)
    }

    @Test
    fun `an enrollment-not-active conflict carries its rejection code`() {
        val server =
            start {
                Response(
                    409,
                    """{"detail":{"code":"ENROLLMENT_NOT_ACTIVE","message":"enrollment is COMPLETED","field":"status"}}""",
                )
            }

        val revoked = transport(server).fetch("enrollment-1", "ctx-test") as BootstrapTransportResult.Revoked

        assertEquals(BootstrapRejection.ENROLLMENT_NOT_ACTIVE, revoked.rejection)
    }

    @Test
    fun `a missing enrollment is a typed not-found rejection`() {
        val server = start { Response(404, """{"detail":"Enrollment not found"}""") }

        val revoked = transport(server).fetch("enrollment-1", "ctx-test") as BootstrapTransportResult.Revoked

        assertEquals(BootstrapRejection.ENROLLMENT_NOT_FOUND, revoked.rejection)
    }

    @Test
    fun `unauthenticated is a typed non-retryable rejection`() {
        val server = start { Response(401, """{"detail":"Not authenticated"}""") }

        val failure = transport(server).fetch("enrollment-1", "ctx-test") as BootstrapTransportResult.Failure

        assertFalse(failure.retryable)
        assertEquals(BootstrapRejection.NOT_AUTHENTICATED, failure.rejection)
    }

    @Test
    fun `forbidden is a typed non-retryable rejection`() {
        val server = start { Response(403, """{"detail":"forbidden"}""") }

        val failure = transport(server).fetch("enrollment-1", "ctx-test") as BootstrapTransportResult.Failure

        assertFalse(failure.retryable)
        assertEquals(BootstrapRejection.NOT_PERMITTED, failure.rejection)
    }

    @Test
    fun `server error is a retryable failure`() {
        val server = start { Response(500, """{"detail":"boom"}""") }

        val result = transport(server).fetch("enrollment-1", "ctx-test")

        val failure = result as BootstrapTransportResult.Failure
        assertTrue(failure.retryable)
    }

    @Test
    fun `unauthenticated is a non-retryable failure`() {
        val server = start { Response(401, """{"detail":"Not authenticated"}""") }

        val result = transport(server).fetch("enrollment-1", "ctx-test")

        val failure = result as BootstrapTransportResult.Failure
        assertFalse(failure.retryable)
    }

    @Test
    fun `malformed success is a non-retryable failure`() {
        val server = start { Response(200, """{"not_a_manifest":true}""") }

        val result = transport(server).fetch("enrollment-1", "ctx-test")

        val failure = result as BootstrapTransportResult.Failure
        assertFalse(failure.retryable)
    }

    @Test
    fun `io failure is a retryable failure`() {
        val server = start { Response(200, "{}") }
        val baseUrl = server.baseUrl
        server.stop()
        backend = null

        val result =
            HttpBootstrapTransport(
                baseUrl,
                httpClient = OkHttpClient(),
                environment = { environment },
                json = json,
            ).fetch("enrollment-1", "ctx-test")

        val failure = result as BootstrapTransportResult.Failure
        assertTrue(failure.retryable)
    }

    @Test
    fun `an injected client can attach the auth cookie`() {
        val server = start { Response(201, """{"manifest":${manifestJson()}}""") }
        val cookieClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder().header("Cookie", "auth_token=test-token").build(),
                    )
                }.build()

        transport(server, cookieClient).fetch("enrollment-1", "ctx-test")

        assertEquals("auth_token=test-token", server.lastCookie)
    }

    private companion object {
        val environment =
            BootstrapEnvironment(
                os = "macos",
                arch = "aarch64",
                ideBuild = "IC-262.1234.5",
                pluginVersion = "1.2.3",
                hostKind = "IntelliJ IDEA",
            )
    }
}

// --------------------------------------------------------------------------
// BootstrapRejectionTest.kt
// --------------------------------------------------------------------------

class BootstrapRejectionTest {
    @Test
    fun `every typed rejection has its own participant-safe message`() {
        val messages = BootstrapRejection.entries.map { it.participantMessage }

        // UNKNOWN is the only rejection without a typed message (generic fallback).
        assertEquals(1, messages.count { it.isBlank() })
        assertEquals(BootstrapRejection.UNKNOWN.participantMessage, "")
        listOf(
            BootstrapRejection.ENROLLMENT_NOT_FOUND,
            BootstrapRejection.NOT_AUTHENTICATED,
            BootstrapRejection.NOT_PERMITTED,
            BootstrapRejection.COMPATIBILITY_MISSING,
            BootstrapRejection.ENROLLMENT_NOT_ACTIVE,
            BootstrapRejection.REVOKED,
            BootstrapRejection.INELIGIBLE,
            BootstrapRejection.STUDY_STOPPED,
            BootstrapRejection.STUDY_MISMATCH,
            BootstrapRejection.KILL_SWITCH_ENGAGED,
        ).forEach { rejection ->
            assertTrue(rejection.participantMessage.isNotBlank(), rejection.name)
            assertTrue(rejection.participantMessage.contains(rejection.name), rejection.name)
        }
        // Distinct reasons never collapse to the same sentence.
        assertEquals(messages.toSet().size, messages.size)
    }

    @Test
    fun `fromCode maps known codes case-insensitively and refuses anything else`() {
        assertEquals(BootstrapRejection.REVOKED, BootstrapRejection.fromCode("revoked"))
        assertEquals(BootstrapRejection.REVOKED, BootstrapRejection.fromCode("  REVOKED  "))
        assertEquals(BootstrapRejection.COMPATIBILITY_MISSING, BootstrapRejection.fromCode("compatibility_missing"))
        assertEquals(BootstrapRejection.ENROLLMENT_NOT_ACTIVE, BootstrapRejection.fromCode("ENROLLMENT_NOT_ACTIVE"))
        assertEquals(BootstrapRejection.INELIGIBLE, BootstrapRejection.fromCode("INELIGIBLE"))
        assertEquals(BootstrapRejection.UNKNOWN, BootstrapRejection.fromCode("not-a-real-code"))
        assertEquals(BootstrapRejection.UNKNOWN, BootstrapRejection.fromCode(null))
        assertEquals(BootstrapRejection.UNKNOWN, BootstrapRejection.fromCode(""))
    }
}

// --------------------------------------------------------------------------
// ResearchJoinCodeResolverTest.kt
// --------------------------------------------------------------------------

/**
 * Contract tests for [ResearchJoinCodeResolver] against a local
 * [com.sun.net.httpserver.HttpServer] (no new test dependency).
 *
 * Membership is discovered from `GET /api/research/participants/me`; joining
 * itself happens through web consent, so the resolver never redeems a code.
 */
class ResearchJoinCodeResolverTest {
    private data class Reply(
        val status: Int,
        val body: String,
    )

    private class JoinBackend(private val responder: (String) -> Reply) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestedPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val requestedBodies: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requestedPaths.add(path)
                requestedBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                val reply = responder(path)
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.executor = null
            server.start()
        }

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }

    private fun enrollmentsBody(vararg entries: String) = """{"enrollments":[${entries.joinToString(",")}]}"""

    private fun enrollment(
        status: String,
        enrollmentId: String = "enr-1",
        studyId: String = "study-1",
    ) = """{"enrollment_id":"$enrollmentId","study_id":"$studyId","status":"$status"}"""

    private fun withBackend(responder: (String) -> Reply, block: (JoinBackend) -> Unit) {
        val backend = JoinBackend(responder)
        try {
            block(backend)
        } finally {
            backend.stop()
        }
    }

    private fun resolver(backend: JoinBackend) = ResearchJoinCodeResolver(backend.baseUrl, httpClient = OkHttpClient())

    @Test
    fun `an active enrollment discovers its enrollment id`() {
        withBackend(
            { _ -> Reply(200, enrollmentsBody(enrollment("ACTIVE"))) },
        ) { backend ->
            val result = resolver(backend).discover()

            assertEquals(EnrollmentDiscovery.Active("enr-1"), result)
            assertTrue(backend.requestedPaths.any { it.endsWith("/api/research/participants/me") })
        }
    }

    @Test
    fun `no enrollment reports none so the caller can direct web consent`() {
        withBackend(
            { _ -> Reply(200, enrollmentsBody()) },
        ) { backend ->
            assertEquals(EnrollmentDiscovery.None, resolver(backend).discover())
        }
    }

    @Test
    fun `an unknown enrollment status reports none`() {
        withBackend(
            { _ -> Reply(200, enrollmentsBody(enrollment("PENDING_CONSENT"))) },
        ) { backend ->
            assertEquals(EnrollmentDiscovery.None, resolver(backend).discover())
        }
    }

    @Test
    fun `an active enrollment in another study still discovers active`() {
        withBackend(
            { _ ->
                Reply(200, enrollmentsBody(enrollment("ACTIVE", enrollmentId = "other", studyId = "study-2")))
            },
        ) { backend ->
            assertEquals(
                EnrollmentDiscovery.Active("other"),
                resolver(backend).discover(),
            )
        }
    }

    @Test
    fun `a server without the own-enrollment endpoint reports none`() {
        withBackend(
            { _ -> Reply(404, """{"detail":"Not Found"}""") },
        ) { backend ->
            assertEquals(EnrollmentDiscovery.None, resolver(backend).discover())
        }
        withBackend(
            { _ -> Reply(405, """{"detail":"Method Not Allowed"}""") },
        ) { backend ->
            assertEquals(EnrollmentDiscovery.None, resolver(backend).discover())
        }
    }

    @Test
    fun `a missing membership document reports none without failing`() {
        withBackend({ _ -> Reply(404, """{"detail":"Not Found"}""") }) { backend ->
            val result = resolver(backend).discover()

            assertEquals(EnrollmentDiscovery.None, result)
        }
    }

    @Test
    fun `an unauthenticated discover is unavailable`() {
        withBackend({ _ -> Reply(401, """{"detail":"Not authenticated"}""") }) { backend ->
            val result = resolver(backend).discover()

            assertTrue(result is EnrollmentDiscovery.Unavailable)
        }
    }

    @Test
    fun `a server error is a retryable unavailable result`() {
        withBackend({ _ -> Reply(503, """{"detail":"temporarily unavailable"}""") }) { backend ->
            assertTrue(resolver(backend).discover() is EnrollmentDiscovery.Unavailable)
        }
    }

    @Test
    fun `a malformed document reports none`() {
        withBackend({ _ -> Reply(200, "{not json") }) { backend ->
            assertEquals(EnrollmentDiscovery.None, resolver(backend).discover())
        }
    }

    @Test
    fun `a revoked enrollment is terminal`() {
        withBackend({ _ -> Reply(200, enrollmentsBody(enrollment("REVOKED"))) }) { backend ->
            assertEquals(EnrollmentDiscovery.Terminal("REVOKED"), resolver(backend).discover())
        }
    }

    @Test
    fun `a stopped-study enrollment is terminal`() {
        withBackend({ _ -> Reply(200, enrollmentsBody(enrollment("STUDY_STOPPED"))) }) { backend ->
            assertEquals(EnrollmentDiscovery.Terminal("STUDY_STOPPED"), resolver(backend).discover())
        }
    }

    @Test
    fun `a completed enrollment is terminal`() {
        withBackend({ _ -> Reply(200, enrollmentsBody(enrollment("COMPLETED"))) }) { backend ->
            assertEquals(EnrollmentDiscovery.Terminal("COMPLETED"), resolver(backend).discover())
        }
    }

    @Test
    fun `an active enrollment wins over terminal entries`() {
        withBackend(
            { _ -> Reply(200, enrollmentsBody(enrollment("REVOKED", enrollmentId = "old"), enrollment("ACTIVE"))) },
        ) { backend ->
            assertEquals(EnrollmentDiscovery.Active("enr-1"), resolver(backend).discover())
        }
    }

    @Test
    fun `terminal matching is case-insensitive`() {
        withBackend({ _ -> Reply(200, enrollmentsBody(enrollment("revoked"))) }) { backend ->
            assertEquals(EnrollmentDiscovery.Terminal("REVOKED"), resolver(backend).discover())
        }
    }
}

// --------------------------------------------------------------------------
// ResearchEnrollmentClassificationTest.kt
// --------------------------------------------------------------------------

/** Unit tests for the membership classifier behind [ResearchJoinCodeResolver]. */
class ResearchEnrollmentClassificationTest {
    @Test
    fun `active wins over terminal entries`() {
        val discovery =
            classifyEnrollmentDiscovery(
                listOf(
                    ResolvedEnrollment("enr-old", "study-1", "REVOKED"),
                    ResolvedEnrollment("enr-new", "study-1", "ACTIVE"),
                ),
            )

        assertEquals(EnrollmentDiscovery.Active("enr-new"), discovery)
    }

    @Test
    fun `each server terminal status classifies terminal`() {
        listOf("REVOKED", "STUDY_STOPPED", "COMPLETED").forEach { status ->
            val discovery = classifyEnrollmentDiscovery(listOf(ResolvedEnrollment("enr-1", "study-1", status)))

            assertEquals(EnrollmentDiscovery.Terminal(status), discovery)
        }
    }

    @Test
    fun `unknown statuses report none`() {
        assertEquals(EnrollmentDiscovery.None, classifyEnrollmentDiscovery(emptyList()))
        assertEquals(
            EnrollmentDiscovery.None,
            classifyEnrollmentDiscovery(listOf(ResolvedEnrollment("enr-1", "study-1", "PENDING_CONSENT"))),
        )
    }

    @Test
    fun `malformed documents parse to no enrollments`() {
        assertTrue(parseEnrollmentEntries("{not json").isEmpty())
        assertTrue(parseEnrollmentEntries("""{"enrollments":"nope"}""").isEmpty())
    }
}
