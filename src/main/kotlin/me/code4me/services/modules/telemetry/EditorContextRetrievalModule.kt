package me.code4me.services.modules.telemetry

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.fileEditor.FileDocumentManager
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.services.state.PrefState
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType

class EditorContextRetrievalModule : PluginModule {
    override val moduleName = "EditorContextRetrievalModule"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val prefState = getPrefState()
        if (!prefState.enabledModules.contains(getPreferenceId())) {
            return emptyList()
        }

        val moduleId = getPreferenceId()
        val project = request.file.project
        val editor = request.editor
        val document = request.document
        val psiFile = request.file

        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return emptyList()
        val caretModel = editor.caretModel
        val logicalPosition = caretModel.logicalPosition

        val selectionModel = editor.selectionModel
        val selectedText: String? =
            if (selectionModel.hasSelection()) {
                selectionModel.selectedText
            } else {
                null
            }

        val expanded = mutableMapOf<Record.EntryKey, Any>()

        // TODO: uncomment when the preference storage properly works
//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.language")?.toBoolean() == true*/true) {
        if (true) {
            val languageKey = Record.Companion.key<String>("context.language")
            expanded[languageKey] = psiFile.language.displayName
        }
//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.filename")?.toBoolean() == true*/true) {
        if (true) {
            val fileNameKey = Record.Companion.key<String>("context.file.name")
            expanded[fileNameKey] = virtualFile.name
        }

//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.filepath")?.toBoolean() == true*/true) {
        if (true) {
            val filePathKey = Record.Companion.key<String>("context.file.path")
            expanded[filePathKey] = virtualFile.path
        }

//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.caret.offset")?.toBoolean() == true*/true) {
        if (true) {
            val caretOffsetKey = Record.Companion.key<Int>("context.caret.offset")
            expanded[caretOffsetKey] = caretModel.offset
        }

//        if (/*PrefState.getPreferenceValue(moduleId, "context.include.caret.position")?.toBoolean() == true*/true) {]
        if (true) {
            val caretLineKey = Record.Companion.key<Int>("context.caret.line")

            val caretColumnKey = Record.Companion.key<Int>("context.caret.column")
            expanded[caretLineKey] = logicalPosition.line
            expanded[caretColumnKey] = logicalPosition.column
        }
        if (PrefState.Companion.getPreferenceValue(
                moduleId,
                "context.include.selection.text",
            )?.toBoolean() == true && !selectedText.isNullOrEmpty()
        ) {
            val selectionTextKey = Record.Companion.key<String>("context.selection.text")
            expanded[selectionTextKey] = selectedText
        }

        val record = Record(type = Record.Type.CONTEXT, expanded = expanded)
        return listOf(record)
    }

    override fun getStatus(): String {
        return "Ready"
    }

    override fun initializeModules() {
    }

    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = "context.include.language",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Language",
                description = "Include the programming language of the current file in context data.",
            ),
            Preference(
                key = "context.include.filename",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Name",
                description = "Include the name of the file in context data.",
            ),
            Preference(
                key = "context.include.filepath",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Path",
                description = "Include the full file path in context data.",
            ),
            Preference(
                key = "context.include.caret.offset",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Caret Offset",
                description = "Include the caret's offset from the beginning of the document.",
            ),
            Preference(
                key = "context.include.caret.position",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Caret Line and Column",
                description = "Include the line and column number of the caret position.",
            ),
            Preference(
                key = "context.include.selection.text",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Selected Text",
                description = "Include currently selected text, if any, in context data.",
            ),
        )

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.TELEMETRY
    }
}