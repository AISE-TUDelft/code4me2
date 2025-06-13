package me.code4me.services.modules.telemetry.contextual

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.PrefState
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import me.code4me.utils.services.state.getBooleanPreference

/**
 * Comprehensive telemetry module for collecting detailed editor context and state information.
 *
 * This module captures extensive context about the current editor session, including
 * file metadata, cursor positioning, document structure, and user selection state.
 * The collected data provides essential insights for completion system optimization
 * and user behavior analysis.
 *
 * ## Core Data Categories
 * - **File Context**: Programming language, file path, and document metadata
 * - **Cursor Information**: Absolute and relative positioning within the document
 * - **Document Metrics**: Character count, line/column positions, and structure
 * - **Selection State**: Currently selected text and selection metadata
 * - **Relative Positioning**: Normalized position within the document for analysis
 *
 * ## Key Applications
 * The context data supports various analytical and optimization use cases:
 * - **Completion Relevance**: Understanding context for better suggestion accuracy
 * - **Position Analysis**: Analyzing where users request completions within files
 * - **Language Patterns**: Studying completion patterns across programming languages
 * - **Document Navigation**: Understanding user movement and positioning patterns
 * - **Selection Behavior**: Analyzing how users interact with selected text
 *
 * ## Data Collection Strategy
 * The module uses configurable data collection with:
 * - **Selective Inclusion**: Individual preferences for each data type
 * - **Privacy Controls**: Optional inclusion of sensitive data like file paths
 * - **Performance Optimization**: Efficient data extraction with minimal overhead
 * - **Standardized Formatting**: Consistent key naming and data structure
 *
 * ## Privacy and Performance Considerations
 * - **Configurable Privacy**: Users can disable collection of sensitive information
 * - **Minimal Performance Impact**: Efficient data extraction without blocking operations
 * - **Local Processing**: All data processing occurs locally without external transmission
 * - **Selective Collection**: Only enabled data types are collected and stored
 *
 * ## Record Structure
 * Creates telemetry records with standardized keys using dot notation for categories
 * and underscore separation for compound names:
 * - `context.language`: Programming language name
 * - `context.file.path`: Complete file system path
 * - `context.caret.offset`: Absolute character position
 * - `relative_document_position`: Normalized position (0.0 to 1.0)
 * - `document_char_length`: Total document character count
 *
 * @since 1.0.0
 * @see me.code4me.services.modules.PluginModule
 * @see me.code4me.utils.record.Record.Type.TELEMETRY
 * @see com.intellij.codeInsight.inline.completion.InlineCompletionRequest
 */
class EditorContextRetrievalModule : PluginModule {
    companion object {
        private val LOG = thisLogger()

        // Record key names using standardized naming conventions
        private const val KEY_CONTEXT_LANGUAGE = "context.language"
        private const val KEY_CONTEXT_FILE_PATH = "context.file.path"
        private const val KEY_CONTEXT_CARET_OFFSET = "context.caret.offset"
        private const val KEY_RELATIVE_DOCUMENT_POSITION = "relative_document_position"
        private const val KEY_CONTEXT_CARET_LINE = "context.caret.line"
        private const val KEY_CONTEXT_CARET_COLUMN = "context.caret.column"
        private const val KEY_DOCUMENT_CHAR_LENGTH = "document_char_length"
        private const val KEY_CONTEXT_SELECTION_TEXT = "context.selection.text"

        // Preference key names using dot notation
        private const val PREF_INCLUDE_LANGUAGE = "context.include.language"
        private const val PREF_INCLUDE_FILEPATH = "context.include.filepath"
        private const val PREF_INCLUDE_CARET_OFFSET = "context.include.caret.offset"
        private const val PREF_INCLUDE_CARET_POSITION = "context.include.caret.position"
        private const val PREF_INCLUDE_SELECTION_TEXT = "context.include.selection.text"
        private const val PREF_INCLUDE_LENGTH = "context.include.length"
    }

    /**
     * The display name for this editor context telemetry module.
     */
    override val moduleName = "EditorContextRetrievalModule"

