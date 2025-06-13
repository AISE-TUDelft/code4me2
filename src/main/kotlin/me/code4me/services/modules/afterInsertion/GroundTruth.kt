package me.code4me.services.modules.afterInsertion

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
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

class GroundTruth : PluginModule {
    companion object {
        private val LOG = thisLogger()

        private const val DEFAULT_LEFT_CHARS = 32
        private const val DEFAULT_RIGHT_CHARS = 32
        private const val DEFAULT_CHECKING_INTERVALS = "15, 45, 90"
    }

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
                "Left range of characters",
                "Number of characters to the left of inserted code to consider for ground truth",
            ),
            Preference(
                "rightChars",
                PreferenceType.INT,
                DEFAULT_RIGHT_CHARS.toString(),
                "Right range of characters",
                "Number of characters to the right of inserted code to consider for ground truth",
            ),
            // When LIST preference type is implemented, use that instead of string
            Preference(
                "checkingIntervals",
                PreferenceType.STRING,
                DEFAULT_CHECKING_INTERVALS,
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

        println("initial time: ${System.currentTimeMillis()}")
        println("initial time: ${System.currentTimeMillis()}")
        println("initial time: ${System.currentTimeMillis()}")

        for (interval in checkingIntervals) {
            timer.schedule(delay = interval * 1000L) {
                try {
                    application.runReadAction {
                        if (rangeMarker.isValid) {
                            sendGroundTruthToServer(
                                document,
                                rangeMarker,
                                completionId,
                                project,
                                modelName,
                            )
                        } else {
                            LOG.warn("Range marker is no longer valid after $interval seconds")
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Error while collecting ground truth after $interval seconds", e)
                }
            }
        }
    }

    /**
     * Sends the ground truth data to the server.
     *
     * @param document The document containing the inserted text.
     * @param rangeMarker The range marker for the inserted text.
     * @param completionId The ID of the completion request.
     * @param project The current project.
     * @param modelName The name of the model used for the completion.
     */
    private fun sendGroundTruthToServer(
        document: Document,
        rangeMarker: RangeMarker,
        completionId: UUID,
        project: Project,
        modelName: String,
    ) {
        val moduleId = getPreferenceId()
        val leftChars = getIntPreference(moduleId, "leftTokens", DEFAULT_LEFT_CHARS)
        val rightChars = getIntPreference(moduleId, "rightTokens", DEFAULT_RIGHT_CHARS)

        val startOffset = rangeMarker.startOffset
        val endOffset = rangeMarker.endOffset

        // Get the inserted text
        val insertedText = document.text.substring(rangeMarker.startOffset, rangeMarker.endOffset)

        // Calculate extended range with extra characters
        val documentText = document.text

        // Find left boundary (count leftChars characters to the left)
        val leftBoundary = Math.max(0, startOffset - leftChars)

        // Find right boundary (count rightChars characters to the right)
        val rightBoundary = Math.min(documentText.length, endOffset + rightChars)

        // Get the extended text
        val extendedText = documentText.substring(leftBoundary, rightBoundary)

        // Print the ground truth
        LOG.info("Ground Truth for inserted code:")
        LOG.info("Inserted code range: [$startOffset, $endOffset]")
        LOG.info("Inserted text: $insertedText")
        LOG.info("Extended range: [$leftBoundary, $rightBoundary]")
        LOG.info("Extended text: $extendedText")

        println("Current time: ${System.currentTimeMillis()}")
        println("Current time: ${System.currentTimeMillis()}")
        println("Current time: ${System.currentTimeMillis()}")

        // Also print to standard output for visibility
        println("Ground Truth for inserted code:")
        println("Inserted code range: [$startOffset, $endOffset]")
        println("Inserted text: $insertedText")
        println("Extended range: [$leftBoundary, $rightBoundary]")
        println("Extended text: $extendedText")

        // Send the ground truth to the server
        try {
            // Use a default model ID (1) since we don't have access to the actual model ID
            // The wasAccepted parameter is true since we're in the afterInsertion method
            val response =
                getAppService().submitCompletionFeedback(
                    metaQueryId = completionId,
                    modelId = getConfig().getModelsConfiguration()?.getModelIdByName(modelName) ?: 1, // Default model ID
                    wasAccepted = true, // The completion was accepted
                    groundTruth = extendedText,
                    project = project,
                )

            if (response != null) {
                LOG.info("Ground truth data sent to server successfully")
                println("Acceptance feedback sent to server successfully")
                println("Acceptance feedback sent to server successfully")
                println("Acceptance feedback sent to server successfully")
            } else {
                LOG.warn("Failed to send ground truth data to server")
            }
        } catch (e: Exception) {
            LOG.error("Error sending ground truth data to server", e)
        }
    }
}
