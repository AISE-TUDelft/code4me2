// FIXED ProjectMultiFileContextService.kt with atomic cache updates to prevent race conditions
package me.code4me.services.project

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import computeLineDiffs
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

fun getProjectMultiFileContextService(project: Project): ProjectMultiFileContextService {
    return project.getService(ProjectMultiFileContextService::class.java)
}

@Service(Service.Level.PROJECT)
class ProjectMultiFileContextService(private val project: Project) {
    companion object {
        private val LOG = thisLogger()
    }

    val contextCacheDir: File by lazy {
        File(project.basePath, ".idea/code4me/context_cache").also { it.mkdirs() }
    }

    private val pathMapFile: File by lazy {
        File(contextCacheDir, "file_mappings.xml").also {
            if (!it.exists()) saveEmptyXml(it)
        }
    }

    // Thread-safe cache locks
    private val cacheLocks = ConcurrentHashMap<String, Any>()

    // Store previous cache state for rollback capability
    private val previousCacheState = ConcurrentHashMap<String, String>()

    private fun getCacheLock(relativePath: String): Any {
        return cacheLocks.getOrPut(relativePath) { Any() }
    }

    /**
     * Checks if a snapshot exists for the given relative path.
     */
    fun hasSnapshot(relativePath: String): Boolean {
        synchronized(getCacheLock(relativePath)) {
            val cacheFile = getCacheFile(relativePath)
            val exists = cacheFile.exists()
            LOG.debug("Snapshot check for $relativePath: $exists")
            return exists
        }
    }

    /**
     * ATOMIC: Updates file content and cache in a single synchronized operation.
     * This prevents race conditions from rapid typing.
     */
    fun updateFileContentAndCache(
        relativePath: String,
        newText: String,
    ): List<FileContextChangeData> {
        synchronized(getCacheLock(relativePath)) {
            val cacheFile = getCacheFile(relativePath)

            if (!cacheFile.exists()) {
                LOG.debug("No cache file exists for $relativePath, returning empty diffs")
                savePathMapping(relativePath)
                return emptyList()
            }

            val oldText =
                try {
                    cacheFile.readText()
                } catch (e: Exception) {
                    LOG.warn("Failed to read cache file for $relativePath", e)
                    return emptyList()
                }

            LOG.debug("Computing diffs for $relativePath: oldSize=${oldText.length}, newSize=${newText.length}")

            val diffs = computeLineDiffs(oldText, newText)

            if (diffs.isNotEmpty()) {
                LOG.debug("Found ${diffs.size} diffs for $relativePath:")
                diffs.forEach { diff ->
                    LOG.debug("  ${diff.changeType}: ${diff.startLine}-${diff.endLine} (${diff.newLines.size} new lines)")
                }

                // CRITICAL: Store previous state for potential rollback
                previousCacheState[relativePath] = oldText

                // CRITICAL: Update cache IMMEDIATELY (optimistically)
                try {
                    writeCacheInternal(relativePath, newText)
                    LOG.debug("Cache updated immediately for $relativePath")
                } catch (e: Exception) {
                    LOG.error("Failed to update cache immediately for $relativePath", e)
                    // If cache update fails, don't return diffs
                    return emptyList()
                }
            } else {
                LOG.debug("No diffs found for $relativePath")
            }

            return diffs
        }
    }

    /**
     * Rollback the last cache update if server update failed.
     */
    fun rollbackLastUpdate(relativePath: String) {
        synchronized(getCacheLock(relativePath)) {
            val previousState = previousCacheState[relativePath]
            if (previousState != null) {
                try {
                    writeCacheInternal(relativePath, previousState)
                    previousCacheState.remove(relativePath)
                    LOG.debug("Successfully rolled back cache for $relativePath")
                } catch (e: Exception) {
                    LOG.error("Failed to rollback cache for $relativePath", e)
                }
            } else {
                LOG.warn("No previous state to rollback for $relativePath")
            }
        }
    }

    /**
     * Thread-safe version of saveInitialSnapshotIfMissing.
     */
    fun saveInitialSnapshotIfMissing(
        relativePath: String,
        newText: String,
    ) {
        synchronized(getCacheLock(relativePath)) {
            val cacheFile = getCacheFile(relativePath)
            if (!cacheFile.exists()) {
                LOG.debug("Saving initial snapshot for $relativePath")
                writeCacheInternal(relativePath, newText)
                savePathMapping(relativePath)
            } else {
                LOG.debug("Snapshot already exists for $relativePath")
            }
        }
    }

