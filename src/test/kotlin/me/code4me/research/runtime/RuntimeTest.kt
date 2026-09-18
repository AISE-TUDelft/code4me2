package me.code4me.research.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import me.code4me.research.runtime.RuntimeArtifactResolver.verifyFile
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.sha256Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

private val acceptingSignatureVerifier = SignatureVerifier { _, _ -> SignatureVerification.Valid }

// --------------------------------------------------------------------------
// ConformanceRunnerTest.kt
// --------------------------------------------------------------------------

class ConformanceRunnerTest {
    private class RecordingExecutor(
        private val outcomeFor: (String) -> ConformanceCaseOutcome,
    ) : ConformanceCaseExecutor {
        val invocations = mutableListOf<String>()

        override fun run(
            case: ConformanceCaseV1,
            fixtureId: String,
        ): ConformanceCaseOutcome {
            invocations += case.caseId
            return outcomeFor(case.caseId)
        }
    }

    private fun passing() = ConformanceCaseOutcome(observations = listOf("tool.completed"), cleanup = CleanupRecord.cleaned())

    private fun run(
        cases: List<ConformanceCaseV1>,
        capabilities: CapabilityLookup = CapabilityLookup.empty(),
        fixtureDigests: Map<String, String> = mapOf("fixture-read" to FIXTURE_SHA),
        executor: ConformanceCaseExecutor = ConformanceCaseExecutor { _, _ -> passing() },
    ): ConformanceReceiptV1 =
        ConformanceRunner.run(
            runInput(cases, capabilities, fixtureDigests),
            executor,
            clock = { 123_456L },
            receiptIdFactory = { "receipt-fixed" },
        )

    @Test
    fun `missing prerequisite yields UNSUPPORTED and is never a pass`() {
        val case =
            conformanceCase(
                "native-diff",
                prerequisite = CapabilityPredicate("permission.native_diff", CapabilityChannel.OBSERVED),
            )
        val executor = RecordingExecutor { passing() }

        val receipt =
            run(
                cases = listOf(case),
                capabilities = lookup(observedCapability("permission.native_diff", CapabilityState.UNAVAILABLE)),
                executor = executor,
            )

        val result = receipt.case("native-diff")!!
        assertEquals(ConformanceStatus.UNSUPPORTED, result.status)
        assertTrue(executor.invocations.isEmpty())
        assertFalse(result.cleanup.attempted)
        assertTrue(result.observations.any { it.contains("not established") })
        assertFalse(receipt.advertisedBehaviorSupported())
    }

    @Test
    fun `unknown capability yields UNKNOWN`() {
        val case =
            conformanceCase(
                "edit",
                prerequisite = CapabilityPredicate("tool.edit", CapabilityChannel.OBSERVED),
            )

        val unknownState =
            run(
                cases = listOf(case),
                capabilities = lookup(observedCapability("tool.edit", CapabilityState.UNKNOWN)),
            ).case("edit")!!
        assertEquals(ConformanceStatus.UNKNOWN, unknownState.status)

        val noEvidence = run(cases = listOf(case), capabilities = CapabilityLookup.empty()).case("edit")!!
        assertEquals(ConformanceStatus.UNKNOWN, noEvidence.status)
    }

    @Test
    fun `failing case yields FAIL with observations and evidence`() {
        val case = conformanceCase("edit")
        val executor =
            RecordingExecutor {
                ConformanceCaseOutcome(
                    observations = listOf("permission.requested"),
                    failureReason = "agent refused edit",
                    evidenceDigests = listOf(EVIDENCE_SHA),
                    cleanup = CleanupRecord.failed("temp file left behind"),
                )
            }

        val result = run(cases = listOf(case), executor = executor).case("edit")!!

        assertEquals(ConformanceStatus.FAIL, result.status)
        assertTrue(result.observations.any { it.contains("agent refused edit") })
        assertEquals(listOf(EVIDENCE_SHA), result.evidenceDigests)
        assertEquals(CleanupRecord.failed("temp file left behind"), result.cleanup)
    }

    @Test
    fun `cleanup is recorded for every case even when the case never runs or throws`() {
        val passCase = conformanceCase("pass")
        val failCase = conformanceCase("fail")
        val unsupportedCase =
            conformanceCase(
                "unsupported",
                prerequisite = CapabilityPredicate("permission.native_diff", CapabilityChannel.OBSERVED),
            )
        val unknownCase =
            conformanceCase("unknown", prerequisite = CapabilityPredicate("tool.edit", CapabilityChannel.OBSERVED))
        val blockedCase = conformanceCase("blocked", setupFixtureId = "fixture-missing")
        val throwingCase = conformanceCase("throwing")

        val executor =
            RecordingExecutor { caseId ->
                when (caseId) {
                    "pass" -> passing()
                    "fail" ->
                        ConformanceCaseOutcome(
                            observations = listOf("permission.decided"),
                            failureReason = "edit was rejected",
                            cleanup = CleanupRecord.cleaned(),
                        )
                    "throwing" -> throw IllegalStateException("executor crash")
                    else -> passing()
                }
            }

        val receipt =
            run(
                cases = listOf(passCase, failCase, unsupportedCase, unknownCase, blockedCase, throwingCase),
                capabilities =
                    lookup(
                        observedCapability("permission.native_diff", CapabilityState.UNAVAILABLE),
                        observedCapability("tool.edit", CapabilityState.UNKNOWN),
                    ),
                executor = executor,
            )

        assertEquals(6, receipt.cases.size)
        assertTrue(receipt.cases.all { it.cleanup.let { cleanup -> cleanup.attempted || !cleanup.succeeded || cleanup.detail != null } })
        assertEquals(CleanupRecord.cleaned(), receipt.case("pass")!!.cleanup)
        assertEquals(ConformanceStatus.FAIL, receipt.case("throwing")!!.status)
        assertTrue(receipt.case("throwing")!!.cleanup.attempted)
        assertFalse(receipt.case("throwing")!!.cleanup.succeeded)
        assertEquals(ConformanceStatus.UNSUPPORTED, receipt.case("unsupported")!!.status)
        assertEquals(ConformanceStatus.UNKNOWN, receipt.case("unknown")!!.status)
        assertEquals(ConformanceStatus.BLOCKED, receipt.case("blocked")!!.status)
        assertFalse(receipt.case("blocked")!!.cleanup.attempted)
    }

