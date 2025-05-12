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
        val project = request.file.project
        val editor = request.editor
        val document = request.document
        val psiFile = request.file

        val fileText = document.text
        val caretOffset = editor.caretModel.offset

        val prefix = fileText.substring(0, caretOffset.coerceAtMost(fileText.length))
        val postfix = fileText.substring(caretOffset.coerceAtMost(fileText.length))

        val prefixLength = PrefState.getPreferenceValue(this::class.java.simpleName, "context.prefix.length") // TODO change preference ID
        val postfixLength = PrefState.getPreferenceValue(this::class.java.simpleName, "context.postfix.length")
        val fileContentsKey = Record.key<String>("context.file.contents")
        val prefixKey = Record.key<String>("context.file.prefix")
        val postfixKey = Record.key<String>("context.file.postfix")
        val fileLengthKey = Record.key<Int>("context.file.length")

        val record =
            Record(
                type = Record.Type.CONTEXT,
                expanded =
                    mutableMapOf<Record.EntryKey, Any>(
                        fileContentsKey to fileText,
                        prefixKey to prefix,
                        postfixKey to postfix,
                        fileLengthKey to fileText.length,
                    ),
            )

        return listOf(record)
    }

    override fun getStatus(): String = "Ready"

    override fun initializeModules() {
        println("Concrete module $moduleName doesn't need to register modules.")
        PrefState.registerModule(this) // Add this to PrefState, temporary, for testing
    }

    //    override fun getPreferenceList(): List<Preference> = emptyList()

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.CONTEXT
    }

    override fun getPreferenceId(): String {
        return "file_context_retrieval"
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
        )
}