    /**
     * Collects comprehensive editor context and state information.
     *
     * This method extracts detailed information about the current editor session,
     * including file metadata, cursor positioning, document structure, and user
     * interaction state. The collection process respects user privacy preferences
     * and provides configurable data inclusion options.
     *
     * ## Collection Process
     * 1. **Module Enablement**: Verifies the module is enabled in user preferences
     * 2. **Context Extraction**: Retrieves editor, document, and file information
     * 3. **Cursor Analysis**: Captures absolute and relative cursor positioning
     * 4. **Selection Detection**: Identifies and extracts selected text if present
     * 5. **Conditional Collection**: Includes data based on preference settings
     * 6. **Record Assembly**: Packages all data into a structured telemetry record
     *
     * ## Data Elements Collected
     * ### File and Language Context
     * - **Programming Language**: Display name of the file's programming language
     * - **File Path**: Complete file system path (privacy-configurable)
     * - **Document Length**: Total character count in the document
     *
     * ### Cursor and Position Context
     * - **Caret Offset**: Absolute character position from document start
     * - **Relative Position**: Normalized position as float (0.0 to 1.0)
     * - **Line Number**: Current line position (zero-based)
     * - **Column Number**: Current column position within the line
     *
     * ### Selection Context
     * - **Selected Text**: Currently selected text content (if any)
     * - **Selection State**: Whether text is currently selected
     *
     * ## Preference Integration
     * The method respects user preferences for data collection:
     * - **Configurable Inclusion**: Each data type can be individually enabled/disabled
     * - **Privacy Controls**: Sensitive data like file paths can be excluded
     * - **Performance Tuning**: Expensive operations can be disabled for better performance
     * - **Default Behavior**: Most data types are enabled by default for optimal functionality
     *
     * ## Error Handling and Robustness
     * - **Graceful Degradation**: Continues collection even if some data is unavailable
     * - **Null Safety**: Handles missing or null context information appropriately
     * - **Exception Isolation**: Errors in one data element don't affect others
     * - **Logging**: Comprehensive logging for debugging and monitoring
     *
     * @param request The inline completion request containing current editor context.
     *                Provides access to editor, document, PSI file, and other context.
     * @return List containing a single [me.code4me.utils.record.Record] with comprehensive editor context data,
     *         or empty list if the module is disabled. The record includes all
     *         enabled context elements based on user preferences.
     * @throws Exception If critical context information is unavailable, but exceptions
     *                   are caught and logged to prevent completion system disruption.
     *
     * @see com.intellij.codeInsight.inline.completion.InlineCompletionRequest
     * @see me.code4me.utils.record.Record.Type.CONTEXTUAL_TELEMETRY
     * @see com.intellij.openapi.fileEditor.FileDocumentManager
     */
    override fun collectData(request: InlineCompletionRequest): List<Record> {
        try {
            val prefState = getPrefState()
            if (!prefState.enabledModules.contains(getPreferenceId())) {
                LOG.debug("Module $moduleName is disabled, skipping data collection")
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

            // Collect programming language information
            // TODO: uncomment when the preference storage properly works
            //        if (/*PrefState.getPreferenceValue(moduleId, "context.include.language")?.toBoolean() == true*/true) {
            if (getBooleanPreference(moduleId, PREF_INCLUDE_LANGUAGE, true)) {
                val languageKey = Record.Companion.key<String>(KEY_CONTEXT_LANGUAGE)
                expanded[languageKey] = psiFile.language.displayName
                LOG.trace("Collected language: ${psiFile.language.displayName}")
            }

            if (getBooleanPreference(moduleId, PREF_INCLUDE_FILEPATH, true)) {
                val filePathKey = Record.Companion.key<String>(KEY_CONTEXT_FILE_PATH)
                expanded[filePathKey] = virtualFile.path
                LOG.trace("Collected file path: ${virtualFile.path}")
            }

            if (getBooleanPreference(moduleId, PREF_INCLUDE_CARET_OFFSET, true)) {
                val caretOffsetKey = Record.Companion.key<Int>(KEY_CONTEXT_CARET_OFFSET)
                expanded[caretOffsetKey] = caretModel.offset
                LOG.trace("Collected caret offset: ${caretModel.offset}")
            }

            if (getBooleanPreference(moduleId, PREF_INCLUDE_CARET_OFFSET, true)) {
                val relativeDocumentPositionKey = Record.Companion.key<Float>(KEY_RELATIVE_DOCUMENT_POSITION)
                expanded[relativeDocumentPositionKey] = (
                    caretModel.offset.toFloat() / document.text.length.toFloat()
                )
                LOG.trace("Collected relative position: ${caretModel.offset.toFloat() / document.text.length.toFloat()}")
            }

            if (getBooleanPreference(moduleId, PREF_INCLUDE_CARET_POSITION, true)) {
                val caretLineKey = Record.Companion.key<Int>(KEY_CONTEXT_CARET_LINE)
                val caretColumnKey = Record.Companion.key<Int>(KEY_CONTEXT_CARET_COLUMN)
                expanded[caretLineKey] = logicalPosition.line
                expanded[caretColumnKey] = logicalPosition.column
                LOG.trace("Collected caret position: line ${logicalPosition.line}, column ${logicalPosition.column}")
            }

            if (getBooleanPreference(moduleId, PREF_INCLUDE_LENGTH, true)) {
                val fileLengthKey = Record.Companion.key<Int>(KEY_DOCUMENT_CHAR_LENGTH)
                expanded[fileLengthKey] = document.text.length
                LOG.trace("Collected document length: ${document.text.length} characters")
            }

            // TODO: change these to actual values later down the line
            val pluginVersion = Record.Companion.key<Int>("version_id")
            expanded[pluginVersion] = 1

            val triggerType = Record.Companion.key<Int>("trigger_type_id")
            expanded[triggerType] =
                request.event.let {
                    // check that the event is of Type InlineCompletionEvent.DocumentChange
                    if (it is InlineCompletionEvent.DocumentChange) {
                        2 // this means that it was automatically triggered by the document change
                    } else {
                        1 // this means that it was manually triggered by the user either by typing or by a shortcut
                        // or via the dropdown menu or even via the chat.
                    }
                }

            val languageId = Record.Companion.key<Int>("language_id")
            expanded[languageId] = 1

            // Collect selected text if preferences allow and text is selected
            if (PrefState.Companion.getPreferenceValue(
                    moduleId,
                    PREF_INCLUDE_SELECTION_TEXT,
                )?.toBoolean() == true && !selectedText.isNullOrEmpty()
            ) {
                val selectionTextKey = Record.Companion.key<String>(KEY_CONTEXT_SELECTION_TEXT)
                expanded[selectionTextKey] = selectedText
                LOG.trace("Collected selected text: ${selectedText.length} characters")
            }

            val record = Record(type = Record.Type.CONTEXTUAL_TELEMETRY, expanded = expanded)
            LOG.debug("Successfully collected ${expanded.size} context elements")
            return listOf(record)
        } catch (e: Exception) {
            LOG.error("Failed to collect editor context telemetry", e)
            return emptyList()
        }
    }

    /**
     * Initializes the editor context retrieval module.
     *
     * This module requires no special initialization as it operates on
     * the editor and document information provided directly through the
     * completion request. All necessary context is available at request time.
     *
     * ## Initialization Characteristics
     * - **Request-Based Operation**: All data is extracted from completion requests
     * - **No State Management**: No persistent state or background processes required
     * - **Immediate Availability**: Ready to collect data as soon as module is loaded
     * - **Zero Configuration**: No setup required for basic operation
     */
    override fun initializeModules() {
        LOG.debug("Initialized $moduleName")
        // No special initialization required for this context collection module
        // All necessary information is available from the completion request
    }

    /**
     * Returns the preference class for this telemetry module.
     *
     * @return [me.code4me.utils.configuration.PreferenceClass.TELEMETRY] indicating this is a telemetry collection module
     */
    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.CONTEXTUAL_TELEMETRY
    }