    @Test
    fun `receipt binds exact artifact adapter host plugin acp and fixture digests`() {
        val receipt = run(cases = listOf(conformanceCase("edit")))

        assertEquals("receipt-fixed", receipt.receiptId)
        assertEquals(ARTIFACT_SHA, receipt.artifactDigest)
        assertEquals("codex-acp", receipt.adapterId)
        assertEquals("codex-v1", receipt.adapterVersion)
        assertEquals("2026.2.2", receipt.hostVersion)
        assertEquals("1.2.0", receipt.pluginVersion)
        assertEquals("1", receipt.acpProtocolVersion)
        assertEquals(mapOf("fixture-read" to FIXTURE_SHA), receipt.fixtureDigests)
        assertEquals("macos-arm64", receipt.hostPlatform)
        assertEquals(123_456L, receipt.generatedAtEpochMs)
        assertEquals(ConformanceStatus.PASS, receipt.overallStatus)
    }

    @Test
    fun `multiple fixtures bind independently`() {
        val first = conformanceCase("first", setupFixtureId = "fixture-read")
        val second = conformanceCase("second", setupFixtureId = "fixture-write")

        val receipt =
            run(
                cases = listOf(first, second),
                fixtureDigests = mapOf("fixture-read" to FIXTURE_SHA, "fixture-write" to OTHER_FIXTURE_SHA),
            )

        assertEquals(mapOf("fixture-read" to FIXTURE_SHA, "fixture-write" to OTHER_FIXTURE_SHA), receipt.fixtureDigests)
        assertEquals(ConformanceStatus.PASS, receipt.overallStatus)
    }

    @Test
    fun `advertised behavior is supported only when all required cases pass`() {
        val allPass = run(cases = listOf(conformanceCase("a"), conformanceCase("b")))
        assertTrue(allPass.advertisedBehaviorSupported())
        assertEquals(ConformanceStatus.PASS, allPass.overallStatus)

        val unsupported =
            run(
                cases =
                    listOf(
                        conformanceCase("a"),
                        conformanceCase(
                            "b",
                            prerequisite = CapabilityPredicate("permission.native_diff", CapabilityChannel.OBSERVED),
                        ),
                    ),
                capabilities = lookup(observedCapability("permission.native_diff", CapabilityState.UNAVAILABLE)),
            )
        assertFalse(unsupported.advertisedBehaviorSupported())
        assertEquals(ConformanceStatus.UNSUPPORTED, unsupported.overallStatus)

        // A non-required failure does not block the required claim, but the
        // overall receipt is still a FAIL.
        val optionalFail =
            run(
                cases = listOf(conformanceCase("a"), conformanceCase("optional", required = false)),
                executor =
                    RecordingExecutor { caseId ->
                        if (caseId == "optional") {
                            ConformanceCaseOutcome(failureReason = "optional feature failed")
                        } else {
                            passing()
                        }
                    },
            )
        assertTrue(optionalFail.advertisedBehaviorSupported())
        assertEquals(ConformanceStatus.FAIL, optionalFail.overallStatus)

        // Explicit required ids must exist and pass.
        assertFalse(optionalFail.advertisedBehaviorSupported(listOf("missing")))
        assertTrue(optionalFail.advertisedBehaviorSupported(listOf("a")))
    }

    @Test
    fun `a performance bound failure is a FAIL`() {
        val case = conformanceCase("latency")
        val executor =
            RecordingExecutor {
                ConformanceCaseOutcome(
                    observations = listOf("edit.applied"),
                    performance = listOf(PerformanceBounds("edit_latency", limitMs = 100, observedMs = 150)),
                )
            }

        val result = run(cases = listOf(case), executor = executor).case("latency")!!

        assertEquals(ConformanceStatus.FAIL, result.status)
        assertTrue(result.observations.any { it.contains("performance bound") })
        assertFalse(result.performanceWithinBounds)
    }

    @Test
    fun `capability predicate evaluation distinguishes missing and unknown`() {
        assertEquals(
            PredicateOutcome.ESTABLISHED,
            CapabilityPredicate("x", CapabilityChannel.OBSERVED)
                .evaluate(lookup(observedCapability("x", CapabilityState.OBSERVED, "1"))),
        )
        assertEquals(
            PredicateOutcome.NOT_ESTABLISHED,
            CapabilityPredicate("x", CapabilityChannel.OBSERVED)
                .evaluate(lookup(observedCapability("x", CapabilityState.UNAVAILABLE))),
        )
        assertEquals(
            PredicateOutcome.UNKNOWN,
            CapabilityPredicate("x", CapabilityChannel.OBSERVED)
                .evaluate(lookup(observedCapability("x", CapabilityState.UNKNOWN))),
        )
        assertEquals(PredicateOutcome.UNKNOWN, CapabilityPredicate("x").evaluate(CapabilityLookup.empty()))
        assertEquals(
            PredicateOutcome.UNKNOWN,
            CapabilityPredicate("x", CapabilityChannel.OBSERVED)
                .evaluate(lookup(declaredCapability("x", CapabilityState.DECLARED))),
        )
        assertEquals(
            PredicateOutcome.ESTABLISHED,
            CapabilityPredicate("x", CapabilityChannel.DECLARED)
                .evaluate(lookup(declaredCapability("x", CapabilityState.DECLARED))),
        )
        assertEquals(
            PredicateOutcome.NOT_ESTABLISHED,
            CapabilityPredicate("x", CapabilityChannel.OBSERVED, requiredValue = "2")
                .evaluate(lookup(observedCapability("x", CapabilityState.OBSERVED, "1"))),
        )
    }

    @Test
    fun `overall status precedence is fail then blocked then unknown then unsupported`() {
        assertEquals(ConformanceStatus.PASS, ConformanceStatus.overall(listOf(ConformanceStatus.PASS)))
        assertEquals(
            ConformanceStatus.FAIL,
            ConformanceStatus.overall(listOf(ConformanceStatus.PASS, ConformanceStatus.FAIL, ConformanceStatus.UNSUPPORTED)),
        )
        assertEquals(
            ConformanceStatus.BLOCKED,
            ConformanceStatus.overall(listOf(ConformanceStatus.PASS, ConformanceStatus.BLOCKED)),
        )
        assertEquals(
            ConformanceStatus.UNKNOWN,
            ConformanceStatus.overall(listOf(ConformanceStatus.PASS, ConformanceStatus.UNKNOWN)),
        )
        assertEquals(
            ConformanceStatus.UNSUPPORTED,
            ConformanceStatus.overall(listOf(ConformanceStatus.PASS, ConformanceStatus.UNSUPPORTED)),
        )
        assertEquals(ConformanceStatus.UNKNOWN, ConformanceStatus.overall(emptyList()))
    }

    @Test
    fun `receipt canonical json is deterministic`() {
        val receipt = run(cases = listOf(conformanceCase("edit")))

        assertEquals(receipt.toCanonicalJson(), receipt.toCanonicalJson())
        assertTrue(receipt.toCanonicalJson().contains("\"status\":\"PASS\""))
        assertNull(receipt.case("missing"))
    }
}

