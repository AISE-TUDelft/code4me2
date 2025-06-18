package me.code4me.services.modules.context

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import me.code4me.utils.services.state.getBooleanPreference
import me.code4me.utils.services.state.getIntPreference

/**
 * Context module responsible for collecting contextual information from the current file
 * being edited in the IntelliJ IDE.
 *
 * This module extracts various aspects of the current file context that are essential
 * for providing intelligent code completion suggestions, including:
 * - File content (full or partial based on configuration)
 * - Text prefix before the cursor position
 * - Text suffix after the cursor position
 * - File name and metadata
 *
 * The module provides configurable options for:
 * - Controlling the length of prefix and suffix text extracted
 * - Enabling/disabling specific context elements
 * - Balancing context richness with performance considerations
 *
 * Context data collected by this module is categorized as [Record.Type.CONTEXT]
 * and uses standardized key naming conventions for consistent data access.
 *
 * ## Key Features
 * - **Configurable Context Length**: Adjustable prefix and suffix lengths
 * - **Selective Content Inclusion**: Toggle individual context elements
 * - **Performance Optimized**: Efficient text extraction with bounds checking
 * - **Type-Safe Data Access**: Strongly-typed record keys for reliable data retrieval
 *
 * ## Record Keys
 * All record keys follow the convention `category.subcategory.element` with underscores
 * separating words within element names:
 * - `file_contents`: Complete file content
 * - `prefix`: Text before cursor (configurable length)
 * - `suffix`: Text after cursor (configurable length)
 * - `file_name`: Name of the current file
 *
 * @since 1.0.0
 * @see PluginModule
 * @see MultiFileContextRetrievalModule
 */
class FileContextRetrievalModule : PluginModule {
    companion object {
        private val LOG = thisLogger()

        // Default configuration values
        private const val DEFAULT_PREFIX_LENGTH = 512
        private const val DEFAULT_SUFFIX_LENGTH = 256

        // Record key names following the new naming convention
        private const val KEY_FILE_CONTENTS = "file_contents"
        private const val KEY_PREFIX = "prefix"
        private const val KEY_SUFFIX = "suffix"
        private const val KEY_FILE_NAME = "file_name"

        // Preference key names using dot notation
        private const val PREF_PREFIX_LENGTH = "context.prefix.length"
        private const val PREF_SUFFIX_LENGTH = "context.suffix.length"
        private const val PREF_INCLUDE_CONTENTS = "context.include.contents"
        private const val PREF_INCLUDE_PREFIX = "context.include.prefix"
        private const val PREF_INCLUDE_SUFFIX = "context.include.suffix"
        private const val PREF_INCLUDE_FILENAME = "context.include.filename"
    }

    /**
     * The display name for this context module.
     */
    override val moduleName: String = "FileContextRetrievalModule"

