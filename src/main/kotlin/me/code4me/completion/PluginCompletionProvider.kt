package me.code4me.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.code4me.utils.completion.prioritize

class PluginCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        results: CompletionResultSet
    ) {
        // TODO: implement the actual logic for invoking and showing, here I've simply added
        // a placeholder for the completion
        CoroutineScope(Dispatchers.IO).launch {
            val element = LookupElementBuilder
                .create("Code4Me V2 Plugin Completion")
                .withPresentableText("Code4Me V2 Plugin Completion")
                .withTypeText("Code4Me V2")
            results.addElement(
                element.prioritize()
            )
        }
    }
}