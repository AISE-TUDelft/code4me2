package me.code4me.services.modules.afterInsertion

import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.diagnostic.thisLogger
import me.code4me.completion.PluginInlineCompletionElement
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceClass
import me.code4me.utils.record.Record

class AcceptanceFeedback : PluginModule {
    companion object {
        private val LOG = thisLogger()
    }

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

    override fun afterInsertion(
        environment: InlineCompletionInsertEnvironment,
        elements: List<InlineCompletionElement>,
    ) {
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
        val project = editor.project

        if (project == null) {
            LOG.warn("No project found for editor, skipping ground truth collection")
            return
        }

        // Send the ground truth to the server
        try {
            // Use a default model ID (1) since we don't have access to the actual model ID
            // The wasAccepted parameter is true since we're in the afterInsertion method
            val response =
                getAppService().submitCompletionFeedback(
                    metaQueryId = completionId,
                    modelId = getConfig().getModelsConfiguration()?.getModelIdByName(modelName) ?: 1, // Default model ID
                    wasAccepted = true, // The completion was accepted
                    groundTruth = null,
                    project = project,
                )

            if (response != null) {
                LOG.info("Acceptance feedback sent to server successfully")
                println("Acceptance feedback sent to server successfully")
                println("Acceptance feedback sent to server successfully")
                println("Acceptance feedback sent to server successfully")
            } else {
                LOG.warn("Failed to send acceptance feedback to server")
            }
        } catch (e: Exception) {
            LOG.error("Error sending acceptance feedback to server", e)
        }
    }
}
