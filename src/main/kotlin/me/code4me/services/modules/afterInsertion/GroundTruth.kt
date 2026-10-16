package me.code4me.services.modules.afterInsertion

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import me.code4me.completion.PluginInlineCompletionElement
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.record.Record
import me.code4me.utils.services.state.getIntPreference
import me.code4me.utils.services.state.getTextualPreference
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.schedule

/**
 * GroundTruth module collects ground truth data after inline code completion insertion.
 * It sends the ground truth data to the server at specified intervals
 * after the insertion of inline completion elements.
 * This module does not collect data during the insertion,
 * but rather focuses on the ground truth
 * after the code has been inserted into the editor.
 *
 * It allows users to specify how many characters to consider
 * to the left and right of the inserted code,
 * as well as the intervals at which to check for ground truth after insertion.
 *
 * This module is part of the after insertion modules.
 *
 * Document text is read only inside a read action; the HTTP submission runs on
 * [Dispatchers.IO] with a bounded timeout, never under the read lock. The
 * participant's source text is never written to the IDE log (lengths only).
 */
class GroundTruth : PluginModule {
    companion object {
        private val LOG = thisLogger()

        private const val DEFAULT_LEFT_CHARS = 32
        private const val DEFAULT_RIGHT_CHARS = 32
        private const val DEFAULT_CHECKING_INTERVALS = "15, 45, 90"

        /** Upper bound for one ground-truth submission, including connect and read. */
        const val SUBMIT_TIMEOUT_MS: Long = 10_000L
    }

    /** Off-read-action submission scope; one failed submission never cancels another. */
    private val submissions = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the read action gathers for one interval; the network call consumes it later. */
    private class GroundTruthSnapshot(
        val insertedLength: Int,
        val extendedText: String,
        val insertedRange: IntRange,
        val extendedRange: IntRange,
    )

