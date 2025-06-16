package me.code4me.services.project

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import computeLineDiffs
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
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
    val contextCacheDir: File by lazy {
        File(project.basePath, ".idea/code4me/context_cache").also { it.mkdirs() }
    }

    private val pathMapFile: File by lazy {
        File(contextCacheDir, "file_mappings.xml").also {
            if (!it.exists()) saveEmptyXml(it)
        }
    }

    /**
     * Updates file content in the cache and returns diffs if any.
     * @param relativePath The relative path from project root (e.g., "src/main/App.kt")
     */
    fun updateFileContent(
        relativePath: String,
        newText: String,
    ): List<FileContextChangeData> {
        val cacheFile = getCacheFile(relativePath)

        if (!cacheFile.exists()) {
            savePathMapping(relativePath)
            return emptyList()
        }

        val oldText = cacheFile.readText()
        val diffs = computeLineDiffs(oldText, newText)
        return diffs
    }

    /**
     * Writes a snapshot of the file content if one doesn't exist.
     * @param relativePath The relative path from project root (e.g., "src/main/App.kt")
     */
    fun saveInitialSnapshotIfMissing(
        relativePath: String,
        newText: String,
    ) {
        val cacheFile = getCacheFile(relativePath)
        if (!cacheFile.exists()) {
            cacheFile.parentFile.mkdirs()
            cacheFile.writeText(newText)
            savePathMapping(relativePath)
        }
    }

    /**
     * Writes content to cache.
     * @param relativePath The relative path from project root (e.g., "src/main/App.kt")
     */
    fun writeCache(
        relativePath: String,
        newText: String,
    ) {
        val cacheFile = getCacheFile(relativePath)
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(newText)
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
     * This flattens the directory structure into a single filename.
     *
     * Example: "src/main/java/App.kt" -> "src_s_main_s_java_s_App.kt"
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

    /**
     * Legacy sanitize method - keeping for backward compatibility with existing cache
     */
    private fun sanitize(path: String): String {
        return sanitizeForFilename(path)
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
        pathElement.textContent = relativePath // Store the original relative path
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
        contextCacheDir.listFiles()?.forEach { it.delete() }
        saveEmptyXml(pathMapFile)
    }
}
