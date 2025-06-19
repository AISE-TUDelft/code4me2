package me.code4me.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns

/**
 * Completion contributor that integrates Code4Me's AI-powered code completion with IntelliJ's completion system.
 *
 * Registers the PluginCompletionProvider to handle basic completion requests for all PSI elements,
 * enabling AI-generated code suggestions to appear in IntelliJ's standard completion popup.
 */
class PluginCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            PluginCompletionProvider(),
        )
    }
}
