package me.code4me.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestionUpdateManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import me.code4me.services.app.AppService
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.utils.record.aggregateByType
import me.code4me.utils.record.toMap
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class PluginInlineCompletionProvider : DebouncedInlineCompletionProvider() {
    val logger = Logger.getInstance("inlineCompletion")
    private val pluginInsertHandle = PluginInlineCompletionInsertHandler()
    private val pluginSuggestionUpdateManager =
        PluginInlineCompletionSuggestionUpdateManager(super.suggestionUpdateManager)

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        // TODO: implement this to actually collect the context send it to the server and get the response
        // TODO: and then use that to create inline completion suggestion
        logger.info("Generating inline completion suggestion")

        // For testing, return a simple suggestion with some text
        val document = request.editor.document
        val requestId = request.requestId

        // get the module manager given the editor
        val moduleManager = getModuleManager(request.editor.project!!)
        val aggregatedCollectedData =
            moduleManager
                .collectData(request)
                .aggregateByType()
                .mapValues { (_, values) -> values.toMap() }

        val completion =
            service<AppService>()
            .getInlineCompletion(aggregatedCollectedData)

        val mappedCompletions = completion?.completions ?: emptyList()

        return PluginInlineCompletionSuggestion(
            mappedCompletions,
            requestId,
        )
    }

    override val insertHandler: InlineCompletionInsertHandler
        get() = pluginInsertHandle

    override val suggestionUpdateManager: InlineCompletionSuggestionUpdateManager
        get() = pluginSuggestionUpdateManager

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return 250.toDuration(DurationUnit.MILLISECONDS)
    }

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("Code4Me V2")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return true
    }
}