    /**
     * Collects contextual information from the current file being edited.
     *
     * This method extracts various pieces of context information based on the
     * module's configuration preferences. The collection process is designed
     * to be efficient and respect user privacy and performance settings.
     *
     * ## Collection Process
     * 1. Validates that the module is enabled in user preferences
     * 2. Extracts editor and document information from the request
     * 3. Collects each enabled context element based on preferences
     * 4. Packages the data into properly typed Record entries
     *
     * ## Context Elements
     * - **File Contents**: The complete text content of the current file
     * - **Prefix**: Text before the cursor up to the configured length
     * - **Suffix**: Text after the cursor up to the configured length
     * - **File Name**: The name of the current file
     *
     * @param request The inline completion request containing editor context
     * @return List containing a single [Record] with collected context data,
     *         or empty list if the module is disabled
     * @throws IllegalStateException If required context information is unavailable
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        try {
            val prefState = me.code4me.services.state.getPrefState()
            if (!prefState.enabledModules.contains(getPreferenceId())) {
                LOG.debug("Module $moduleName is disabled, skipping data collection")
                return emptyList()
            }

            val moduleId = getPreferenceId()
            val editor = request.editor
            val document = request.document
            val fileText = document.text
            val caretOffset = editor.caretModel.offset

            val expanded = mutableMapOf<Record.EntryKey, Any>()

            // Collect file contents if enabled
            if (getBooleanPreference(moduleId, PREF_INCLUDE_CONTENTS, true)) {
                val fileContentsKey = Record.key<String>(KEY_FILE_CONTENTS)
                expanded[fileContentsKey] = fileText
                LOG.trace("Collected file contents (${fileText.length} chars)")
            }

            // Collect prefix text if enabled
            if (getBooleanPreference(moduleId, PREF_INCLUDE_PREFIX, true)) {
                val prefixLength = getIntPreference(moduleId, PREF_PREFIX_LENGTH, DEFAULT_PREFIX_LENGTH)
                val start = (caretOffset - prefixLength).coerceAtLeast(0)
                val prefix = fileText.substring(start, caretOffset.coerceAtMost(fileText.length))
                val prefixKey = Record.key<String>(KEY_PREFIX)
                expanded[prefixKey] = prefix
                LOG.trace("Collected prefix text (${prefix.length} chars)")
            }

            // Collect suffix text if enabled
            if (getBooleanPreference(moduleId, PREF_INCLUDE_SUFFIX, true)) {
                val suffixLength = getIntPreference(moduleId, PREF_SUFFIX_LENGTH, DEFAULT_SUFFIX_LENGTH)
                val end = (caretOffset + suffixLength).coerceAtMost(fileText.length)
                val suffix = fileText.substring(caretOffset.coerceAtMost(fileText.length), end)
                val suffixKey = Record.key<String>(KEY_SUFFIX)
                expanded[suffixKey] = suffix
                LOG.trace("Collected suffix text (${suffix.length} chars)")
            }

            // Collect file name if enabled
            if (getBooleanPreference(moduleId, PREF_INCLUDE_FILENAME, true)) {
                val virtualFile = request.file.virtualFile
                val project = request.editor.project
                if (virtualFile != null && project != null) {
                    val projectPath = project.basePath
                    val relativePath =
                        if (projectPath != null && virtualFile.path.startsWith(projectPath)) {
                            virtualFile.path.removePrefix(projectPath).removePrefix("/")
                        } else {
                            virtualFile.name
                        }
                    val fileNameKey = Record.key<String>(KEY_FILE_NAME)
                    expanded[fileNameKey] = relativePath
                    LOG.trace("Collected relative file path: $relativePath")
                } else {
                    LOG.warn("Virtual file not available for file name collection")
                }
            }

            val record = Record(type = Record.Type.CONTEXT, expanded = expanded)
            LOG.debug("Successfully collected ${expanded.size} context elements")
            return listOf(record)
        } catch (e: Exception) {
            LOG.error("Failed to collect file context data", e)
            return emptyList()
        }
    }

    /**
     * Initializes the file context retrieval module.
     *
     * This module requires no special initialization as it operates
     * directly on the editor context provided in completion requests.
     */
    override fun initializeModules() {
        LOG.debug("Initialized $moduleName")
    }

    /**
     * Returns the preference class for this context module.
     *
     * @return [PreferenceClass.CONTEXT] indicating this is a context collection module
     */
    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.CONTEXT
    }

    /**
     * Provides the list of configurable preferences for this module.
     *
     * These preferences allow users to customize:
     * - The length of prefix and suffix text to collect
     * - Which context elements to include in data collection
     * - Performance and privacy trade-offs
     *
     * @return List of [Preference] objects defining module configuration options
     */
    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = PREF_PREFIX_LENGTH,
                type = PreferenceType.INT,
                defaultValue = DEFAULT_PREFIX_LENGTH.toString(),
                limitedDefaultValue = "256",
                displayName = "Prefix Length",
                description =
                    "Number of characters to include before the cursor position. " +
                        "Larger values provide more context but may impact performance.",
            ),
            Preference(
                key = PREF_SUFFIX_LENGTH,
                type = PreferenceType.INT,
                defaultValue = DEFAULT_SUFFIX_LENGTH.toString(),
                limitedDefaultValue = "128",
                displayName = "Suffix Length",
                description =
                    "Number of characters to include after the cursor position. " +
                        "Larger values provide more context but may impact performance.",
            ),
            Preference(
                key = PREF_INCLUDE_CONTENTS,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "false",
                displayName = "Include File Contents",
                description =
                    "Include the complete file content in context data. " +
                        "Disable for large files to improve performance.",
            ),
            Preference(
                key = PREF_INCLUDE_PREFIX,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "true",
                displayName = "Include Prefix",
                description = "Include the text before the cursor position, up to the configured prefix length.",
            ),
            Preference(
                key = PREF_INCLUDE_SUFFIX,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "true",
                displayName = "Include Suffix",
                description = "Include the text after the cursor position, up to the configured suffix length.",
            ),
            Preference(
                key = PREF_INCLUDE_FILENAME,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                limitedDefaultValue = "false",
                displayName = "Include File Name",
                description = "Include the name of the current file in context data.",
            ),
        )
}
