package me.code4me.services.project

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import computeLineDiffs
import me.code4me.services.modules.context.MultiFileContextRetrievalModule.FileContextChangeData
import java.io.File

fun getProjectMultiFileContextService(project: Project): ProjectMultiFileContextService {
    return project.getService(ProjectMultiFileContextService::class.java)
}

@Service(Service.Level.PROJECT)
class ProjectMultiFileContextService(private val project: Project) {
    private val contextCacheDir: File by lazy {
        File(project.basePath, ".idea/code4me/context_cache").also { it.mkdirs() }
    }

    /**
     * Returns the content of the cached file.
     */
    fun getContent(filePath: String): String? {
        val cacheFile = getCacheFile(filePath)
        return if (cacheFile.exists()) cacheFile.readText() else null
    }

    /**
     * Checks if a cache entry exists for the given file.
     */
    fun hasFile(filePath: String): Boolean {
        return getCacheFile(filePath).exists()
    }

    /**
     * Updates file content in the cache and returns diffs if any.
     */
    fun updateFileContent(
        filePath: String,
        newText: String,
    ): List<FileContextChangeData> {
        val cacheFile = getCacheFile(filePath)

        // First-time snapshot: no diff
        if (!cacheFile.exists()) {
            cacheFile.writeText(newText)
            return emptyList()
        }

        val oldText = cacheFile.readText()
        val diffs = computeLineDiffs(oldText, newText)

        // Only update if there are changes
        if (diffs.isNotEmpty()) {
            cacheFile.writeText(newText)
        }

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
        }
    }

    private fun getCacheFile(filePath: String): File {
        val sanitizedName = filePath.replace(File.separator, "_").replace(":", "_")
        return File(contextCacheDir, sanitizedName)
    }
    fun getHashCacheFile(): File {
        return File(contextCacheDir, "file_hashes.xml")
    }
}
