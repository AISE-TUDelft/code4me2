package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.Record
import me.code4me.services.state.PrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType

class EditorContextRetrievalModule : PluginModule {
    override val moduleName = "BasicContextRetrievalModule"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val project = request.file.project
        val editor = request.editor
        val document = request.document
        val psiFile = request.file


        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return emptyList()
        val caretModel = editor.caretModel
        val logicalPosition = caretModel.logicalPosition

        val languageName = psiFile.language.displayName
        val fileName = virtualFile.name
        val filePath = virtualFile.path
        val caretOffset = caretModel.offset
        val caretLine = logicalPosition.line
        val caretColumn = logicalPosition.column

        val languageKey = Record.key<String>("context.language")
        val fileNameKey = Record.key<String>("context.file.name")
        val filePathKey = Record.key<String>("context.file.path")
        val caretOffsetKey = Record.key<Int>("context.caret.offset")
        val caretLineKey = Record.key<Int>("context.caret.line")
        val timestampKey = Record.key<Long>("timestamp")
        val caretColumnKey = Record.key<Int>("context.caret.column")

        val record =
            Record(
                type = Record.Type.CONTEXT,
                expanded =
                    mutableMapOf(
                        languageKey to languageName,
                        fileNameKey to fileName,
                        filePathKey to filePath,
                        caretOffsetKey to caretOffset,
                        caretLineKey to caretLine,
                        caretColumnKey to caretColumn,
                        timestampKey to System.currentTimeMillis(),
                    ),
            )

        return listOf(record)
    }

    override fun getStatus(): String {
        return "Ready"
    }

    override fun initializeModules() {
        println("Concrete module $moduleName doesn't need to register modules.")
        PrefState.registerModule(this)
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
                defaultValue = "false",
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
        )

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.CONTEXT
    }
}
