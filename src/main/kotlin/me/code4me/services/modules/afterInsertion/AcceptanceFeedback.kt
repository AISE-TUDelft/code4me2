package me.code4me.services.modules.afterInsertion

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.diagnostic.thisLogger
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
import me.code4me.utils.record.Record
import java.util.UUID

/**
 * Module for collecting acceptance feedback after inline completion insertions.
 * This module is responsible for sending feedback to the server
 * when a user accepts an inline completion suggestion.
 *
 * [afterInsertion] runs inside the platform read action (see
 * `ModuleManager.afterInsertion`), so it only gathers the completion identity
 * there; the HTTP call runs on [Dispatchers.IO] with a bounded timeout, never
 * under the read lock, so a slow or unreachable server cannot stall typing.
 */
class AcceptanceFeedback : PluginModule {
    companion object {
        private val LOG = thisLogger()

        /** Upper bound for one feedback submission, including connect and read. */
        const val SUBMIT_TIMEOUT_MS: Long = 10_000L
    }

    /** Off-read-action submission scope; one failed submission never cancels another. */
    private val submissions = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val moduleName: String
        get() = "AcceptanceFeedback"

    override fun collectData(request: InlineCompletionRequest): List<Record> {
        // This module does not collect data, so we return an empty list
        return emptyList()
    }

    override fun initializeModules() {
        // Initialization logic for the AcceptanceFeedback module can be added here if needed
        // For now, we are not implementing any specific initialization logic
    }

    override fun getPreferenceList(): List<Preference> {
        // This module does not have specific preferences, so we return an empty list
        return emptyList()
    }

    override fun getPreferenceClass(): PreferenceClass {
        return PreferenceClass.AFTER_INSERTION
    }

    /**
     * This method is called after an inline completion insertion.
     * It sends a request to server indicating that this code suggestion has been accepted and inserted.
     * It collects the completion ID and model name from the inserted elements
     * (inside the caller's read action) and submits the acceptance feedback to
     * the server off the read action.
     */
    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
        val inserted = elements.filterIsInstance<PluginInlineCompletionElement>().firstOrNull()
        val completionId = inserted?.metaQueryId
        val modelName = inserted?.completionModel

        if (completionId == null || modelName == null) {
            LOG.warn("No completion ID or model name found in inserted elements, skipping acceptance feedback")
            return
        }

        val project = environment.editor.project
        if (project == null) {
            LOG.warn("No project found for editor, skipping acceptance feedback")
            return
        }

        submitOffReadAction(completionId, modelName, project)
    }

    private fun submitOffReadAction(
        completionId: UUID,
        modelName: String,
        project: Project,
    ) {
        submissions.launch {
            try {
                val response =
                    withTimeout(SUBMIT_TIMEOUT_MS) {
                        runInterruptible {
                            // The wasAccepted parameter is true since we're in the afterInsertion method
                            getAppService().submitCompletionFeedback(
                                metaQueryId = completionId,
                                modelId = getConfig().getModelsConfiguration()?.getModelIdByName(modelName) ?: 1,
                                wasAccepted = true,
                                groundTruth = null,
                                project = project,
                            )
                        }
                    }
                if (response != null) {
                    LOG.info("Acceptance feedback sent to server successfully")
                } else {
                    LOG.warn("Failed to send acceptance feedback to server")
                }
            } catch (e: TimeoutCancellationException) {
                LOG.warn("Acceptance feedback timed out after ${SUBMIT_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                LOG.warn("Error sending acceptance feedback to server", e)
            }
        }
    }
}
