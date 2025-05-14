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
        val moduleId = getPreferenceId()
        val editor = request.editor
        val document = request.document
        val fileText = document.text
        val caretOffset = editor.caretModel.offset

        val prefixLength = PrefState.getPreferenceValue(moduleId, "context.prefix.length")?.toIntOrNull() ?: 20
        val postfixLength = PrefState.getPreferenceValue(moduleId, "context.postfix.length")?.toIntOrNull() ?: 20

        val start = (caretOffset - prefixLength).coerceAtLeast(0)
        val end = (caretOffset + postfixLength).coerceAtMost(fileText.length)

        val prefix = fileText.substring(start, caretOffset.coerceAtMost(fileText.length))
        val postfix = fileText.substring(caretOffset.coerceAtMost(fileText.length), end)

        val fileContentsKey = Record.key<String>("context.file.contents")
        val prefixKey = Record.key<String>("context.file.prefix")
        val postfixKey = Record.key<String>("context.file.postfix")
        val fileLengthKey = Record.key<Int>("context.file.length")

        val expanded = mutableMapOf<Record.EntryKey, Any>()

        if (PrefState.getPreferenceValue(moduleId, "context.include.contents")?.toBoolean() == true) {
            expanded[fileContentsKey] = fileText
        }
        if (PrefState.getPreferenceValue(moduleId, "context.include.prefix")?.toBoolean() == true) {
            expanded[prefixKey] = prefix
        }
        if (PrefState.getPreferenceValue(moduleId, "context.include.postfix")?.toBoolean() == true) {
            expanded[postfixKey] = postfix
        }
        if (PrefState.getPreferenceValue(moduleId, "context.include.length")?.toBoolean() == true) {
            expanded[fileLengthKey] = fileText.length
        }

        return listOf(
            Record(type = Record.Type.CONTEXT, expanded = expanded),
        )
    }

    override fun getStatus(): String = "Ready"

    override fun initializeModules() {
    }

    override fun getPreferenceId(): String {
        return "file_context_retrieval"
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.CONTEXT
    }

    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = "context.prefix.length",
                type = PreferenceType.INT,
                defaultValue = "20",
                displayName = "Prefix Length",
                description = "Number of characters to include before the cursor.",
            ),
            Preference(
                key = "context.postfix.length",
                type = PreferenceType.INT,
                defaultValue = "20",
                displayName = "Postfix Length",
                description = "Number of characters to include after the cursor.",
            ),
            Preference(
                key = "context.include.contents",
                type = PreferenceType.BOOLEAN,
                defaultValue = "false",
                displayName = "Include Full File Contents",
                description = "Include the entire contents of the file.",
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
