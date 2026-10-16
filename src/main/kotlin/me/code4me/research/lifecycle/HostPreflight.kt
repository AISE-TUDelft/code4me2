package me.code4me.research.lifecycle

import me.code4me.research.session.StudyBlockReason

/** Typed outcome of the host preflight run before a study bootstrap; never thrown. */
sealed interface HostPreflightResult {
    /** The host can run the study; [aiAssistantVersion] is reported to the bootstrap API. */
    data class Ok(val aiAssistantVersion: String?) : HostPreflightResult

    /** The host cannot run the study until the participant acts; [detail] is participant-readable. */
    data class Blocked(
        val reason: StudyBlockReason,
        val detail: String,
    ) : HostPreflightResult
}

/**
 * Pure host preflight for the research runtime (A-03).
 *
 * The research entry is only reachable through JetBrains AI Assistant's ACP
 * registry, and IntelliJ 2026.2 shipped with an AI Assistant that did not read
 * `acp.json` at all until build [MIN_AI_ASSISTANT_VERSION]. Without this check
 * a participant on an absent or older AI Assistant would see the plugin report
 * an active study while nothing could ever be observed. The IDE service feeds
 * the installed plugin descriptor in; this object holds no platform dependency
 * so the decision is unit-testable.
 */
object HostPreflight {
    /** JetBrains AI Assistant plugin id (the ACP host). */
    const val AI_ASSISTANT_PLUGIN_ID: String = "com.intellij.ml.llm"

    /** The first AI Assistant build that reads `acp.json` on 2026.2 (LLM-29700). */
    const val MIN_AI_ASSISTANT_VERSION: String = "262.8665.344"

    /** Participant-readable name of the requirement, reused by the status surface and docs. */
    const val REQUIREMENT_TEXT: String = "JetBrains AI Assistant $MIN_AI_ASSISTANT_VERSION or newer"

    /**
     * Decide whether the host can run the study.
     *
     * @param installed whether the AI Assistant plugin descriptor exists.
     * @param enabled whether it is enabled (an installed-but-disabled plugin is
     * as absent as a missing one for ACP).
     * @param version its reported version; a blank/unknown version cannot be
     * proven new enough and is treated as too old, but is reported as such.
     */
    fun evaluate(
        installed: Boolean,
        enabled: Boolean,
        version: String?,
    ): HostPreflightResult {
        if (!installed) {
            return HostPreflightResult.Blocked(
                StudyBlockReason.AI_ASSISTANT_MISSING,
                "$REQUIREMENT_TEXT is not installed. Install it from Settings > Plugins, restart the IDE, " +
                    "then reopen the project.",
            )
        }
        if (!enabled) {
            return HostPreflightResult.Blocked(
                StudyBlockReason.AI_ASSISTANT_MISSING,
                "$REQUIREMENT_TEXT is installed but disabled. Enable it in Settings > Plugins, restart the IDE, " +
                    "then reopen the project.",
            )
        }
        val reported = version?.trim()?.takeIf { it.isNotEmpty() }
        if (reported == null) {
            return HostPreflightResult.Blocked(
                StudyBlockReason.AI_ASSISTANT_OUTDATED,
                "JetBrains AI Assistant reports no version; $REQUIREMENT_TEXT is required. " +
                    "Update it from Settings > Plugins and restart the IDE.",
            )
        }
        if (compareBuildVersions(reported, MIN_AI_ASSISTANT_VERSION) < 0) {
            return HostPreflightResult.Blocked(
                StudyBlockReason.AI_ASSISTANT_OUTDATED,
                "JetBrains AI Assistant $reported is too old; $REQUIREMENT_TEXT is required. " +
                    "Update it from Settings > Plugins and restart the IDE.",
            )
        }
        return HostPreflightResult.Ok(reported)
    }

    /**
     * Compare two dotted build versions numerically, component by component
     * (`262.8665.344` < `262.10315.125` < `263.1.2`); a missing component counts
     * as `0`, so `263.1` == `263.1.0`. A non-numeric component compares as `0`
     * up to its first digits (`262.8665.344-EAP` ~ `262.8665.344`). A blank or
     * `null` version is older than any real version, and two blanks are equal.
     */
    fun compareBuildVersions(
        left: String?,
        right: String?,
    ): Int {
        val leftParts = components(left)
        val rightParts = components(right)
        if (leftParts.isEmpty() || rightParts.isEmpty()) {
            return leftParts.size.compareTo(rightParts.size)
        }
        val length = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until length) {
            val a = leftParts.getOrElse(index) { 0L }
            val b = rightParts.getOrElse(index) { 0L }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun components(version: String?): List<Long> {
        val trimmed = version?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }
    }
}