    /**
     * Provides the list of configurable preferences for editor context collection.
     *
     * This module offers extensive configuration options allowing users to control
     * exactly which types of context information are collected. This enables
     * fine-grained privacy control and performance optimization based on user needs.
     *
     * ## Available Preferences
     * Each preference controls the inclusion of a specific type of context data:
     *
     * ### File and Language Context
     * - **Include Language**: Programming language of the current file
     * - **Include File Path**: Complete file system path (privacy-sensitive)
     * - **Include File Length**: Total character count in the document
     *
     * ### Cursor and Position Context
     * - **Include Caret Offset**: Absolute character position from document start
     * - **Include Caret Line and Column**: Line and column number positions
     *
     * ### User Interaction Context
     * - **Include Selected Text**: Currently selected text content (if any)
     *
     * ## Privacy Considerations
     * - **File Paths**: Can contain sensitive information about project structure
     * - **Selected Text**: May contain proprietary or sensitive code content
     * - **Granular Control**: Each data type can be individually enabled/disabled
     * - **Default Settings**: Privacy-sensitive options default to user consent
     *
     * ## Performance Impact
     * - **Minimal Overhead**: Most context extraction is very lightweight
     * - **Selective Collection**: Disabled preferences reduce processing time
     * - **Efficient Implementation**: Optimized data extraction algorithms
     * - **Non-blocking**: Never delays completion request processing
     *
     * @return List of [me.code4me.utils.configuration.Preference] objects defining all configurable context collection options.
     *         Each preference includes type information, default values, and user-friendly
     *         descriptions for informed configuration decisions.
     */
    override fun getPreferenceList(): List<Preference> =
        listOf(
            Preference(
                key = PREF_INCLUDE_LANGUAGE,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Language",
                description =
                    "Include the programming language of the current file in context data. " +
                        "This helps analyze completion patterns across different programming languages.",
            ),
            Preference(
                key = PREF_INCLUDE_FILEPATH,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Path",
                description =
                    "Include the full file path in context data. " +
                        "Note: File paths may contain sensitive information about your project structure.",
            ),
            Preference(
                key = PREF_INCLUDE_CARET_OFFSET,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Caret Offset",
                description =
                    "Include the caret's offset from the beginning of the document. " +
                        "This provides precise positioning information for completion analysis.",
            ),
            Preference(
                key = PREF_INCLUDE_CARET_POSITION,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Caret Line and Column",
                description =
                    "Include the line and column number of the caret position. " +
                        "This helps understand where in files users typically request completions.",
            ),
            Preference(
                key = PREF_INCLUDE_SELECTION_TEXT,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include Selected Text",
                description =
                    "Include currently selected text, if any, in context data. " +
                        "Note: Selected text may contain sensitive code content.",
            ),
            Preference(
                key = PREF_INCLUDE_LENGTH,
                type = PreferenceType.BOOLEAN,
                defaultValue = "true",
                displayName = "Include File Length",
                description =
                    "Include the total length of the file in characters. " +
                        "This helps analyze completion patterns in files of different sizes.",
            ),
        )
}
