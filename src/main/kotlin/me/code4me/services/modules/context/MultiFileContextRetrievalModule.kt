
package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil.sanitizeFileName
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
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
        private const val PREF_INCLUDE_CONTENTS = "context.include.contents"
        private const val PREF_INCLUDE_LOCATION = "context.include.location"
        private const val PREF_INCLUDE_PSI = "context.include.psi"

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
                LOG.debug("Module ${moduleName} is disabled, skipping data collection")
                return emptyList()
            }

            val moduleId = getPreferenceId()
            val includeContent = getBooleanPreference(moduleId, PREF_INCLUDE_CONTENTS, true)
            val includeLocation = getBooleanPreference(moduleId, PREF_INCLUDE_LOCATION, true)
            val includePsi = getBooleanPreference(moduleId, PREF_INCLUDE_PSI, true)

            val expanded = mutableMapOf<Record.EntryKey, Any>()
            val editors = EditorFactory.getInstance().allEditors
            val currentEditor = request.editor
            var processedFiles = 0

            for (editor in editors) {
                // Skip the current editor to avoid duplicate context
                if (editor == currentEditor) continue

                val project = editor.project ?: continue
                val document = editor.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: continue
                val caretOffset = editor.caretModel.offset

                try {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                    val fileText = document.text

                    // Extract PSI element at cursor position if available
                    val topElement = if (includePsi && psiFile != null) {
                        PsiTreeUtil.getParentOfType(
                            psiFile.findElementAt(caretOffset),
                            PsiElement::class.java,
                        )
                    } else null

                    // Build context object based on preferences
                    val fileContext = FileContext(
                        location = if (includeLocation) file.path else "",
                        psiElement = if (includePsi) topElement?.text ?: "" else "",
                        contents = if (includeContent) fileText else ""
                    )

                    // Create standardized key for this file context
                    val sanitizedFileName = sanitizeFileName(file.name)
                    val key = Record.key<FileContext>("${KEY_PREFIX_MULTI_FILE}.${sanitizedFileName}")
                    expanded[key] = fileContext
                    processedFiles++

                    LOG.trace("Collected context for file: ${file.name}")

                } catch (e: Exception) {
                    LOG.warn("Failed to collect context for file: ${file.name}", e)
                    // Continue processing other files even if one fails
                }
            }

            LOG.debug("Successfully collected context from $processedFiles additional files")
            return if (expanded.isNotEmpty()) {
                listOf(Record(type = Record.Type.CONTEXT, expanded = expanded.toMutableMap()))
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
        LOG.debug("Initialized ${moduleName}")
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
    override fun getPreferenceList(): List<Preference> = listOf(
        Preference(
            key = PREF_INCLUDE_CONTENTS,
            type = PreferenceType.BOOLEAN,
            defaultValue = "true",
            displayName = "Include File Contents",
            description = "Include the complete contents of open files. " +
                    "Disable for better performance with large files."
        ),
        Preference(
            key = PREF_INCLUDE_LOCATION,
            type = PreferenceType.BOOLEAN,
            defaultValue = "true",
            displayName = "Include File Location",
            description = "Include the file system path for each open file."
        ),
        Preference(
            key = PREF_INCLUDE_PSI,
            type = PreferenceType.BOOLEAN,
            defaultValue = "true",
            displayName = "Include PSI Element",
            description = "Include the PSI (Program Structure Interface) element near " +
                    "the cursor position for structural code understanding."
        )
    )
}