// --------------------------------------------------------------------------
// ConformanceTestFixtures.kt
// --------------------------------------------------------------------------

/** Deterministic 64-hex digests for conformance tests. */

internal val ARTIFACT_SHA: String = "1".repeat(64)
internal val FIXTURE_SHA: String = "2".repeat(64)
internal val OTHER_FIXTURE_SHA: String = "5".repeat(64)
internal val EVIDENCE_SHA: String = "3".repeat(64)
internal val OTHER_EVIDENCE_SHA: String = "4".repeat(64)

internal fun observedCapability(
    capability: String,
    state: CapabilityState,
    value: String? = null,
): CapabilitySnapshotEntry = CapabilitySnapshotEntry(capability, observed = CapabilityFact(state, value))

internal fun declaredCapability(
    capability: String,
    state: CapabilityState,
    value: String? = null,
): CapabilitySnapshotEntry = CapabilitySnapshotEntry(capability, declared = CapabilityFact(state, value))

internal fun lookup(vararg entries: CapabilitySnapshotEntry): CapabilityLookup = CapabilityLookup.of(entries.toList())

internal fun conformanceCase(
    caseId: String,
    prerequisite: CapabilityPredicate? = null,
    setupFixtureId: String = "fixture-read",
    required: Boolean = true,
    actionSteps: List<String> = listOf("open file", "request edit"),
    expectedObservations: List<String> = listOf("tool.completed"),
    cleanupAssertion: String = "workspace unchanged",
): ConformanceCaseV1 =
    ConformanceCaseV1(
        caseId = caseId,
        setupFixtureId = setupFixtureId,
        prerequisite = prerequisite,
        actionSteps = actionSteps,
        expectedObservations = expectedObservations,
        cleanupAssertion = cleanupAssertion,
        required = required,
    )

internal fun runInput(
    cases: List<ConformanceCaseV1>,
    capabilities: CapabilityLookup = CapabilityLookup.empty(),
    fixtureDigests: Map<String, String> = mapOf("fixture-read" to FIXTURE_SHA),
): ConformanceRunInput =
    ConformanceRunInput(
        cases = cases,
        capabilities = capabilities,
        fixtureDigests = fixtureDigests,
        artifactDigest = ARTIFACT_SHA,
        adapterId = "codex-acp",
        adapterVersion = "codex-v1",
        hostVersion = "2026.2.2",
        pluginVersion = "1.2.0",
        acpProtocolVersion = "1",
        hostPlatform = "macos-arm64",
    )

// --------------------------------------------------------------------------
// PackageVerifierTest.kt
// --------------------------------------------------------------------------

class PackageVerifierTest {
    private fun write(
        root: Path,
        relative: String,
        content: String,
    ): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, content.toByteArray())
        return path
    }

    private fun executableComponent(
        path: String,
        content: String,
        signature: String? = null,
    ): Map<String, Any?> =
        componentMap(
            componentId = "codex-acp-macos-arm64",
            path = path,
            sha256 = sha256Hex(content),
            size = content.toByteArray().size.toLong(),
            executable = path,
            signature = signature,
        )

    private fun manifestFor(component: Map<String, Any?>): RuntimeManifestV2 = manifest(components = listOf(component))

    private fun codes(verification: PackageVerification): Set<PackageVerificationCode> =
        (verification as PackageVerification.Rejected).issues.map { it.code }.toSet()

    @Test
    fun `valid package verifies with real digests`() {
        val root = Files.createTempDirectory("pkg-valid")
        val content = "#!/bin/sh\necho code4me-runtime\n"
        write(root, "bin/codex-acp.sh", content)
        write(root, "LICENSE", "MIT License\n")

        val verification = PackageVerifier.verify(root, manifestFor(executableComponent("bin/codex-acp.sh", content)))

        assertTrue(verification is PackageVerification.Verified)
        assertEquals(1, (verification as PackageVerification.Verified).verifiedComponents)
    }

    @Test
    fun `absolute and parent paths are path escapes`() {
        val root = Files.createTempDirectory("pkg-paths")

        val absolute = manifestFor(componentMap(path = "/etc/passwd", executable = "/etc/passwd", size = 10L))
        assertTrue(PackageVerificationCode.PATH_ESCAPE in codes(PackageVerifier.verify(root, absolute)))

        val parent = manifestFor(componentMap(path = "../escape", executable = "../escape", size = 10L))
        assertTrue(PackageVerificationCode.PATH_ESCAPE in codes(PackageVerifier.verify(root, parent)))
    }

    @Test
    fun `blank and nul paths are path unsafe`() {
        val root = Files.createTempDirectory("pkg-unsafe")
        val blank = manifestFor(componentMap(path = "", executable = ""))
        assertTrue(PackageVerificationCode.PATH_UNSAFE in codes(PackageVerifier.verify(root, blank)))

        val nul = manifestFor(componentMap(path = "bin/\u0000bad", executable = "bin/\u0000bad"))
        assertTrue(PackageVerificationCode.PATH_UNSAFE in codes(PackageVerifier.verify(root, nul)))
    }

    @Test
    fun `symlink escape is rejected`() {
        val root = Files.createTempDirectory("pkg-link")
        val outside = Files.createTempFile("outside", ".bin")
        Files.write(outside, "payload".toByteArray())
        val link = root.resolve("payload.bin")
        Files.createSymbolicLink(link, outside)

        val component =
            componentMap(
                componentId = "link",
                path = "payload.bin",
                executable = "payload.bin",
                sha256 = sha256Hex("payload"),
                size = 7L,
            )
        val verification = PackageVerifier.verify(root, manifestFor(component))

        assertTrue(verification is PackageVerification.Rejected)
    }

    @Test
    fun `digest and size mismatches are typed`() {
        val root = Files.createTempDirectory("pkg-integrity")
        val content = "#!/bin/sh\necho hi\n"
        write(root, "bin/codex-acp.sh", content)

        val badDigest =
            componentMap(
                path = "bin/codex-acp.sh",
                executable = "bin/codex-acp.sh",
                sha256 = SHA_B,
                size = content.toByteArray().size.toLong(),
            )
        assertTrue(PackageVerificationCode.DIGEST_MISMATCH in codes(PackageVerifier.verify(root, manifestFor(badDigest))))

        val badSize =
            componentMap(
                path = "bin/codex-acp.sh",
                executable = "bin/codex-acp.sh",
                sha256 = sha256Hex(content),
                size = content.toByteArray().size.toLong() + 5,
            )
        assertTrue(PackageVerificationCode.SIZE_MISMATCH in codes(PackageVerifier.verify(root, manifestFor(badSize))))
    }

    @Test
    fun `claimed signature requires a verifier`() {
        val root = Files.createTempDirectory("pkg-signature")
        val content = "#!/bin/sh\necho signed\n"
        write(root, "bin/codex-acp.sh", content)
        val component = executableComponent("bin/codex-acp.sh", content, signature = "deadbeef")

        val unverified = PackageVerifier.verify(root, manifestFor(component))
        assertTrue(PackageVerificationCode.SIGNATURE_MISSING in codes(unverified))

        val verified = PackageVerifier.verify(root, manifestFor(component), verifier = acceptingSignatureVerifier)
        assertTrue(verified is PackageVerification.Verified)
    }

    @Test
    fun `undeclared executable is rejected`() {
        val root = Files.createTempDirectory("pkg-undeclared")
        val content = "#!/bin/sh\necho ok\n"
        write(root, "bin/codex-acp.sh", content)
        write(root, "bin/extra.sh", "#!/bin/sh\necho extra\n")

        val verification = PackageVerifier.verify(root, manifestFor(executableComponent("bin/codex-acp.sh", content)))
        assertTrue(PackageVerificationCode.UNDECLARED_EXECUTABLE in codes(verification))
    }

    @Test
    fun `secret files are rejected`() {
        val root = Files.createTempDirectory("pkg-secret")
        val content = "#!/bin/sh\necho ok\n"
        write(root, "bin/codex-acp.sh", content)
        write(root, ".env", "AWS_SECRET_ACCESS_KEY=canary\n")

        val verification = PackageVerifier.verify(root, manifestFor(executableComponent("bin/codex-acp.sh", content)))
        assertTrue(PackageVerificationCode.SECRET_FILE_PRESENT in codes(verification))
    }

    @Test
    fun `invalid manifest is rejected`() {
        val root = Files.createTempDirectory("pkg-invalid")
        val verification = PackageVerifier.verify(root, manifest(components = emptyList()))
        assertTrue(PackageVerificationCode.INVALID_MANIFEST in codes(verification))
    }

    @Test
    fun `bootstrap resolution requires a digest match`() {
        val manifest = manifest(components = listOf(componentMap(sha256 = SHA_A)))
        val name = "codex-acp-macos-arm64"

        val matched = PackageVerifier.resolveForBootstrap(manifest, "macos", "arm64", SHA_A)
        assertTrue(matched is BootstrapResolution.Resolved)
        val resolved = matched as BootstrapResolution.Resolved
        assertEquals(name, resolved.resolved.component.componentId)
        assertEquals(SHA_A, resolved.resolved.digest)
        assertEquals(listOf("--acp"), resolved.resolved.arguments)

        // A prefixed digest is normalized, so it still matches.
        assertTrue(PackageVerifier.resolveForBootstrap(manifest, "macos", "arm64", "sha256:$SHA_A") is BootstrapResolution.Resolved)

        val mismatch = PackageVerifier.resolveForBootstrap(manifest, "macos", "arm64", SHA_B)
        assertTrue(mismatch is BootstrapResolution.Failed)
        assertEquals(
            PackageVerificationCode.BOOTSTRAP_DIGEST_MISMATCH,
            (mismatch as BootstrapResolution.Failed).issues.first().code,
        )
    }

    @Test
    fun `bootstrap resolution blocks unsupported platform`() {
        val result = PackageVerifier.resolveForBootstrap(manifest(), "windows", "x64", SHA_A)
        assertTrue(result is BootstrapResolution.Failed)
        assertEquals(
            PackageVerificationCode.UNSUPPORTED_PLATFORM,
            (result as BootstrapResolution.Failed).issues.first().code,
        )
    }

    @Test
    fun `digest normalization accepts prefixed and bare hex`() {
        assertEquals(SHA_A, PackageVerifier.normalizeDigest(SHA_A))
        assertEquals(SHA_A, PackageVerifier.normalizeDigest("sha256:$SHA_A"))
        assertEquals(SHA_A, PackageVerifier.normalizeDigest("SHA256:${SHA_A.uppercase()}"))
        assertNull(PackageVerifier.normalizeDigest("not-a-digest"))
        assertNotNull(PackageVerifier.normalizeDigest(SHA_A))
    }
}

