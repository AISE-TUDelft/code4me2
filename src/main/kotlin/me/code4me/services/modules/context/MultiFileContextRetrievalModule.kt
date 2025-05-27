package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import me.code4me.services.modules.PluginModule
import me.code4me.utils.record.Record
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType

class MultiFileContextRetrievalModule : PluginModule {
    data class FileContext(
        val location: String,
        val psiElement: String,
        val contents: String,
    )

    override val moduleName: String = "MultiFileContextRetrievalModule"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val prefState = me.code4me.services.state.getPrefState()
        if (!prefState.enabledModules.contains(getPreferenceId())) {
            return emptyList()
        }

        val moduleId = getPreferenceId()
        // TODO switch to preferences once fully implemented
        val includeContent = true
        val includeLocation = true
        val includePsi = true

        val expanded = mutableMapOf<Record.EntryKey, Any>()
        val editors = EditorFactory.getInstance().allEditors
        val currentEditor = request.editor
        for (editor in editors) {
            if (editor == currentEditor) continue
            val project = editor.project ?: continue
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: continue
            val caretOffset = editor.caretModel.offset
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: continue
            val fileText = document.text

            val topElement =
                PsiTreeUtil.getParentOfType(
                    psiFile.findElementAt(caretOffset),
                    PsiElement::class.java,
                )

            val location = if (includeLocation) file.path else ""
            val psi = if (includePsi) topElement?.text ?: "" else ""
            val content = if (includeContent) fileText else ""

            val fileContext =
                FileContext(
                    location = location,
                    psiElement = psi,
                    contents = content,
                )

            val key = Record.key<FileContext>("context.file.${file.name}")
            expanded[key] = fileContext
        }

        return listOf(Record(type = Record.Type.CONTEXT, expanded = expanded.toMutableMap()))
    }

    override fun getStatus(): String = "Ready"

    override fun initializeModules() {}

    override fun getPreferenceClass(): PreferenceClass = PreferenceClass.CONTEXT

    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = "context.include.contents",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Contents",
                description = "Include the entire contents of the open file.",
            ),
            Preference(
                key = "context.include.location",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Location",
                description = "Include the file path for the open file.",
            ),
            Preference(
                key = "context.include.psi",
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include PSI Element",
                description = "Include the PSI element near the caret.",
            ),
        )
}
