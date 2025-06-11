package me.code4me.utils.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement

fun LookupElement.prioritize(): LookupElement {
    return PrioritizedLookupElement.withPriority(
        this,
        Double.MAX_VALUE,
    )
}