// --------------------------------------------------------------------------
// ProcessTreeManagerTest.kt
// --------------------------------------------------------------------------

class ProcessTreeManagerTest {
    private class FakeProcess(
        override val pid: Long,
        override val parentPid: Long?,
        override val startedAtEpochMs: Long = 1_000L,
        private val gracefulKills: Boolean = true,
        private val forcedKills: Boolean = true,
        private val children: List<FakeProcess> = emptyList(),
    ) : ProcessHandle {
        var alive: Boolean = true
            private set
        var gracefulRequests: Int = 0
            private set
        var forceRequests: Int = 0
            private set

        override val isAlive: Boolean
            get() = alive

        override fun descendants(): List<ProcessHandle> = children

        override fun gracefulTerminate(): Boolean {
            gracefulRequests++
            if (gracefulKills) alive = false
            return true
        }

        override fun forceTerminate(): Boolean {
            forceRequests++
            if (forcedKills) alive = false
            return true
        }

        override fun awaitExit(timeoutMs: Long): Boolean = !alive
    }

    @Test
    fun `graceful close within the timeout exits clean`() {
        val child = FakeProcess(pid = 2, parentPid = 1)
        val root = FakeProcess(pid = 1, parentPid = null, children = listOf(child))
        val manager = ProcessTreeManager.attach(root)

        assertNull(manager.outcome)
        assertEquals(2, manager.trackedNodes().size)

        val report = manager.closeGracefully(1_000)

        assertEquals(ProcessOutcome.EXITED_CLEAN, report.outcome)
        assertFalse(report.forced)
        assertTrue(report.orphaned.isEmpty())
        assertEquals(ProcessOutcome.EXITED_CLEAN, manager.outcome)
        assertTrue(manager.orphanCheck().isEmpty())
    }

    @Test
    fun `forced kill is used after the graceful timeout`() {
        val child = FakeProcess(pid = 2, parentPid = 1, gracefulKills = false, forcedKills = true)
        val root = FakeProcess(pid = 1, parentPid = null, gracefulKills = false, forcedKills = true, children = listOf(child))
        val manager = ProcessTreeManager.attach(root)

        val report = manager.closeGracefully(500)

        assertEquals(ProcessOutcome.FORCED_KILLED, report.outcome)
        assertTrue(report.forced)
        assertTrue(report.orphaned.isEmpty())
        assertTrue(root.forceRequests > 0)
        assertTrue(child.forceRequests > 0)
        assertEquals(ProcessOutcome.FORCED_KILLED, manager.outcome)
    }

