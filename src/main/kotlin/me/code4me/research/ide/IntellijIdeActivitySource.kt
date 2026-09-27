package me.code4me.research.ide

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-scoped IDE activity source built exclusively on public IntelliJ
 * Platform APIs (Issue 10).
 *
 * It observes file open/close, document change, file save, and run/debug
 * execution start/finish and translates each into a metadata-only
 * [IdeActivitySignal]. Editor text, paths, prompts, diffs, and command output
 * are never read: only file extension, file-type name, the executor id, a
 * bounded changed-length count, the run phase, and the bounded exit status
 * cross the boundary. The collector remains responsible for the metadata
 * allowlist and for dropping signals before an active session exists.
 *
 * The source is deliberately tolerant: a subscriber (research) failure is
 * caught and logged, never propagated into IDE event dispatch, so ordinary
 * Code4Me behaviour cannot be broken by research collection.
 */
class IntellijIdeActivitySource(private val project: Project) : IdeActivitySource, Disposable {
    private val log = thisLogger()
    private val subscribers = CopyOnWriteArrayList<(IdeActivitySignal) -> Unit>()

    /** Opaque project key: a platform-provided hash, never a raw path handed to telemetry. */
    private val projectKey: String = project.locationHash.ifBlank { project.name }

    private val connection = project.messageBus.connect(this)

    // Documents may be re-bound to several editors; register each identity once.
    private val registeredDocuments: MutableSet<Document> =
        Collections.newSetFromMap(IdentityHashMap<Document, Boolean>())

    private val documentListener: DocumentListener =
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                publishDocumentChange(event)
            }
        }

    init {
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) {
                    publishFileSignal(IdeActivityKind.OPENED.wire, file)
                }

                override fun fileClosed(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) {
                    publishFileSignal(IdeActivityKind.CLOSED.wire, file)
                }
            },
        )

        connection.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val file = FileDocumentManager.getInstance().getFile(document) ?: return
                    // The topic is application-wide: a save in another open
                    // project must not be recorded as this project's activity.
                    if (!belongsToProject(file)) return
                    publishFileSignal(IdeActivityKind.SAVED.wire, file)
                }
            },
        )

        connection.subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarted(
                    executorId: String,
                    env: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) {
                    publish(IntellijIdeActivityMapper.runSignal(projectKey, executorId, IdeRunPhase.STARTED))
                }

                override fun processTerminated(
                    executorId: String,
                    env: ExecutionEnvironment,
                    handler: ProcessHandler,
                    exitCode: Int,
                ) {
                    publish(IntellijIdeActivityMapper.runSignal(projectKey, executorId, IdeRunPhase.FINISHED, exitCode))
                }
            },
        )

        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    // Editors of every open project are announced here; only
                    // this project's documents are this project's activity.
                    if (event.editor.project !== project) return
                    registerDocument(event.editor.document)
                }
            },
            this,
        )
    }

    private fun belongsToProject(file: VirtualFile): Boolean =
        runCatching {
            com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(file)
            }
        }.getOrDefault(true)

    /** Subscribe [callback]; it receives every subsequent raw signal. */
    override fun onActivity(callback: (IdeActivitySignal) -> Unit) {
        val firstSubscriber = subscribers.isEmpty()
        subscribers.add(callback)
        // The project is open for as long as the source is attached. The collector
        // drops this until a manifest-scoped session is active, so an unscoped
        // lifecycle signal is never buffered.
        if (firstSubscriber) publish(IntellijIdeActivityMapper.lifecycleSignal(PROJECT_OPENED, projectKey))
    }

    override fun dispose() {
        publish(IntellijIdeActivityMapper.lifecycleSignal(PROJECT_CLOSED, projectKey))
        subscribers.clear()
        registeredDocuments.clear()
    }

    private fun registerDocument(document: Document) {
        if (!registeredDocuments.add(document)) return
        runCatching { document.addDocumentListener(documentListener, this) }
            .onFailure { log.debug("IDE research document listener was not attached", it) }
    }

    private fun publishDocumentChange(event: DocumentEvent) {
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (file.isDirectory) return
        // Only the bounded new length is read; the changed fragment is never touched.
        publish(
            IntellijIdeActivityMapper.documentSignal(
                projectKey = projectKey,
                extension = file.extension,
                language = file.fileType.name,
                changedCharacters = event.newLength,
            ),
        )
    }

    private fun publishFileSignal(
        kind: String,
        file: VirtualFile,
    ) {
        if (file.isDirectory) return
        publish(
            IntellijIdeActivityMapper.fileSignal(
                kind = kind,
                projectKey = projectKey,
                extension = file.extension,
                language = file.fileType.name,
            ),
        )
    }

    private fun publish(signal: IdeActivitySignal) {
        if (subscribers.isEmpty()) return
        for (subscriber in subscribers) {
            // A research failure must never escape into the IDE event dispatch.
            runCatching { subscriber(signal) }
                .onFailure { log.debug("IDE research signal was dropped", it) }
        }
    }

    companion object {
        const val PROJECT_OPENED = "project.opened"
        const val PROJECT_CLOSED = "project.closed"

        fun getInstance(project: Project): IntellijIdeActivitySource = project.service()
    }
}
