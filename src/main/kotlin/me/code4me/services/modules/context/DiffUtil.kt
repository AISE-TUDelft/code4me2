// BLOCK-AWARE DiffUtil.kt - Detects section deletions and handles them as single operations
import me.code4me.api.generated.model.ContextChangeType
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import me.code4me.api.generated.model.FileContextChangeData as ApiChangeData
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData as InternalChangeData

fun InternalChangeData.toApiModel(): ApiChangeData {
    val apiChangeType =
        when (this.changeType.lowercase()) {
            "insert" -> ContextChangeType.insert
            "delete" -> ContextChangeType.remove
            "replace" -> ContextChangeType.update
            "update" -> ContextChangeType.update
            else -> ContextChangeType.update
        }

    return ApiChangeData(
        changeType = apiChangeType,
        startLine = this.startLine,
        endLine = this.endLine,
        newLines = this.newLines,
    )
}

/**
 * Block-aware diff that detects section deletions and handles them cleanly.
 */
fun computeLineDiffs(
    oldText: String,
    newText: String,
): List<FileContextChangeData> {
    if (oldText == newText) {
        return emptyList()
    }

    val oldLines =
        oldText.replace("\r\n", "\n").split("\n").let { lines ->
            // Remove trailing empty line if the text doesn't end with newline
            if (lines.isNotEmpty() && lines.last().isEmpty() && !oldText.endsWith("\n")) {
                lines.dropLast(1)
            } else {
                lines
            }
        }

    val newLines =
        newText.replace("\r\n", "\n").split("\n").let { lines ->
            // Remove trailing empty line if the text doesn't end with newline
            if (lines.isNotEmpty() && lines.last().isEmpty() && !newText.endsWith("\n")) {
                lines.dropLast(1)
            } else {
                lines
            }
        }

    // Handle complete file operations
    when {
        oldLines.isEmpty() && newLines.isNotEmpty() -> {
            return listOf(
                FileContextChangeData(
                    changeType = "insert",
                    startLine = 0,
                    endLine = newLines.size - 1,
                    newLines = newLines,
                ),
            )
        }
        oldLines.isNotEmpty() && newLines.isEmpty() -> {
            return listOf(
                FileContextChangeData(
                    changeType = "delete",
                    startLine = 0,
                    endLine = oldLines.size - 1,
                    newLines = emptyList(),
                ),
            )
        }
    }

    // Find the first and last matching lines to establish boundaries
    val frontMatches = findFrontMatches(oldLines, newLines)
    val backMatches = findBackMatches(oldLines, newLines, frontMatches)

    val oldStart = frontMatches
    val oldEnd = oldLines.size - backMatches
    val newStart = frontMatches
    val newEnd = newLines.size - backMatches

    // If everything matches, no changes
    if (oldStart >= oldEnd && newStart >= newEnd) {
        return emptyList()
    }

    val changes = mutableListOf<FileContextChangeData>()

    // Determine the type of change for the middle section
    val oldMiddle = if (oldStart < oldEnd) oldLines.subList(oldStart, oldEnd) else emptyList()
    val newMiddle = if (newStart < newEnd) newLines.subList(newStart, newEnd) else emptyList()

    when {
        oldMiddle.isNotEmpty() && newMiddle.isEmpty() -> {
            // Pure deletion
            changes.add(
                FileContextChangeData(
                    changeType = "delete",
                    startLine = oldStart,
                    endLine = oldEnd - 1,
                    newLines = emptyList(),
                ),
            )
        }
        oldMiddle.isEmpty() && newMiddle.isNotEmpty() -> {
            // Pure insertion
            changes.add(
                FileContextChangeData(
                    changeType = "insert",
                    startLine = oldStart,
                    endLine = oldStart,
                    newLines = newMiddle,
                ),
            )
        }
        oldMiddle.isNotEmpty() && newMiddle.isNotEmpty() -> {
            // Replacement
            changes.add(
                FileContextChangeData(
                    changeType = "replace",
                    startLine = oldStart,
                    endLine = oldEnd - 1,
                    newLines = newMiddle,
                ),
            )
        }
    }

    return changes
}

/**
 * Find how many lines match at the beginning
 */
private fun findFrontMatches(
    oldLines: List<String>,
    newLines: List<String>,
): Int {
    val minSize = minOf(oldLines.size, newLines.size)
    var matches = 0

    while (matches < minSize && oldLines[matches] == newLines[matches]) {
        matches++
    }

    return matches
}

/**
 * Find how many lines match at the end
 */
private fun findBackMatches(
    oldLines: List<String>,
    newLines: List<String>,
    frontMatches: Int,
): Int {
    val oldRemaining = oldLines.size - frontMatches
    val newRemaining = newLines.size - frontMatches
    val maxBackMatches = minOf(oldRemaining, newRemaining)

    var matches = 0

    while (matches < maxBackMatches) {
        val oldIndex = oldLines.size - 1 - matches
        val newIndex = newLines.size - 1 - matches

        if (oldLines[oldIndex] == newLines[newIndex]) {
            matches++
        } else {
            break
        }
    }

    return matches
}