    @Test
    fun `orphans are detected after shutdown`() {
        val child =
            FakeProcess(pid = 2, parentPid = 1, gracefulKills = false, forcedKills = false)
        val root =
            FakeProcess(pid = 1, parentPid = null, gracefulKills = false, forcedKills = false, children = listOf(child))
        val manager = ProcessTreeManager.attach(root)

        val report = manager.closeGracefully(500)

        assertEquals(ProcessOutcome.ORPHANED, report.outcome)
        assertEquals(ProcessOutcome.ORPHANED, manager.outcome)
        assertEquals(setOf(1L, 2L), manager.orphanCheck().map { it.pid }.toSet())

        // A terminal outcome is never recomputed.
        val second = manager.closeGracefully(500)
        assertEquals(ProcessOutcome.ORPHANED, second.outcome)
        assertTrue(second.forced)
    }

    @Test
    fun `orphan check is empty before shutdown`() {
        val root = FakeProcess(pid = 1, parentPid = null)

        val manager = ProcessTreeManager.attach(root)

        assertTrue(manager.orphanCheck().isEmpty())
    }

    @Test
    fun `failed-to-start is a terminal outcome`() {
        val manager = ProcessTreeManager.failedToStart()

        assertEquals(ProcessOutcome.FAILED_TO_START, manager.outcome)
        assertNull(manager.rootNode)

        val report = manager.closeGracefully(10)
        assertEquals(ProcessOutcome.FAILED_TO_START, report.outcome)
        assertFalse(report.forced)
        assertTrue(report.orphaned.isEmpty())
        assertTrue(manager.orphanCheck().isEmpty())
    }

    @Test
    fun `launcher failure yields a failed-to-start manager`() {
        val launcher =
            ProcessLauncher { _, _ -> throw IOException("spawn failed") }

        val manager = ProcessTreeManager.start(launcher, listOf("bin/agent"))

        assertEquals(ProcessOutcome.FAILED_TO_START, manager.outcome)
    }

    @Test
    fun `launcher success tracks the whole descendant tree`() {
        val grandchild = FakeProcess(pid = 3, parentPid = 2, startedAtEpochMs = 300L)
        val child = FakeProcess(pid = 2, parentPid = 1, startedAtEpochMs = 200L, children = listOf(grandchild))
        val root = FakeProcess(pid = 1, parentPid = null, startedAtEpochMs = 100L, children = listOf(child))
        val launcher = ProcessLauncher { _, _ -> root }

        val manager = ProcessTreeManager.start(launcher, listOf("bin/agent"))
        val nodes = manager.trackedNodes().associateBy { it.pid }

        assertNull(manager.outcome)
        assertEquals(setOf(1L, 2L, 3L), nodes.keys)
        assertEquals(1L, nodes.getValue(2).parentPid)
        assertEquals(2L, nodes.getValue(3).parentPid)
        assertEquals(100L, nodes.getValue(1).startedAtEpochMs)
        assertEquals(300L, nodes.getValue(3).startedAtEpochMs)

        val report = manager.closeGracefully(1_000)
        assertEquals(ProcessOutcome.EXITED_CLEAN, report.outcome)
    }
}


// --------------------------------------------------------------------------
// RuntimeArtifactResolverTest.kt
// --------------------------------------------------------------------------

class RuntimeArtifactResolverTest {
    @Test
    fun `exact platform component is selected`() {
        val manifest = twoPlatformManifest()

        val macos = RuntimeArtifactResolver.resolve(manifest, "macos", "arm64")
        val linux = RuntimeArtifactResolver.resolve(manifest, "linux", "x64")

        assertTrue(macos is Resolution.Resolved)
        assertEquals("codex-acp-macos-arm64", (macos as Resolution.Resolved).component.componentId)
        assertEquals(listOf("--acp"), macos.arguments)

        assertTrue(linux is Resolution.Resolved)
        assertEquals("codex-acp-linux-x64", (linux as Resolution.Resolved).component.componentId)
    }

    @Test
    fun `unsupported platform is a typed terminal error`() {
        val result = RuntimeArtifactResolver.resolve(twoPlatformManifest(), "windows", "x64")

        assertTrue(result is Resolution.Failed)
        assertEquals(ResolutionErrorCode.UNSUPPORTED_PLATFORM, (result as Resolution.Failed).error.code)
    }

    @Test
    fun `missing artifact is a typed terminal error`() {
        val manifest =
            manifest(
                components = listOf(componentMap(componentId = "linux-only", os = "linux", arch = "x64")),
                platforms = listOf(linkedMapOf("os" to "macos", "arch" to "arm64")),
            )

        val result = RuntimeArtifactResolver.resolve(manifest, "macos", "arm64")

        assertTrue(result is Resolution.Failed)
        assertEquals(ResolutionErrorCode.ARTIFACT_MISSING, (result as Resolution.Failed).error.code)
    }

    @Test
    fun `file size and digest mismatches are typed`() {
        val directory = Files.createTempDirectory("resolver-digest")
        val content = "hello-code4me".toByteArray(Charsets.UTF_8)
        val file = Files.write(directory.resolve("agent"), content)
        val correct = sha256Hex(content)

        val verified =
            verifyFile(
                component(sha256 = correct, size = content.size.toLong()),
                file,
            )
        assertTrue(verified is VerificationResult.Passed)

        val wrongDigest = verifyFile(component(sha256 = SHA_A, size = content.size.toLong()), file)
        assertEquals(ResolutionErrorCode.DIGEST_MISMATCH, (wrongDigest as VerificationResult.Failed).error.code)

        val wrongSize = verifyFile(component(sha256 = correct, size = content.size.toLong() + 5), file)
        assertEquals(ResolutionErrorCode.DIGEST_MISMATCH, (wrongSize as VerificationResult.Failed).error.code)
    }

    @Test
    fun `missing file is a typed artifact error`() {
        val directory = Files.createTempDirectory("resolver-missing")

        val result = verifyFile(component(), directory.resolve("absent"))

        assertEquals(ResolutionErrorCode.ARTIFACT_MISSING, (result as VerificationResult.Failed).error.code)
    }

    @Test
    fun `path traversal and absolute executables are rejected`() {
        val root = Files.createTempDirectory("resolver-root")

        val dotdot = RuntimeArtifactResolver.resolveUnderRoot(root, component(executable = "../evil"))
        assertEquals(ResolutionErrorCode.PATH_UNSAFE, (dotdot as PathResolution.Rejected).error.code)

        val absolute = RuntimeArtifactResolver.resolveUnderRoot(root, component(executable = "/bin/sh"))
        assertEquals(ResolutionErrorCode.PATH_UNSAFE, (absolute as PathResolution.Rejected).error.code)

        val metacharacter = RuntimeArtifactResolver.resolveUnderRoot(root, component(executable = "bin/evil;rm"))
        assertEquals(ResolutionErrorCode.PATH_UNSAFE, (metacharacter as PathResolution.Rejected).error.code)
    }