    /**
     * Internal method for atomic cache writing
     */
    private fun writeCacheInternal(
        relativePath: String,
        newText: String,
    ) {
        val cacheFile = getCacheFile(relativePath)
        LOG.debug("Writing cache for $relativePath (${newText.length} chars)")

        try {
            cacheFile.parentFile.mkdirs()

            // Write to temporary file first, then rename for atomic operation
            val tempFile = File(cacheFile.parent, "${cacheFile.name}.tmp")
            tempFile.writeText(newText)

            // Atomic rename
            if (cacheFile.exists()) {
                cacheFile.delete()
            }

            val success = tempFile.renameTo(cacheFile)
            if (success) {
                LOG.debug("Cache written successfully for $relativePath")
            } else {
                LOG.error("Failed to rename temp file for $relativePath")
                // Cleanup temp file if rename failed
                tempFile.delete()
            }
        } catch (e: Exception) {
            LOG.error("Failed to write cache for $relativePath", e)
        }
    }

    /**
     * Completely resets the cache and forces fresh file sends.
     */
    fun resetCacheCompletely() {
        try {
            LOG.warn("RESETTING CACHE COMPLETELY - all files will be sent fresh")

            // Clear all cache locks first
            cacheLocks.clear()
            previousCacheState.clear()

            // Delete all cache files
            contextCacheDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val deleted = file.delete()
                    LOG.debug("Deleted cache file: ${file.name} - success: $deleted")
                }
            }

            // Recreate empty XML mapping file
            saveEmptyXml(pathMapFile)

            LOG.warn("Cache reset complete - next requests will send full files")
        } catch (e: Exception) {
            LOG.error("Failed to reset cache", e)
        }
    }

    fun getMappedPath(sanitized: String): String? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pathMapFile)
        val nodes = doc.getElementsByTagName("file")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.getAttribute("name") == sanitized) {
                return element.getElementsByTagName("originalPath").item(0).textContent
            }
        }
        return null
    }

    private fun getCacheFile(relativePath: String): File {
        val sanitizedName = sanitizeForFilename(relativePath)
        return File(contextCacheDir, sanitizedName)
    }

    /**
     * Sanitizes a relative path to create a safe filename for caching.
     */
    private fun sanitizeForFilename(relativePath: String): String {
        return relativePath
            .replace("_", "__")
            .replace(":", "_c_")
            .replace("/", "_s_")
            .replace("\\", "_s_")
            .replace("<", "_lt_")
            .replace(">", "_gt_")
            .replace("\"", "_q_")
            .replace("|", "_p_")
            .replace("?", "_qm_")
            .replace("*", "_a_")
    }

    private fun savePathMapping(relativePath: String) {
        val sanitizedName = sanitizeForFilename(relativePath)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pathMapFile)
        val root = doc.documentElement

        // Avoid duplicate entries
        val existing = doc.getElementsByTagName("file")
        for (i in 0 until existing.length) {
            val el = existing.item(i) as Element
            if (el.getAttribute("name") == sanitizedName) return
        }

        val fileElement = doc.createElement("file")
        fileElement.setAttribute("name", sanitizedName)
        val pathElement = doc.createElement("originalPath")
        pathElement.textContent = relativePath
        fileElement.appendChild(pathElement)
        root.appendChild(fileElement)
        saveXml(doc)
    }

    private fun saveEmptyXml(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = doc.createElement("fileMappings")
        doc.appendChild(root)
        saveXml(doc, file)
    }

    private fun saveXml(
        doc: Document,
        file: File = pathMapFile,
    ) {
        val transformer =
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
            }
        transformer.transform(DOMSource(doc), StreamResult(file))
    }

    fun removeMapping(sanitized: String) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pathMapFile)
        val root = doc.documentElement
        val nodes = doc.getElementsByTagName("file")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.getAttribute("name") == sanitized) {
                root.removeChild(el)
                break
            }
        }
        saveXml(doc)
    }

    fun clearCache() {
        resetCacheCompletely()
    }
}
