package me.code4me.research.ide

/**
 * Pure mapping from public IntelliJ listener facts to metadata-only
 * [IdeActivitySignal]s (Issue 10).
 *
 * This object deliberately imports no IntelliJ types and never sees editor
 * text, file paths, prompts, diffs, or command output. Callers extract only the
 * primitives allowed here (extension, file-type name, executor id, bounded
 * length) and the mapper drops anything that does not fit a documented
 * metadata key. Values that fail their allowlist shape are omitted rather than
 * coerced, so a canary cannot be smuggled through an allowlisted slot.
 */
internal object IntellijIdeActivityMapper {
    private val EXTENSION_PATTERN = Regex("^[a-z0-9]{1,16}$")
    private val LANGUAGE_PATTERN = Regex("^[a-z0-9+#_-]{1,32}$")

    /** Bound mirrored from [IdeActivityEventBuilder] so a count can never exceed policy. */
    const val MAX_COUNT: Long = 1_000_000L

    /** File opened/closed/saved signals carrying only extension and language. */
    fun fileSignal(
        kind: String,
        projectKey: String,
        extension: String?,
        language: String?,
    ): IdeActivitySignal = IdeActivitySignal(kind = kind, projectKey = projectKey, metadata = fileMetadata(extension, language))

    /** Document-changed signal carrying metadata plus a bounded changed-length count. */
    fun documentSignal(
        projectKey: String,
        extension: String?,
        language: String?,
        changedCharacters: Int,
    ): IdeActivitySignal {
        val metadata = fileMetadata(extension, language)
        metadata[IdePayloadKey.COUNT.key] = changedCharacters.coerceIn(0, MAX_COUNT.toInt()).toLong()
        return IdeActivitySignal(kind = IdeActivityKind.CHANGED.wire, projectKey = projectKey, metadata = metadata)
    }

    /** Run/debug execution signal carrying only the bounded action category. */
    fun runSignal(
        projectKey: String,
        executorId: String?,
    ): IdeActivitySignal {
        val category = if (executorId?.contains("debug", ignoreCase = true) == true) "DEBUG" else "RUN"
        return IdeActivitySignal(
            kind = IdeActivityKind.RUN_EXECUTED.wire,
            projectKey = projectKey,
            metadata = linkedMapOf(IdePayloadKey.ACTION_CATEGORY.key to category),
        )
    }

    /** Project lifecycle signal; the collector preserves the unrecognized kind for review. */
    fun lifecycleSignal(
        kind: String,
        projectKey: String,
    ): IdeActivitySignal = IdeActivitySignal(kind = kind, projectKey = projectKey)

    /** Lowercase valid extension, or `null` when it is missing or not a plain extension. */
    fun sanitizeExtension(raw: String?): String? {
        val value = raw?.removePrefix(".")?.trim()?.lowercase() ?: return null
        return value.takeIf { EXTENSION_PATTERN.matches(it) }
    }

    /** Lowercase valid language identifier, or `null` when it is not a short identifier. */
    fun sanitizeLanguage(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        return value.takeIf { LANGUAGE_PATTERN.matches(it) }
    }

    private fun fileMetadata(
        extension: String?,
        language: String?,
    ): LinkedHashMap<String, Any?> {
        val metadata = LinkedHashMap<String, Any?>()
        sanitizeExtension(extension)?.let { metadata[IdePayloadKey.FILE_EXTENSION.key] = it }
        sanitizeLanguage(language)?.let { metadata[IdePayloadKey.LANGUAGE.key] = it }
        return metadata
    }
}