    @Test
    fun `symlink escaping the root is rejected`() {
        val parent = Files.createTempDirectory("resolver-symlink")
        val root = Files.createDirectories(parent.resolve("root"))
        val outside = Files.createDirectories(parent.resolve("outside"))
        val target = Files.writeString(outside.resolve("evil"), "#!/bin/sh\n")
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link, target)
        } catch (exception: Exception) {
            Assumptions.assumeTrue(false, "symlinks unsupported: ${exception.message}")
            return
        }

        val result = RuntimeArtifactResolver.resolveUnderRoot(root, component(executable = "link"))

        assertEquals(ResolutionErrorCode.PATH_UNSAFE, (result as PathResolution.Rejected).error.code)
    }

    @Test
    fun `safe executable resolves inside the root`() {
        val root = Files.createTempDirectory("resolver-safe")

        val result = RuntimeArtifactResolver.resolveUnderRoot(root, component())

        assertTrue(result is PathResolution.Contained)
        assertTrue((result as PathResolution.Contained).path.startsWith(root.toAbsolutePath().normalize()))
    }

    @Test
    fun `no fallback resolution is produced when the component is unusable`() {
        val manifest = twoPlatformManifest()

        // Unsupported platform: failure is terminal and never a path.
        val unsupported = RuntimeArtifactResolver.resolve(manifest, "windows", "arm64")
        assertTrue(unsupported is Resolution.Failed)
        assertFalse(unsupported is Resolution.Resolved)

        // Unsafe executable: failure is terminal and never a path.
        val unsafeManifest =
            manifest(
                components = listOf(componentMap(executable = "../npx-agent")),
                overrides = emptyMap(),
            )
        val unsafe = RuntimeArtifactResolver.resolve(unsafeManifest, "macos", "arm64")
        assertTrue(unsafe is Resolution.Failed)
        assertEquals(ResolutionErrorCode.PATH_UNSAFE, (unsafe as Resolution.Failed).error.code)

        // Malformed digest: failure is terminal and never a path.
        val badDigest =
            manifest(components = listOf(componentMap(sha256 = "NOT-HEX")))
        val digestFailure = RuntimeArtifactResolver.resolve(badDigest, "macos", "arm64")
        assertEquals(ResolutionErrorCode.DIGEST_MISMATCH, (digestFailure as Resolution.Failed).error.code)
    }

    @Test
    fun `signature verifier integration is fail-closed`() {
        val signed = manifest(components = listOf(componentMap(signature = "sig-1")))

        val rejectedByDefault = RuntimeArtifactResolver.resolve(signed, "macos", "arm64")
        assertEquals(ResolutionErrorCode.SIGNATURE_MISSING, (rejectedByDefault as Resolution.Failed).error.code)

        val invalid =
            RuntimeArtifactResolver.resolve(
                signed,
                "macos",
                "arm64",
                SignatureVerifier { _, _ -> SignatureVerification.Invalid("bad signature") },
            )
        assertEquals(ResolutionErrorCode.SIGNATURE_INVALID, (invalid as Resolution.Failed).error.code)

        val accepted = RuntimeArtifactResolver.resolve(signed, "macos", "arm64", acceptingSignatureVerifier)
        assertTrue(accepted is Resolution.Resolved)

        val unsigned = RuntimeArtifactResolver.resolve(twoPlatformManifest(), "macos", "arm64")
        assertTrue(unsigned is Resolution.Resolved)
    }

    @Test
    fun `self-check failure is a typed terminal error`() {
        val component = component(selfCheck = SelfCheckSpec(command = "bin/codex-acp", args = listOf("--version")))
        val path = Path.of("bin/codex-acp")

        val failed =
            RuntimeArtifactResolver.verifySelfCheck(component, path, SelfCheckExecutor { _, _ -> SelfCheckOutcome(1) })
        assertEquals(ResolutionErrorCode.SELF_CHECK_FAILED, (failed as VerificationResult.Failed).error.code)

        val passed =
            RuntimeArtifactResolver.verifySelfCheck(component, path, SelfCheckExecutor { _, _ -> SelfCheckOutcome(0) })
        assertTrue(passed is VerificationResult.Passed)

        val noSelfCheck =
            RuntimeArtifactResolver.verifySelfCheck(
                component(selfCheck = null),
                path,
                SelfCheckExecutor { _, _ -> SelfCheckOutcome(3) },
            )
        assertTrue(noSelfCheck is VerificationResult.Passed)
    }

    @Test
    fun `command is an argv array with substituted placeholders`() {
        val executable = Path.of("/runtime/bin/codex-acp")
        val resolved = component(argsTemplate = listOf("--acp", "--root", "{component_dir}", "{executable}"))

        val command = RuntimeArtifactResolver.buildCommand(executable, resolved)

        assertEquals("/runtime/bin/codex-acp", command.first())
        assertEquals("--acp", command[1])
        assertEquals("/runtime/bin", command[3])
        assertEquals("/runtime/bin/codex-acp", command[4])
    }

    @Test
    fun `resolution does not expose a path on failure`() {
        val failure = RuntimeArtifactResolver.resolve(twoPlatformManifest(), "windows", "x64")
        assertThrows(ClassCastException::class.java) {
            (failure as Resolution.Resolved).component
        }
        assertFalse(failure is Resolution.Resolved)
    }
}

// --------------------------------------------------------------------------
// RuntimeManifestV2Test.kt
// --------------------------------------------------------------------------

class RuntimeManifestV2Test {
    @Test
    fun `valid manifest parses and validates`() {
        val manifest = manifest()

        assertEquals(RuntimeManifestV2.SUPPORTED_MANIFEST_VERSION, manifest.version)
        assertEquals("release-1", manifest.releaseId)
        assertEquals("codex-acp", manifest.adapterId)
        assertEquals("codex-v1", manifest.adapterVersion)
        assertEquals(1, manifest.components.size)

        val component = manifest.componentFor("macos", "arm64")!!
        assertEquals("codex-acp-macos-arm64", component.componentId)
        assertEquals("bin/codex-acp", component.path)
        assertEquals("bin/codex-acp", component.executable)
        assertEquals(listOf("--acp"), component.argsTemplate)
        assertEquals(SHA_A, component.sha256)
        assertEquals(1024L, component.size)

        assertTrue(manifest.digestMatches())
        val validation = manifest.validate()
        assertTrue(validation.isValid, validation.issues.toString())
    }

    @Test
    fun `digest mismatch is a typed error`() {
        val manifest = manifest(digestOverride = "0".repeat(64))

        val validation = manifest.validate()

        assertFalse(validation.isValid)
        assertTrue(RuntimeManifestErrorCode.DIGEST_MISMATCH in validation.codes())
    }

