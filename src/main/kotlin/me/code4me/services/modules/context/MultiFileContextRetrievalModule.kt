package me.code4me.services.modules

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import me.code4me.services.modules.context.exctractors.ImportExtractor
import me.code4me.services.modules.context.exctractors.JavaImportExtractor
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass

// Class currentlty nonfuncitonal and being worked on
class MultiFileContextRetrievalModule : PluginModule {
    override val moduleName: String = "MultiFileContextRetrievalModule"
    private val extractors: List<ImportExtractor> =
        listOf(
            JavaImportExtractor(),
            // TODO add more
        )

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        val editor = request.editor
        val project = editor.project ?: return emptyList()
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return emptyList()

        val allImports =
            extractors
                .firstOrNull { it.supports(psiFile) }
                ?.extractImports(psiFile)
                ?: emptyList()

        val results = mutableListOf<Record>()
        print("IMPORTS FOUND")
        print(allImports)
        for (importText in allImports) {
            val projectFiles = findProjectFilesByImport(importText, project)
            println("Found project files: $projectFiles")
            for (file in projectFiles) {
                val filePsi = PsiManager.getInstance(project).findFile(file) ?: continue

                results.add(
                    Record(
                        Record.Type.CONTEXT,
                        mutableMapOf(
                            Record.key<String>("content") to filePsi.text,
                            Record.key<String>("location") to file.path,
                            Record.key<String>("name") to file.name,
                            Record.key<String>("package") to extractPackageName(filePsi),
                        ),
                    ),
                )
            }
        }

        return results
    }

    private fun extractPackageName(psiFile: PsiFile): String {
        return when (psiFile.language.id) {
            "JAVA" -> psiFile.containingDirectory?.name ?: ""
            else -> ""
        }
    }

    //TODO fix, doesn't work properly currently, returns empty list
    private fun findProjectFilesByImport(
        importText: String,
        project: Project,
    ): List<VirtualFile> {
        val packagePath = importText.substringBeforeLast('.').replace('.', '/')
        val className = importText.substringAfterLast('.')

        val projectBaseDir = project.baseDir
        println("Import Text: $importText")
        println("Package Path: $packagePath")
        println("Class Name: $className")

        val matchedFiles = mutableListOf<VirtualFile>()

        fun searchFilesInDirectory(directory: VirtualFile) {
            if (!directory.isDirectory) return

            for (file in directory.children) {
                if (file.name == "$className.java" || file.name == "$className.kt") {
                    val filePath = file.path.replace(projectBaseDir.path, "") // Remove project root to get relative path
                    if (filePath.startsWith(packagePath)) {
                        matchedFiles.add(file)
                    }
                }

                if (file.isDirectory) {
                    searchFilesInDirectory(file)
                }
            }
        }

        searchFilesInDirectory(projectBaseDir)

        return matchedFiles
    }

    override fun getStatus(): String = "Ready"

    override fun initializeModules() {}

    override fun getPreferenceList(): List<Preference> {
        TODO("Not yet implemented")
    }

    override fun getPreferenceClass(): PreferenceClass {
        TODO("Not yet implemented")
    }
}
