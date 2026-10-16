package me.code4me.services.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.io.File

/** Snapshot of the IDE editor state at the moment an agent request is made. */
data class ActiveFileContext(val path: String?, val selectedText: String?)

/**
 * Captures the IDE-side context that gets attached to agent inference calls.
 *
 * TODO: this is still the minimal placeholder inherited from the original prototype — it only
 * reports the active file and its selection. Richer enrichment (open tabs, diagnostics, VCS
 * state, the multi-file context the completion path already builds) is not implemented.
 */
class AgentContextProvider {
    private val LOG = thisLogger()

    /**
     * Capture the active editor's file path and current selection, for telemetry
     * enrichment. FileEditorManager.selectedEditor / selectedTextEditor are EDT-only,
     * so when called from the local proxy's HTTP worker thread we hop onto the EDT
     * (invokeAndWait) to read them — otherwise they return null. Returns nulls on any
     * failure rather than throwing, so it can never block a request.
     */
    fun activeFileContext(project: Project): ActiveFileContext {
        var result = ActiveFileContext(null, null)
        val read =
            Runnable {
                result =
                    try {
                        if (project.isDisposed) {
                            ActiveFileContext(null, null)
                        } else {
                            val fem = FileEditorManager.getInstance(project)
                            val path = fem.selectedEditor?.file?.path
                            val selection =
                                fem.selectedTextEditor?.selectionModel?.selectedText
                                    ?.takeIf { it.isNotBlank() }
                            ActiveFileContext(path, selection)
                        }
                    } catch (e: Exception) {
                        LOG.warn("[AgentContextProvider] failed to read active file context", e)
                        ActiveFileContext(null, null)
                    }
            }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) read.run() else app.invokeAndWait(read)
        return result
    }

    /**
     * Writes a human-readable context digest to `<project>/.code4me/agent_context.md`.
     *
     * Third-party ACP agents have no way to query the IDE for editor state, so this file is the
     * handoff surface: the agent can read it as an ordinary project file.
     */
    fun writeContext(project: Project) {
        val basePath = project.basePath ?: return
        val contextDir = File(basePath, ".code4me")
        contextDir.mkdirs()
        val contextFile = File(contextDir, "agent_context.md")

        val context = activeFileContext(project)

        val sb = StringBuilder()
        sb.appendLine("# Agent Context")
        sb.appendLine("**Project root:** $basePath")
        context.path?.let { sb.appendLine("**Active file:** $it") }
        context.selectedText?.let {
            sb.appendLine("**Selected text:**")
            sb.appendLine("```")
            sb.appendLine(it)
            sb.appendLine("```")
        }

        try {
            contextFile.writeText(sb.toString())
            LOG.info("[AgentContextProvider] Context written to ${contextFile.absolutePath}")
        } catch (e: Exception) {
            LOG.warn("[AgentContextProvider] Failed to write ${contextFile.absolutePath}", e)
        }
    }
}