    @Test
    fun `digest is recomputed over canonical bytes excluding the digest field`() {
        val document = manifestMap()
        assertEquals(RuntimeManifestV2.computeDigest(document), document["manifest_digest"])
    }

    @Test
    fun `dot-dot absolute nul and metacharacter paths are rejected`() {
        val unsafePaths =
            mapOf(
                "../evil" to "..",
                "bin/../../evil" to "..",
                "/usr/local/bin/evil" to "absolute",
                "bin/evil\u0000name" to "nul",
                "bin/evil;rm -rf" to "metacharacter",
                "bin/evil\$(whoami)" to "metacharacter",
                "bin/evil`whoami`" to "metacharacter",
            )

        unsafePaths.forEach { (path, label) ->
            val validation = manifest(components = listOf(componentMap(path = path, executable = path))).validate()
            assertFalse(validation.isValid, "expected '$label' path '$path' to be rejected")
            assertTrue(
                RuntimeManifestErrorCode.UNSAFE_PATH in validation.codes(),
                "'$label' path '$path' -> ${validation.issues}",
            )
        }
    }

    @Test
    fun `non-normalized paths are rejected`() {
        listOf("bin//codex-acp", "./bin/codex-acp", "bin/./codex-acp", "bin/codex-acp/").forEach { path ->
            val validation = manifest(components = listOf(componentMap(path = path, executable = path))).validate()
            assertFalse(validation.isValid, "expected non-normalized path '$path' to be rejected")
            assertTrue(RuntimeManifestErrorCode.UNSAFE_PATH in validation.codes(), "path '$path'")
        }
    }

    @Test
    fun `bad sha256 and negative size are rejected while zero size is valid`() {
        val badSha = manifest(components = listOf(componentMap(sha256 = "NOT-A-DIGEST"))).validate()
        assertFalse(badSha.isValid)
        assertTrue(RuntimeManifestErrorCode.INVALID_SHA256 in badSha.codes())

        val upperSha = manifest(components = listOf(componentMap(sha256 = SHA_A.uppercase()))).validate()
        assertTrue(RuntimeManifestErrorCode.INVALID_SHA256 in upperSha.codes())

        // A zero-byte component is valid (for example PyInstaller REQUESTED placeholders).
        val zeroSize = manifest(components = listOf(componentMap(size = 0L))).validate()
        assertFalse(RuntimeManifestErrorCode.INVALID_SIZE in zeroSize.codes())
        assertTrue(zeroSize.isValid, zeroSize.issues.toString())

        val negativeSize = manifest(components = listOf(componentMap(size = -1L))).validate()
        assertTrue(RuntimeManifestErrorCode.INVALID_SIZE in negativeSize.codes())
    }

    @Test
    fun `duplicate platform is rejected`() {
        val first = componentMap(componentId = "one", os = "macos", arch = "arm64")
        val second = componentMap(componentId = "two", os = "macos", arch = "arm64", path = "bin/other")

        val validation = manifest(components = listOf(first, second)).validate()

        assertFalse(validation.isValid)
        assertTrue(RuntimeManifestErrorCode.DUPLICATE_PLATFORM in validation.codes())
    }

    @Test
    fun `duplicate supported platform and component id are rejected`() {
        val duplicatePlatforms =
            manifest(
                platforms =
                    listOf(
                        linkedMapOf("os" to "macos", "arch" to "arm64"),
                        linkedMapOf("os" to "macos", "arch" to "arm64"),
                    ),
            ).validate()
        assertTrue(RuntimeManifestErrorCode.DUPLICATE_SUPPORTED_PLATFORM in duplicatePlatforms.codes())

        val duplicateIds =
            manifest(
                components =
                    listOf(
                        componentMap(componentId = "same"),
                        componentMap(componentId = "same", path = "bin/other"),
                    ),
            ).validate()
        assertTrue(RuntimeManifestErrorCode.DUPLICATE_COMPONENT_ID in duplicateIds.codes())
    }

    @Test
    fun `component targeting an undeclared platform is rejected`() {
        val validation =
            manifest(
                components = listOf(componentMap(os = "linux", arch = "x64")),
                platforms = listOf(linkedMapOf("os" to "macos", "arch" to "arm64")),
            ).validate()

        assertTrue(RuntimeManifestErrorCode.COMPONENT_PLATFORM_NOT_SUPPORTED in validation.codes())
    }

    @Test
    fun `credential-shaped keys and values are rejected anywhere`() {
        val secretKeys = listOf("api_key", "token", "secret", "password", "credential", "authorization")
        secretKeys.forEach { key ->
            val validation = manifest(overrides = mapOf(key to "irrelevant")).validate()
            assertFalse(validation.isValid, "expected key '$key' to be rejected")
            assertTrue(RuntimeManifestErrorCode.SECRET_DETECTED in validation.codes(), "key '$key'")
        }

        val nested =
            manifest(overrides = mapOf("extra" to linkedMapOf("level" to linkedMapOf("api_key" to "hidden")))).validate()
        assertTrue(RuntimeManifestErrorCode.SECRET_DETECTED in nested.codes())

        val secretValue = manifest(overrides = mapOf("release_id" to "sk-abcdefghijklmnop")).validate()
        assertTrue(RuntimeManifestErrorCode.SECRET_DETECTED in secretValue.codes())
    }

    @Test
    fun `undeclared executable assets are rejected`() {
        val validation = manifest(overrides = mapOf("executables" to listOf("bin/extra-tool"))).validate()

        assertFalse(validation.isValid)
        assertTrue(RuntimeManifestErrorCode.UNDECLARED_EXECUTABLE in validation.codes())
    }

    @Test
    fun `component without a declared executable is rejected`() {
        val validation = manifest(components = listOf(componentMap(executable = ""))).validate()

        assertTrue(RuntimeManifestErrorCode.MISSING_COMPONENT_EXECUTABLE in validation.codes())
    }

    @Test
    fun `deferred fields are optional and preserved`() {
        val component =
            componentMap(
                signature = "sig-1",
                license = linkedMapOf("license_id" to "Apache-2.0", "spdx" to "Apache-2.0", "path" to "LICENSES/Apache-2.0.txt"),
                selfCheck = linkedMapOf("command" to "bin/codex-acp", "args" to listOf("--version")),
            )
        val parsed = manifest(components = listOf(component))

        val parsedComponent = parsed.components.single()
        assertEquals("sig-1", parsedComponent.signature)
        assertEquals("Apache-2.0", parsedComponent.license?.spdx)
        assertEquals("bin/codex-acp", parsedComponent.selfCheck?.command)
        assertEquals(listOf("--version"), parsedComponent.selfCheck?.args)
        assertTrue(parsed.validate().isValid)
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val validation = manifest(overrides = mapOf("manifest_version" to "1")).validate()

        assertTrue(RuntimeManifestErrorCode.SCHEMA_VERSION_UNSUPPORTED in validation.codes())
    }

