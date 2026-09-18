package me.code4me.research.telemetry

import me.code4me.research.telemetry.CoverageState
import me.code4me.research.telemetry.FieldClass
import me.code4me.research.telemetry.PolicyAction
import me.code4me.research.telemetry.canonicalJson
import me.code4me.research.telemetry.sha256Hex

/** Marker persisted in place of redacted content. */
const val REDACTED_MARKER = "[REDACTED]"

/** The outcome of filtering one payload. */
data class PrivacyFilterResult(
    /** A newly built, sanitized payload. The input is never mutated. */
    val sanitizedPayload: Map<String, Any?>,
    /** How many fields each policy action was applied to. */
    val actionsCounts: Map<PolicyAction, Int>,
    /** Per-field coverage: `AVAILABLE` kept, `UNAVAILABLE` redacted, `UNKNOWN` dropped. */
    val coverage: Map<String, CoverageState>,
    /** How many fields were seen for each class (unknown classifications are omitted). */
    val fieldClassCounts: Map<FieldClass, Int>,
    /** Dotted paths that were redacted, hashed, or dropped. */
    val redactedPaths: List<String>,
    /** True when a `BLOCK` action (or residual secret) rejected the whole payload. */
    val blocked: Boolean,
    /** Human-readable reason when [blocked] is true. */
    val blockReason: String? = null,
)

/**
 * Always-on secret detection plus deterministic field classification.
 *
 * Classification is fail-closed:
 * 1. A secret key, or a value shaped like a well-known secret, is `SECRET`.
 * 2. Free-content keys are `CONTENT`; code metadata keys are `CODE_METADATA`.
 * 3. Timing/process/schema keys are `SYSTEM`; lifecycle keys are `BEHAVIORAL`.
 * 4. Unrecognized *containers* are traversed so children are classified.
 * 5. Unrecognized *scalars* return `null`: the filter then drops them.
 */
object FieldClassifier {
    private val secretKeySubstrings: List<String> =
        listOf(
            "password",
            "passwd",
            "secret",
            "api_key",
            "apikey",
            "authorization",
            "bearer",
            "credential",
            "private_key",
            "access_key",
            "client_secret",
            "cookie",
            "session_token",
            "auth_token",
        )

    private val secretKeyExact: Set<String> =
        setOf("token", "auth", "authorization", "secret", "password", "credential")

