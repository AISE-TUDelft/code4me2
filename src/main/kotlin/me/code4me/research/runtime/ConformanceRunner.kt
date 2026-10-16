package me.code4me.research.runtime

/**
 * Capability-aware conformance runner (Issue 11).
 *
 * The runner executes only cases whose prerequisites are established. A missing
 * capability produces [ConformanceStatus.UNSUPPORTED] (never a skip or a pass),
 * an unknown capability produces [ConformanceStatus.UNKNOWN], and a failed case
 * produces [ConformanceStatus.FAIL] with its observations. Every case, run or
 * not, carries a [CleanupRecord].
 *
 * The clock and receipt-id generator are injected so a run is deterministic.
 */

/** What a case executor observed while running one case. */
data class ConformanceCaseOutcome(
    val observations: List<String> = emptyList(),
    val failureReason: String? = null,
    val evidenceDigests: List<String> = emptyList(),
    val performance: List<PerformanceBounds> = emptyList(),
    val cleanup: CleanupRecord = CleanupRecord.cleaned(),
)

/** Runs one conformance case against its fixture. Injectable for tests. */
fun interface ConformanceCaseExecutor {
    fun run(
        case: ConformanceCaseV1,
        fixtureId: String,
    ): ConformanceCaseOutcome
}

/** Everything a run must bind: cases, capability evidence, and exact digests. */
data class ConformanceRunInput(
    val cases: List<ConformanceCaseV1>,
    val capabilities: CapabilityLookup,
    val fixtureDigests: Map<String, String>,
    val artifactDigest: String,
    val adapterId: String,
    val adapterVersion: String,
    val hostVersion: String,
    val pluginVersion: String,
    val acpProtocolVersion: String,
    val hostPlatform: String? = null,
)

/** Runs conformance cases and produces a digest-bound receipt. */
object ConformanceRunner {
    /**
     * Run every case in [input] whose prerequisite is established.
     *
     * @param clock injected wall clock in epoch milliseconds.
     * @param receiptIdFactory injected deterministic receipt id source.
     */
    fun run(
        input: ConformanceRunInput,
        executor: ConformanceCaseExecutor,
        clock: () -> Long = System::currentTimeMillis,
        receiptIdFactory: () -> String = { "receipt-" + java.util.UUID.randomUUID() },
    ): ConformanceReceiptV1 {
        val results = input.cases.map { case -> runCase(case, input, executor) }
        return ConformanceReceiptV1(
            receiptId = receiptIdFactory(),
            artifactDigest = input.artifactDigest,
            adapterId = input.adapterId,
            adapterVersion = input.adapterVersion,
            hostVersion = input.hostVersion,
            pluginVersion = input.pluginVersion,
            acpProtocolVersion = input.acpProtocolVersion,
            fixtureDigests = input.fixtureDigests,
            cases = results,
            overallStatus = ConformanceStatus.overall(results.map { it.status }),
            generatedAtEpochMs = clock(),
            hostPlatform = input.hostPlatform,
        )
    }

    /**
     * True only when every required case in [receipt] is
     * [ConformanceStatus.PASS]. Delegates to
     * [ConformanceReceiptV1.advertisedBehaviorSupported].
     */
    fun advertisedBehaviorSupported(
        receipt: ConformanceReceiptV1,
        requiredCaseIds: Collection<String>? = null,
    ): Boolean = receipt.advertisedBehaviorSupported(requiredCaseIds)

    private fun runCase(
        case: ConformanceCaseV1,
        input: ConformanceRunInput,
        executor: ConformanceCaseExecutor,
    ): ConformanceCaseV1 {
        val prerequisite = case.prerequisite
        if (prerequisite != null) {
            when (prerequisite.evaluate(input.capabilities)) {
                PredicateOutcome.NOT_ESTABLISHED ->
                    return case.withResult(
                        status = ConformanceStatus.UNSUPPORTED,
                        observations =
                            listOf(
                                "capability '${prerequisite.capability}' is not established " +
                                    "in the ${prerequisite.channel.value} channel",
                            ),
                        cleanup = CleanupRecord.notRequired("case not run: prerequisite missing"),
                        performance = emptyList(),
                        evidenceDigests = emptyList(),
                    )
                PredicateOutcome.UNKNOWN ->
                    return case.withResult(
                        status = ConformanceStatus.UNKNOWN,
                        observations =
                            listOf(
                                "capability '${prerequisite.capability}' has no usable " +
                                    "${prerequisite.channel.value} evidence",
                            ),
                        cleanup = CleanupRecord.notRequired("case not run: prerequisite unknown"),
                        performance = emptyList(),
                        evidenceDigests = emptyList(),
                    )
                PredicateOutcome.ESTABLISHED -> Unit
            }
        }

        if (case.setupFixtureId !in input.fixtureDigests) {
            return case.withResult(
                status = ConformanceStatus.BLOCKED,
                observations = listOf("fixture '${case.setupFixtureId}' has no bound digest"),
                cleanup = CleanupRecord.notRequired("case not run: fixture digest missing"),
                performance = emptyList(),
                evidenceDigests = emptyList(),
            )
        }

        val outcome =
            try {
                executor.run(case, case.setupFixtureId)
            } catch (exception: Exception) {
                return case.withResult(
                    status = ConformanceStatus.FAIL,
                    observations = listOf("case executor threw: ${exception::class.simpleName}: ${exception.message}"),
                    cleanup = CleanupRecord.failed("cleanup could not complete after executor failure"),
                    performance = emptyList(),
                    evidenceDigests = emptyList(),
                )
            }

        val boundsHeld = outcome.performance.all { it.met }
        val passed = outcome.failureReason == null && boundsHeld
        val observations =
            buildList {
                addAll(outcome.observations)
                outcome.failureReason?.let { add("failure: $it") }
                outcome.performance.filterNot { it.met }.forEach {
                    add("performance bound '${it.metric}' not met: ${it.observedMs}ms > ${it.limitMs}ms")
                }
            }
        val status = if (passed) ConformanceStatus.PASS else ConformanceStatus.FAIL
        return case.withResult(
            status = status,
            observations = observations,
            cleanup = outcome.cleanup,
            performance = outcome.performance,
            evidenceDigests = outcome.evidenceDigests,
        )
    }
}
