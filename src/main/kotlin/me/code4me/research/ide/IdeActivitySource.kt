package me.code4me.research.ide

/**
 * One raw, source-local IDE activity signal (Issue 10).
 *
 * This is the untrusted boundary: [metadata] may carry arbitrary keys and
 * values from an IDE listener, and [projectKey] is the IDE's local project
 * identity (never a participant identity). The collector is responsible for
 * mapping it to an opaque project context and for rejecting everything that is
 * not on the metadata allowlist.
 *
 * @property kind source kind (`opened`, `changed`, `saved`, or vendor-specific).
 * @property projectKey project-scoped local identity used only to derive an
 * opaque context id.
 * @property metadata untrusted metadata (extension, language, action, count).
 */
data class IdeActivitySignal(
    val kind: String,
    val projectKey: String,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    init {
        require(kind.isNotBlank()) { "kind must not be blank" }
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
    }
}

/**
 * A source of raw IDE activity signals.
 *
 * Implemented by the IntelliJ plugin with public listeners (file/document/run
 * events) and by fakes in tests. It is deliberately the *only* IDE-facing
 * contract, so the collector stays free of IntelliJ imports and is pure JVM.
 */
fun interface IdeActivitySource {
    /** Subscribe [callback]; it receives every subsequent raw signal. */
    fun onActivity(callback: (IdeActivitySignal) -> Unit)
}
