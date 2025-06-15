import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import me.code4me.api.generated.model.ContextChangeType
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import me.code4me.api.generated.model.FileContextChangeData as ApiChangeData
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData as InternalChangeData

fun InternalChangeData.toApiModel(): ApiChangeData {
    return ApiChangeData(
        changeType = ContextChangeType.decode(this.changeType) ?: ContextChangeType.update,
        startLine = this.startLine,
        endLine = this.endLine,
        newLines = this.newLines,
    )
}

fun computeLineDiffs(
    oldText: String,
    newText: String,
): List<FileContextChangeData> {
    val result = mutableListOf<FileContextChangeData>()

    ApplicationManager.getApplication().runReadAction {
        val comparisonManager = ComparisonManager.getInstance()
        val policy = ComparisonPolicy.DEFAULT
        val indicator = EmptyProgressIndicator()

        try {
            val fragments: List<LineFragment> =
                comparisonManager.compareLines(
                    oldText,
                    newText,
                    policy,
                    indicator,
                )

            val newLines = newText.lines()

            for (fragment in fragments) {
                val type =
                    when {
                        fragment.startLine1 == fragment.endLine1 -> "insert"
                        fragment.startLine2 == fragment.endLine2 -> "delete"
                        else -> "replace"
                    }

                val changedLines = newLines.subList(fragment.startLine2, fragment.endLine2)

                result.add(
                    FileContextChangeData(
                        changeType = type,
                        startLine = fragment.startLine1,
                        endLine = fragment.endLine1,
                        newLines = changedLines,
                    ),
                )
            }
        } catch (e: ProcessCanceledException) {
            throw e // How to handle files too long
        }
    }

    return result
}
