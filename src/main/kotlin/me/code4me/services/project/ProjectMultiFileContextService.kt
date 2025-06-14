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
     */
    fun updateFileContent(
        filePath: String,
        newText: String,
    ): List<FileContextChangeData> {
        val cacheFile = getCacheFile(filePath)

        if (!cacheFile.exists()) {
            savePathMapping(filePath)
            return emptyList()
        }

        val oldText = cacheFile.readText()
        val diffs = computeLineDiffs(oldText, newText)
        return diffs
    }

    /**
     * Writes a snapshot of the file content if one doesn't exist.
     */
    fun saveInitialSnapshotIfMissing(
        filePath: String,
        newText: String,
    ) {
        val cacheFile = getCacheFile(filePath)
        if (!cacheFile.exists()) {
            cacheFile.writeText(newText)
            savePathMapping(filePath)
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

    private fun getCacheFile(filePath: String): File {
        val name = sanitize(filePath)
        return File(contextCacheDir, name)
    }

    private fun sanitize(path: String): String {
        return path
            .replace("_", "__") // escape existing underscores
            .replace(":", "_c_") // colon
            .replace(File.separator, "_s_") // slash/backslash
    }

    private fun savePathMapping(originalPath: String) {
        val name = sanitize(originalPath)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pathMapFile)
        val root = doc.documentElement

        // Avoid duplicate entries
        val existing = doc.getElementsByTagName("file")
        for (i in 0 until existing.length) {
            val el = existing.item(i) as Element
            if (el.getAttribute("name") == name) return
        }

        val fileElement = doc.createElement("file")
        fileElement.setAttribute("name", name)
        val pathElement = doc.createElement("originalPath")
        pathElement.textContent = originalPath
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

    // Todo see if it's worth the overhead of parsing XML every time
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

    fun writeCache(
        filePath: String,
        newText: String,
    ) {
        val cacheFile = getCacheFile(filePath)
        cacheFile.writeText(newText)
    }

    fun clearCache() {
        contextCacheDir.listFiles()?.forEach { it.delete() }
        saveEmptyXml(pathMapFile)
    }
}
