import me.code4me.api.generated.model.ContextChangeType
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import me.code4me.api.generated.model.FileContextChangeData as ApiChangeData
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData as InternalChangeData

/**
 * Converts internal file context change data to the API model format.
 *
 * Maps internal change type strings to the corresponding API enum values and
 * creates an API-compatible change data object for transmission to the server.
 *
 * @return ApiChangeData object ready for API transmission
 */
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
 * Computes line-based differences between two text strings with block-aware change detection.
 *
 * Analyzes two text versions to identify insertions, deletions, and replacements at the line level.
 * Uses boundary matching to minimize the change scope and handles complete file operations
 * (empty to content, content to empty) as special cases. The algorithm finds matching lines
 * at the beginning and end to isolate the actual changed region.
 *
 * @param oldText The original text content
 * @param newText The updated text content
 * @return List of FileContextChangeData objects describing the changes, or empty list if no changes
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
                    endLine = newLines.size,
                    newLines = newLines,
                ),
            )
        }
        oldLines.isNotEmpty() && newLines.isEmpty() -> {
            return listOf(
                FileContextChangeData(
                    changeType = "delete",
                    startLine = 0,
                    endLine = oldLines.size,
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
                    endLine = oldEnd,
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
                    endLine = oldEnd,
                    newLines = newMiddle,
                ),
            )
        }
    }

    return changes
}

/**
 * Finds how many lines match at the beginning of both text versions.
 *
 * Compares lines from the start of both lists until a mismatch is found,
 * helping to identify the unchanged prefix that can be excluded from diffs.
 *
 * @param oldLines Lines from the original text
 * @param newLines Lines from the updated text
 * @return Number of matching lines at the beginning
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
 * Finds how many lines match at the end of both text versions.
 *
 * Compares lines from the end of both lists working backwards until a mismatch
 * is found, helping to identify the unchanged suffix that can be excluded from diffs.
 * Takes into account the front matches to avoid double-counting overlapping regions.
 *
 * @param oldLines Lines from the original text
 * @param newLines Lines from the updated text
 * @param frontMatches Number of lines already matched from the beginning
 * @return Number of matching lines at the end
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