    override val moduleName: String
        get() = "GroundTruth"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        // This module does not collect data, so we return an empty list
        return emptyList()
    }

    override fun initializeModules() {
        // Initialization logic for the GroundTruth module can be added here if needed
        // For now, we are not implementing any specific initialization logic
    }

    /**
     * Returns a list of preferences for this module.
     * These preferences can be used to configure the behavior of the module.
     *
     * They allow users to specify how many characters to consider
     * to the left and right of the inserted code,
     * as well as the intervals at which to check for ground truth after insertion.
     */
    override fun getPreferenceList(): List<Preference> {
        return listOf(
            Preference(
                "leftChars",
                PreferenceType.INT,
                DEFAULT_LEFT_CHARS.toString(),
                DEFAULT_LEFT_CHARS.toString(),
                "Left range of characters",
                "Number of characters to the left of inserted code to consider for ground truth",
            ),
            Preference(
                "rightChars",
                PreferenceType.INT,
                DEFAULT_RIGHT_CHARS.toString(),
                DEFAULT_RIGHT_CHARS.toString(),
                "Right range of characters",
                "Number of characters to the right of inserted code to consider for ground truth",
            ),
            // When LIST preference type is implemented, use that instead of string
            Preference(
                "checkingIntervals",
                PreferenceType.STRING,
                DEFAULT_CHECKING_INTERVALS,
                "",
                "Checking intervals",
                "Comma separated list of intervals in seconds to check for ground truth after insertion (e.g., 15, 45, 90)",
            ),
        )
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.AFTER_INSERTION
    }

    /**
     * This method is called after an inline completion element has been inserted.
     * It collects ground truth data based on the inserted element and sends it to the server.
     *
     * It sends the ground truth on certain intervals after the insertion,
     * which can be specified in the plugin preferences.
     */
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
        if (elements.isEmpty()) {
            LOG.debug("No elements were inserted, skipping ground truth collection")
            return
        }

        // getting the id of the inserted element
        // check if it is assigniable to a PluginInlineCompletionElement
        val completionId =
            elements.filterIsInstance<PluginInlineCompletionElement>()
                .firstOrNull() ?. let { el -> (el as PluginInlineCompletionElement).metaQueryId }

        val modelName =
            elements.filterIsInstance<PluginInlineCompletionElement>()
                .firstOrNull()?.completionModel

        if (completionId == null || modelName == null) {
            LOG.warn("No completion ID or model name found in inserted elements, skipping ground truth collection")
            return
        }

        val editor = environment.editor
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val project = editor.project

        if (project == null) {
            LOG.warn("No project found for editor, skipping ground truth collection")
            return
        }

        // Get the inserted text from the first element
        val insertedText = elements.first().text

        // Create a range marker for the inserted text
        val startOffset = caretOffset - insertedText.length
        val endOffset = caretOffset

        if (startOffset < 0 || endOffset > document.textLength) {
            LOG.warn("Invalid range for inserted text: [$startOffset, $endOffset], document length: ${document.textLength}")
            return
        }

        val rangeMarker = document.createRangeMarker(startOffset, endOffset)

        val moduleId = getPreferenceId()
        val checkingIntervals =
            getTextualPreference(moduleId, "checkingIntervals", DEFAULT_CHECKING_INTERVALS)
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }

        val application = ApplicationManager.getApplication()
        val timer = Timer("GroundTruthTimer", true)

        for (interval in checkingIntervals) {
            timer.schedule(delay = interval * 1000L) {
                try {
                    // Only the document read happens under the read lock; the
                    // network call below runs off it with a timeout.
                    val snapshot =
                        application.runReadAction<GroundTruthSnapshot?> {
                            if (rangeMarker.isValid) {
                                snapshotGroundTruth(document, rangeMarker)
                            } else {
                                LOG.warn("Range marker is no longer valid after $interval seconds")
                                null
                            }
                        }
                    if (snapshot != null) {
                        sendGroundTruthToServer(snapshot, completionId, project, modelName)
                    }
                } catch (e: Exception) {
                    LOG.warn("Error while collecting ground truth after $interval seconds", e)
                }
            }
        }
    }

    /**
     * Read the inserted text and its surrounding window. Must run inside a read
     * action. Returns lengths/ranges plus the extended text for the server; the
     * text itself is never logged.
     */
    private fun snapshotGroundTruth(
        document: Document,
        rangeMarker: RangeMarker,
    ): GroundTruthSnapshot {
        val moduleId = getPreferenceId()
        // Preference keys must match getPreferenceList() ("leftChars"/"rightChars"),
        // otherwise user configuration is ignored and defaults are always used.
        val leftChars = getIntPreference(moduleId, "leftChars", DEFAULT_LEFT_CHARS)
        val rightChars = getIntPreference(moduleId, "rightChars", DEFAULT_RIGHT_CHARS)

        val startOffset = rangeMarker.startOffset
        val endOffset = rangeMarker.endOffset
        val documentText = document.text

        // Find left boundary (count leftChars characters to the left)
        val leftBoundary = Math.max(0, startOffset - leftChars)

        // Find right boundary (count rightChars characters to the right)
        val rightBoundary = Math.min(documentText.length, endOffset + rightChars)

        return GroundTruthSnapshot(
            insertedLength = endOffset - startOffset,
            extendedText = documentText.substring(leftBoundary, rightBoundary),
            insertedRange = startOffset until endOffset,
            extendedRange = leftBoundary until rightBoundary,
        )
    }

    /**
     * Sends the ground truth data to the server on [Dispatchers.IO] with a
     * bounded timeout; never under a read action.
     *
     * @param snapshot The text window gathered inside the read action.
     * @param completionId The ID of the completion request.
     * @param project The current project.
     * @param modelName The name of the model used for the completion.
     */
    private fun sendGroundTruthToServer(
        snapshot: GroundTruthSnapshot,
        completionId: UUID,
        project: Project,
        modelName: String,
    ) {
        // Lengths and offsets only: participant source text never reaches idea.log.
        LOG.info(
            "Ground truth for inserted code: inserted range ${snapshot.insertedRange} " +
                "(${snapshot.insertedLength} chars), extended range ${snapshot.extendedRange} " +
                "(${snapshot.extendedText.length} chars)",
        )

        submissions.launch {
            try {
                val response =
                    withTimeout(SUBMIT_TIMEOUT_MS) {
                        runInterruptible {
                            // Use a default model ID (1) since we don't have access to the actual model ID
                            // The wasAccepted parameter is true since we're in the afterInsertion method
                            getAppService().submitCompletionFeedback(
                                metaQueryId = completionId,
                                modelId = getConfig().getModelsConfiguration()?.getModelIdByName(modelName) ?: 1,
                                wasAccepted = true,
                                groundTruth = snapshot.extendedText,
                                project = project,
                            )
                        }
                    }

                if (response != null) {
                    LOG.info("Ground truth data sent to server successfully")
                } else {
                    LOG.warn("Failed to send ground truth data to server")
                }
            } catch (e: TimeoutCancellationException) {
                LOG.warn("Ground truth submission timed out after ${SUBMIT_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                LOG.warn("Error sending ground truth data to server", e)
            }
        }
    }
}
