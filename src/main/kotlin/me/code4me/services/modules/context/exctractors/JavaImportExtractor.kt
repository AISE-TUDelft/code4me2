package me.code4me.services.modules.context.exctractors

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class JavaImportExtractor : ImportExtractor {
    override fun supports(psiFile: PsiFile): Boolean {
        return psiFile.language.id == "JAVA"
    }

    override fun extractImports(psiFile: PsiFile): List<String> {
        val importStatements = mutableListOf<String>()

        val elements = PsiTreeUtil.getChildrenOfType(psiFile, PsiElement::class.java) ?: return emptyList()

        for (element in elements) {
            if (element.text.startsWith("import ")) {
                importStatements.add(element.text.trim())
            }
        }

        return importStatements
    }
}