    private val secretValuePatterns: List<Regex> =
        listOf(
            Regex("\\bsk-[A-Za-z0-9_-]{8,}"),
            Regex("\\bghp_[A-Za-z0-9]{8,}"),
            Regex("\\bAKIA[0-9A-Z]{12,}"),
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{4,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        )

    private val contentTokens: Set<String> =
        setOf(
            "content", "prompt", "prompts", "reasoning", "thought", "thoughts",
            "completion", "completions", "response", "output", "stdout", "stderr",
            "diff", "snippet", "transcript", "body", "text", "raw", "payload",
            "arguments", "cot", "chain", "message_text",
        )

    private val codeMetadataTokens: Set<String> =
        setOf(
            "path", "file", "filename", "extension", "language", "line", "lines",
            "symbol", "uri", "directory", "module", "package", "repo", "workspace",
            "document",
        )

    private val systemTokens: Set<String> =
        setOf(
            "duration", "latency", "timestamp", "clock", "process", "pid", "version",
            "schema", "emitter", "sequence", "count", "counts", "size", "bytes",
            "host", "os", "arch", "trace", "span", "level", "tokens", "tokens_used",
        )

    private val behavioralTokens: Set<String> =
        setOf(
            "tool", "permission", "turn", "message", "event", "action", "decision",
            "status", "state", "role", "model", "result", "outcome", "error", "name",
            "id", "type", "call", "edit", "session", "run", "agent", "capability",
            "fidelity", "kind", "reason", "method",
        )

    private fun normalizedKey(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun tokensOf(name: String): Set<String> = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    /** True when a mapping key names secret material. */
    fun isSecretKey(name: String): Boolean {
        val normalized = normalizedKey(name)
        if (normalized in secretKeyExact) return true
        if (normalized.endsWith("_token") || normalized.endsWith("_secret") || normalized.endsWith("_password")) {
            return true
        }
        return secretKeySubstrings.any { it in normalized }
    }

    /** True when a string value matches a well-known secret shape. */
    fun looksSecretValue(value: Any?): Boolean {
        if (value !is String) return false
        return secretValuePatterns.any { it.containsMatchIn(value) }
    }

    /** Classify one field, or `null` when the input is not a recognized scalar field. */
    fun classify(
        name: String,
        value: Any?,
    ): FieldClass? {
        if (isSecretKey(name) || looksSecretValue(value)) return FieldClass.SECRET
        val tokens = tokensOf(name)
        if (tokens.any { it in contentTokens }) return FieldClass.CONTENT
        if (tokens.any { it in codeMetadataTokens }) return FieldClass.CODE_METADATA
        if (tokens.any { it in systemTokens }) return FieldClass.SYSTEM
        if (tokens.any { it in behavioralTokens }) return FieldClass.BEHAVIORAL
        if (value is Map<*, *> || value is List<*>) return FieldClass.SYSTEM
        return null
    }

    /** Recursively detect secret-shaped content anywhere in [value]. */
    fun containsSecret(value: Any?): Boolean =
        when (value) {
            is Map<*, *> ->
                value.entries.any { (key, item) ->
                    (key is String && isSecretKey(key)) || containsSecret(item)
                }
            is List<*> -> value.any { containsSecret(it) }
            else -> looksSecretValue(value)
        }
}

private object Dropped

private class BlockedException(val path: String, val fieldClass: FieldClass?) :
    RuntimeException("field $path blocked")

private class FilterContext(val policy: PrivacyPolicy) {
    val actions = LinkedHashMap<PolicyAction, Int>()
    val classes = LinkedHashMap<FieldClass, Int>()
    val coverage = LinkedHashMap<String, CoverageState>()
    val redacted = ArrayList<String>()

    fun record(
        action: PolicyAction,
        fieldClass: FieldClass?,
    ) {
        actions[action] = (actions[action] ?: 0) + 1
        if (fieldClass != null) classes[fieldClass] = (classes[fieldClass] ?: 0) + 1
    }
}

/**
 * Applies a [PrivacyPolicy] to a payload before it reaches a spool, log, retry
 * queue, analytics, or network boundary (Issue 06).
 *
 * Guarantees:
 * - `SECRET` is always dropped, whatever the policy says.
 * - `CONTENT` persists only when the policy allows it *and* consent is active.
 * - Unclassifiable values fail closed (dropped).
 * - A residual secret anywhere in the sanitized result blocks the payload.
 * - The input map is never mutated; nested containers are rebuilt.
 */
class PrivacyFilter(private val policy: PrivacyPolicy = PrivacyPolicy.default()) {
    private companion object {
        const val MAX_DEPTH = 32
    }

    fun filter(payload: Map<String, Any?>): PrivacyFilterResult {
        val context = FilterContext(policy)
        var blocked = false
        var blockReason: String? = null
        var sanitized: Map<String, Any?> = emptyMap()
        try {
            val walked = walk(payload, "", 0, context)
            if (walked is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                sanitized = walked as Map<String, Any?>
            }
        } catch (exception: BlockedException) {
            blocked = true
            blockReason = "field ${exception.path} classified " +
                "${exception.fieldClass?.value ?: "unknown"} is blocked by policy"
            context.coverage[exception.path] = CoverageState.UNKNOWN
            context.redacted.add(exception.path)
            sanitized = emptyMap()
        }

        if (!blocked && FieldClassifier.containsSecret(sanitized)) {
            // Defense in depth: never let a residual secret survive serialization.
            blocked = true
            blockReason = "residual secret detected after filtering"
            sanitized = emptyMap()
        }

        return PrivacyFilterResult(
            sanitizedPayload = sanitized,
            actionsCounts = context.actions.toMap(),
            coverage = context.coverage.toMap(),
            fieldClassCounts = context.classes.toMap(),
            redactedPaths = context.redacted.toList(),
            blocked = blocked,
            blockReason = blockReason,
        )
    }

    private fun walk(
        value: Any?,
        path: String,
        depth: Int,
        context: FilterContext,
    ): Any? {
        if (depth > MAX_DEPTH) {
            context.record(PolicyAction.DROP, null)
            context.coverage[path] = CoverageState.UNKNOWN
            context.redacted.add(path)
            return Dropped
        }
        return when (value) {
            is Map<*, *> -> walkObject(value, path, depth, context)
            is List<*> -> walkList(value, path, depth, context)
            else -> {
                if (FieldClassifier.looksSecretValue(value)) {
                    context.record(PolicyAction.DROP, FieldClass.SECRET)
                    context.coverage[path] = CoverageState.UNKNOWN
                    context.redacted.add(path)
                    Dropped
                } else {
                    value
                }
            }
        }
    }

    private fun walkObject(
        value: Map<*, *>,
        path: String,
        depth: Int,
        context: FilterContext,
    ): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((rawKey, item) in value) {
            val key = rawKey?.toString() ?: continue
            val childPath = if (path.isEmpty()) key else "$path.$key"
            val fieldClass = FieldClassifier.classify(key, item)
            val action = context.policy.actionFor(fieldClass)
            context.record(action, fieldClass)
            when (action) {
                PolicyAction.BLOCK -> throw BlockedException(childPath, fieldClass)
                PolicyAction.DROP -> {
                    context.coverage[childPath] = CoverageState.UNKNOWN
                    context.redacted.add(childPath)
                }
                PolicyAction.REDACT -> {
                    result[key] = REDACTED_MARKER
                    context.coverage[childPath] = CoverageState.UNAVAILABLE
                    context.redacted.add(childPath)
                }
                PolicyAction.HASH -> {
                    result[key] = hashValue(item)
                    context.coverage[childPath] = CoverageState.AVAILABLE
                    context.redacted.add(childPath)
                }
                PolicyAction.ALLOW -> {
                    val walked = walk(item, childPath, depth + 1, context)
                    if (walked !== Dropped) {
                        result[key] = walked
                        context.coverage[childPath] = CoverageState.AVAILABLE
                    }
                }
            }
        }
        return result
    }

    private fun walkList(
        value: List<*>,
        path: String,
        depth: Int,
        context: FilterContext,
    ): List<Any?> {
        val result = ArrayList<Any?>(value.size)
        value.forEachIndexed { index, item ->
            val childPath = "$path[$index]"
            val walked = walk(item, childPath, depth + 1, context)
            if (walked !== Dropped) result.add(walked)
        }
        return result
    }

    private fun hashValue(value: Any?): String = "sha256:" + sha256Hex(canonicalJson(mapOf("value" to value)))
}
