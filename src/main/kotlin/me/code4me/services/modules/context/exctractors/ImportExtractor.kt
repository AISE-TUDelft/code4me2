package me.code4me.services.modules.context.exctractors

import com.intellij.psi.PsiFile

interface ImportExtractor {
    fun supports(psiFile: PsiFile): Boolean

    fun extractImports(psiFile: PsiFile): List<String>
}
