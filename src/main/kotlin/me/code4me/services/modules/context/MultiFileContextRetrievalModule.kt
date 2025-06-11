package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import me.code4me.utils.services.state.getBooleanPreference

/**
 * Context module responsible for collecting contextual information from multiple files
 * currently open in the IntelliJ IDE editor sessions.
 *
 * This module extends beyond the current file to gather context from all open editors,
 * providing a broader understanding of the development environment. This multi-file
 * context is particularly valuable for:
 * - Understanding related code across multiple files
 * - Providing completions based on recently viewed code
 * - Analyzing patterns across the current working set
 * - Supporting cross-file refactoring and navigation
 *
 * The module captures information from each open editor including:
 * - File location and path information
 * - PSI (Program Structure Interface) elements near cursor positions
 * - File contents (configurable inclusion)
 * - Structural code elements and context
 *
 * ## Key Features
 * - **Multi-Editor Awareness**: Processes all currently open editor instances
 * - **PSI Integration**: Leverages IntelliJ's PSI for structural code understanding
 * - **Configurable Collection**: Selective inclusion of different context types
 * - **Performance Conscious**: Respects user preferences for large files
 *
 * ## Record Structure
 * Creates individual record entries for each open file using keys in the format:
 * `multi_file_context.{filename}` containing [FileContext] data objects.
 *
 * @since 1.0.0
 * @see PluginModule
 * @see FileContextRetrievalModule
 * @see PsiElement
 */
class MultiFileContextRetrievalModule : PluginModule {
    companion object {
        private val LOG = thisLogger()

        // Preference key constants using dot notation
        private const val PREF_INCLUDE_LOCATION = "context.include.location"
        private const val PREF_INCLUDE_REFERENCED_CLASSES = "context.include.referenced_classes"
        private const val PREF_INCLUDE_OPEN_EDITORS = "context.include.open_editors"

        // Record key prefix for multi-file context
        private const val KEY_PREFIX_MULTI_FILE = "multi_file_context"
    }

    /**
     * Data class representing context information collected from a single file.
     *
     * This structured approach ensures consistent data format and type safety
     * when accessing multi-file context information.
     *
     * @property location The file system path or location identifier
     * @property psiElement The text representation of the PSI element near the cursor
     * @property contents The complete text content of the file
     */
    data class FileContext(
        val location: String,
        val psiElement: String,
        val contents: String,
    )

    /**
     * The display name for this multi-file context module.
     */
    override val moduleName: String = "MultiFileContextRetrievalModule"

    /**
     * Collects contextual information from all currently open editor instances.
     *
     * This method iterates through all open editors (excluding the current one
     * where completion is being requested) and extracts relevant context
     * information based on user preferences and configuration.
     *
     * ## Collection Process
     * 1. Validates module is enabled in user preferences
     * 2. Retrieves all open editor instances from EditorFactory
     * 3. Excludes the current editor to avoid duplication
     * 4. For each editor, extracts configured context elements
     * 5. Packages data into FileContext objects with standardized keys
     *
     * ## Context Elements Per File
     * - **Location**: File path and location information
     * - **PSI Element**: Structural code element at cursor position
     * - **Contents**: Complete file text content
     *
     * @param request The inline completion request containing current editor context
     * @return List containing a single [Record] with multi-file context data,
     *         or empty list if module is disabled or no additional files are open
     * @throws Exception If PSI operations fail or file access is denied
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        try {
            val prefState = getPrefState()
            if (!prefState.enabledModules.contains(getPreferenceId())) {
                LOG.debug("Module $moduleName is disabled, skipping data collection")
                return emptyList()
            }

            val moduleId = getPreferenceId()
            val includeRefs = getBooleanPreference(moduleId, PREF_INCLUDE_REFERENCED_CLASSES, true)
            val includeOpenEditors = getBooleanPreference(moduleId, PREF_INCLUDE_OPEN_EDITORS, true)

            val expanded = mutableMapOf<Record.EntryKey, Any>()
            val currentEditor = request.editor
            val editors = EditorFactory.getInstance().allEditors

            val allPaths = mutableSetOf<String>() // Unified set of paths

            // Collect file paths from all open editors (excluding the current one)
            if (includeOpenEditors) {
                for (editor in editors) {
                    if (editor == currentEditor) continue

                    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: continue
                    if (file.isValid) {
                        allPaths.add(file.path)
                        LOG.trace("Collected open editor path: ${file.path}")
                    }
                }
            }

            // Collect referenced file paths from PSI references in the current editor
            if (includeRefs) {
                val project = currentEditor.project
                val document = currentEditor.document
                val psiFile =
                    PsiDocumentManager.getInstance(project ?: return emptyList())
                        .getPsiFile(document)

                psiFile?.accept(
                    object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            super.visitElement(element)
                            val resolved = element.reference?.resolve()
                            val sourceFile = resolved?.containingFile?.virtualFile
                            if (sourceFile != null && sourceFile.isValid) {
                                allPaths.add(sourceFile.path)
                                LOG.trace("Collected referenced path: ${sourceFile.path}")
                            }
                        }
                    },
                )
            }

            // Add the collected paths as a single key for simplicity
            if (allPaths.isNotEmpty()) {
                expanded[Record.key<List<String>>("$KEY_PREFIX_MULTI_FILE.paths")] = allPaths.toList()
            }

            LOG.debug("Successfully collected ${allPaths.size} unique paths")
            return if (expanded.isNotEmpty()) {
                listOf(Record(type = Record.Type.CONTEXT, expanded = expanded))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            LOG.error("Failed to collect multi-file context data", e)
            return emptyList()
        }
    }

    /**
     * Initializes the multi-file context retrieval module.
     *
     * This module requires no special initialization as it operates
     * on the editor factory and PSI services provided by IntelliJ.
     */
    override fun initializeModules() {
        LOG.debug("Initialized $moduleName")
    }

    /**
     * Returns the preference class for this context module.
     *
     * @return [PreferenceClass.CONTEXT] indicating this is a context collection module
     */
    override fun getPreferenceClass(): PreferenceClass = PreferenceClass.CONTEXT

    /**
     * Provides the list of configurable preferences for this module.
     *
     * These preferences control which types of context information
     * are collected from open files, allowing users to balance
     * context richness with performance and privacy considerations.
     *
     * @return List of [Preference] objects defining module configuration options
     */
    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = PREF_INCLUDE_OPEN_EDITORS,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Open Editors",
                description = "Include file paths of all open editors excluding the current one.",
            ),
            Preference(
                key = PREF_INCLUDE_REFERENCED_CLASSES,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Referenced Classes",
                description = "Include file paths of the referenced classes in current editor.",
            ),
        )

    data class FileContextChangeData(
        val changeType: String, // e.g., "insert", "delete", "replace"
        val startLine: Int,
        val endLine: Int,
        val newLines: List<String>,
    )
}
