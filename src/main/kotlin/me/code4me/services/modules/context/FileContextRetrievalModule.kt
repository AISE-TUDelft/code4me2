package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType

class FileContextRetrievalModule : PluginModule {
    override val moduleName: String = "FileContextRetrievalModule"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val prefState = me.code4me.services.state.getPrefState()
        if (!prefState.enabledModules.contains(getPreferenceId())) {
            return emptyList()
        }

        val moduleId = getPreferenceId()
        val editor = request.editor
        val document = request.document
        val fileText = document.text
        val caretOffset = editor.caretModel.offset

        val expanded = mutableMapOf<Record.EntryKey, Any>()

        // TODO: change to use preference when properly implemented

//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.contents")?.toBoolean() == true*/true) {
        if (true) {
            val fileContentsKey = Record.key<String>("context.file.contents")
            expanded[fileContentsKey] = fileText
        }
//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.prefix")?.toBoolean() == true*/ true) {
        if (true) {
            val prefixLength = PrefState.getPreferenceValue(moduleId, "context.prefix.length")?.toIntOrNull() ?: 120
            val start = (caretOffset - prefixLength).coerceAtLeast(0)
            val prefix = fileText.substring(start, caretOffset.coerceAtMost(fileText.length))
            val prefixKey = Record.key<String>("context.file.prefix")
            expanded[prefixKey] = prefix
        }
//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.postfix")?.toBoolean() == true*/true) {
        if (true) {
            val postfixLength = PrefState.getPreferenceValue(moduleId, "context.postfix.length")?.toIntOrNull() ?: 120
            val end = (caretOffset + postfixLength).coerceAtMost(fileText.length)
            val postfix = fileText.substring(caretOffset.coerceAtMost(fileText.length), end)
            val postfixKey = Record.key<String>("context.file.postfix")
            expanded[postfixKey] = postfix
        }
//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.length")?.toBoolean() == true*/true) {
        if (true) {
            val fileLengthKey = Record.key<Int>("context.file.length")
            expanded[fileLengthKey] = fileText.length
        }

        return listOf(
            Record(type = Record.Type.CONTEXT, expanded = expanded),
        )
    }

    override fun getStatus(): String = "Ready"

    override fun initializeModules() {
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.CONTEXT
    }

    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = "context.prefix.length",
                type = PreferenceType.INT,
                defaultValue = "120",
                displayName = "Prefix Length",
                description = "Number of characters to include before the cursor.",
            ),
            Preference(
                key = "context.postfix.length",
                type = PreferenceType.INT,
                defaultValue = "120",
                displayName = "Postfix Length",
                description = "Number of characters to include after the cursor.",
            ),
            Preference(
                key = "context.include.prefix",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Prefix",
                description = "Include the text before the caret, up to prefix length.",
            ),
            Preference(
                key = "context.include.postfix",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Postfix",
                description = "Include the text after the caret, up to postfix length.",
            ),
            Preference(
                key = "context.include.length",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Length",
                description = "Include the total length of the file in characters.",
            ),
        )
}