    @Test
    fun `missing adapter identity is rejected`() {
        val component =
            RuntimeComponent(
                componentId = "codex-acp-macos-arm64",
                path = "bin/codex-acp",
                sha256 = SHA_A,
                size = 1024L,
                os = "macos",
                arch = "arm64",
                executable = "bin/codex-acp",
            )
        val raw = emptyMap<String, Any?>()
        val manifest =
            RuntimeManifestV2(
                schema = RuntimeManifestV2.MANIFEST_SCHEMA,
                version = RuntimeManifestV2.SUPPORTED_MANIFEST_VERSION,
                digest = RuntimeManifestV2.computeDigest(raw),
                releaseId = "release-1",
                adapterId = "",
                adapterVersion = "",
                components = listOf(component),
                platforms = listOf(PlatformTriple("macos", "arm64")),
                raw = raw,
            )

        assertTrue(RuntimeManifestErrorCode.MISSING_ADAPTER_IDENTITY in manifest.validate().codes())
    }

    @Test
    fun `unparseable compatibility ranges are rejected`() {
        val validation = manifest(overrides = mapOf("compatibility" to linkedMapOf("plugin" to "not-a-range"))).validate()

        assertTrue(RuntimeManifestErrorCode.INVALID_COMPATIBILITY in validation.codes())
    }

    @Test
    fun `declared compatibility ranges parse`() {
        val manifest = manifest()

        assertTrue(manifest.validate().isValid)
        val range = VersionRange.parse(manifest.compatibility.constraints.getValue("plugin"))!!
        assertTrue(range.satisfies(SemanticVersion.parse("1.5.0")!!))
        assertFalse(range.satisfies(SemanticVersion.parse("2.0.0")!!))
    }

    @Test
    fun `malformed json and missing fields throw a typed parse error`() {
        assertThrows(RuntimeManifestParseException::class.java) { RuntimeManifestV2.parse("{not json") }
        assertThrows(RuntimeManifestParseException::class.java) { RuntimeManifestV2.parse("[]") }

        val document = manifestMap().toMutableMap()
        document.remove("release_id")
        assertThrows(RuntimeManifestParseException::class.java) {
            RuntimeManifestV2.parse(me.code4me.research.telemetry.canonicalJson(document))
        }
    }
}

// --------------------------------------------------------------------------
// RuntimeTestFixtures.kt
// --------------------------------------------------------------------------

/** Deterministic 64-hex digests for tests. */

internal fun hexDigest(seed: Char): String = seed.toString().repeat(64)

internal val SHA_A: String = hexDigest('a')
internal val SHA_B: String = hexDigest('b')
internal val SHA_C: String = hexDigest('c')

internal fun componentMap(
    componentId: String = "codex-acp-macos-arm64",
    path: String = "bin/codex-acp",
    sha256: String = SHA_A,
    size: Long = 1024L,
    os: String = "macos",
    arch: String = "arm64",
    executable: String = path,
    argsTemplate: List<String> = listOf("--acp"),
    signature: String? = null,
    license: Map<String, Any?>? = null,
    selfCheck: Map<String, Any?>? = null,
): Map<String, Any?> =
    linkedMapOf(
        "component_id" to componentId,
        "path" to path,
        "sha256" to sha256,
        "size" to size,
        "os" to os,
        "arch" to arch,
        "executable" to executable,
        "args_template" to argsTemplate,
        "signature" to signature,
        "license" to license,
        "self_check" to selfCheck,
    )

internal fun component(
    componentId: String = "codex-acp-macos-arm64",
    path: String = "bin/codex-acp",
    sha256: String = SHA_A,
    size: Long = 1024L,
    os: String = "macos",
    arch: String = "arm64",
    executable: String = path,
    argsTemplate: List<String> = listOf("--acp"),
    signature: String? = null,
    selfCheck: SelfCheckSpec? = null,
): RuntimeComponent =
    RuntimeComponent(
        componentId = componentId,
        path = path,
        sha256 = sha256,
        size = size,
        os = os,
        arch = arch,
        executable = executable,
        argsTemplate = argsTemplate,
        signature = signature,
        selfCheck = selfCheck,
    )

internal fun manifestMap(
    components: List<Map<String, Any?>> = listOf(componentMap()),
    platforms: List<Map<String, Any?>> = listOf(linkedMapOf("os" to "macos", "arch" to "arm64")),
    adapterId: String = "codex-acp",
    adapterVersion: String = "codex-v1",
    overrides: Map<String, Any?> = emptyMap(),
    digestOverride: String? = null,
): Map<String, Any?> {
    val base =
        linkedMapOf<String, Any?>(
            "manifest_schema" to RuntimeManifestV2.MANIFEST_SCHEMA,
            "manifest_version" to RuntimeManifestV2.SUPPORTED_MANIFEST_VERSION,
            "release_id" to "release-1",
            "adapter_id" to adapterId,
            "adapter_version" to adapterVersion,
            "components" to components,
            "supported_platforms" to platforms,
            "licenses" to emptyList<Any?>(),
            "compatibility" to linkedMapOf("plugin" to ">=1.0.0 <2.0.0"),
            "self_check" to null,
            "signature" to null,
        )
    base.putAll(overrides)
    base["manifest_digest"] = digestOverride ?: RuntimeManifestV2.computeDigest(base)
    return base
}

internal fun manifest(
    components: List<Map<String, Any?>> = listOf(componentMap()),
    platforms: List<Map<String, Any?>> = listOf(linkedMapOf("os" to "macos", "arch" to "arm64")),
    overrides: Map<String, Any?> = emptyMap(),
    digestOverride: String? = null,
): RuntimeManifestV2 =
    RuntimeManifestV2.parse(
        canonicalJson(
            manifestMap(
                components = components,
                platforms = platforms,
                overrides = overrides,
                digestOverride = digestOverride,
            ),
        ),
    )

/** Two-platform manifest used by resolver tests. */
internal fun twoPlatformManifest(): RuntimeManifestV2 =
    manifest(
        components =
            listOf(
                componentMap(
                    componentId = "codex-acp-macos-arm64",
                    os = "macos",
                    arch = "arm64",
                    sha256 = SHA_A,
                ),
                componentMap(
                    componentId = "codex-acp-linux-x64",
                    os = "linux",
                    arch = "x64",
                    sha256 = SHA_B,
                    path = "bin/codex-acp",
                ),
            ),
        platforms =
            listOf(
                linkedMapOf("os" to "macos", "arch" to "arm64"),
                linkedMapOf("os" to "linux", "arch" to "x64"),
            ),
    )
