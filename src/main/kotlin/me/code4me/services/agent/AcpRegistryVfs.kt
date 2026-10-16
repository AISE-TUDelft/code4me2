package me.code4me.services.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Keeps the IDE's virtual file system in step with `~/.jetbrains/acp.json`.
 *
 * The registry is written with NIO, which the VFS does not see. JetBrains AI
 * Assistant reloads its agent list from VFS change events, so a file the plugin
 * created for the first time was not picked up until it changed again or the
 * IDE restarted. Loading the parent directory before the write and refreshing
 * the file after it yields the create/change event the assistant listens for.
 *
 * Best effort: outside an IDE (unit tests) or on any failure nothing happens.
 *
 * Never touches VFS on the caller's thread (see [onPooledThread]).
 */
internal object AcpRegistryVfs {
    /**
     * Load the registry's directory into VFS so a later create is reported as an
     * event. Runs on a pooled thread: callers may hold locks the EDT waits on.
     */
    fun beforeWrite(registryPath: Path?) {
        val parent = registryPath?.parent ?: return
        onPooledThread { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent)?.children }
    }

    /** Refresh the directory and the written file so listeners see the write. */
    fun afterWrite(registryPath: Path?) {
        if (registryPath == null) return
        val parent = registryPath.parent
        onPooledThread {
            val fs = LocalFileSystem.getInstance()
            parent?.let { fs.refreshAndFindFileByNioFile(it) }
            fs.refreshAndFindFileByNioFile(registryPath)
        }
    }

    /**
     * Every VFS access happens here, never on the caller's thread. Research
     * activation calls this while holding the session manager lock; a VFS read
     * or refresh on that thread waits for the EDT, and the EDT (disposing the
     * project inside a write action) waits for that lock: the IDE froze.
     */
    private fun onPooledThread(action: () -> Unit) {
        try {
            val application = ApplicationManager.getApplication() ?: return
            application.executeOnPooledThread {
                try {
                    action()
                } catch (_: Throwable) {
                    // Never let a VFS problem affect the registry or the IDE.
                }
            }
        } catch (_: Throwable) {
            // Outside a running IDE (unit tests) there is nothing to refresh.
        }
    }
}
