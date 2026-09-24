package me.code4me.utils.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import javax.swing.Timer

internal fun interface AcpPreparationNotice {
    fun expire()
}

interface AcpPreparationLease {
    /** Clears this attempt's progress, then runs its outcome notification on the UI thread. */
    fun finish(after: () -> Unit = {})

    /** A ready agent completes every overlapping preparation attempt for this project. */
    fun complete(after: () -> Unit = {})
}

interface AcpPreparationIndicator {
    fun acquire(delayMs: Int = 700): AcpPreparationLease
}

fun getAcpPreparationProgress(project: Project): AcpPreparationProgress = project.service()

/** A single waiting balloon shared by automatic and manual preparation in one project. */
@Service(Service.Level.PROJECT)
class AcpPreparationProgress(project: Project) : AcpPreparationIndicator, Disposable {
    private val controller = AcpPreparationProgressController(
        project,
        { action -> ApplicationManager.getApplication().invokeLater(action) },
        { delayMs, action ->
            Timer(delayMs) { action() }.apply {
                isRepeats = false
                start()
            }
        },
        ::showDefaultNotice,
    )

    override fun acquire(delayMs: Int): AcpPreparationLease = controller.acquire(delayMs)

    fun cancelAll() = controller.cancelAll()

    override fun dispose() = controller.dispose()
}

internal class AcpPreparationProgressController(
    private val project: Project,
    private val runOnUi: (() -> Unit) -> Unit,
    private val scheduleOnUi: (Int, () -> Unit) -> Unit,
    private val showNotice: (Project) -> AcpPreparationNotice,
) : AcpPreparationIndicator, Disposable {
    private val lock = Any()
    private val activeLeases = mutableSetOf<Long>()
    private var nextLease = 0L
    private var displayGeneration = 0L
    private var outcomeEpoch = 0L
    private var notice: AcpPreparationNotice? = null
    private val pendingExpiry = mutableListOf<AcpPreparationNotice>()
    private var disposed = false

    override fun acquire(delayMs: Int): AcpPreparationLease {
        val acquisition = synchronized(lock) {
            if (disposed) return Lease(-1)
            val first = activeLeases.isEmpty()
            val id = ++nextLease
            activeLeases += id
            if (first) displayGeneration++
            Acquisition(id, displayGeneration, first, !first && delayMs <= 0 && notice == null)
        }
        if (acquisition.schedule) {
            runOnUi {
                if (delayMs <= 0) showIfCurrent(acquisition.generation)
                else scheduleOnUi(delayMs) { showIfCurrent(acquisition.generation) }
            }
        } else if (acquisition.expedite) {
            runOnUi { showIfCurrent(acquisition.generation) }
        }
        return Lease(acquisition.leaseId)
    }

    fun cancelAll() = clearAll(dispose = false)

    override fun dispose() = clearAll(dispose = true)

    private fun clearAll(dispose: Boolean) {
        val expire = synchronized(lock) {
            if (disposed) return
            if (dispose) disposed = true
            activeLeases.clear()
            displayGeneration++
            outcomeEpoch++
            notice?.let(pendingExpiry::add)
            notice = null
            pendingExpiry.isNotEmpty()
        }
        if (expire) runOnUi { synchronized(lock) { expirePendingLocked() } }
    }

    private fun release(id: Long, all: Boolean, after: () -> Unit) {
        val epoch = synchronized(lock) {
            if (!activeLeases.remove(id)) return
            if (all) {
                activeLeases.clear()
                outcomeEpoch++
            }
            if (activeLeases.isEmpty()) {
                displayGeneration++
                notice?.let(pendingExpiry::add)
                notice = null
            }
            outcomeEpoch
        }
        runOnUi {
            synchronized(lock) {
                expirePendingLocked()
                if (!disposed && !project.isDisposed && outcomeEpoch == epoch) after()
            }
        }
    }

    private fun showIfCurrent(expectedGeneration: Long) {
        synchronized(lock) {
            expirePendingLocked()
            if (disposed || project.isDisposed || activeLeases.isEmpty() ||
                displayGeneration != expectedGeneration || notice != null
            ) return
            notice = showNotice(project)
        }
    }

    /** UI callbacks drain old balloons before showing a replacement or a result. */
    private fun expirePendingLocked() {
        val old = pendingExpiry.toList()
        pendingExpiry.clear()
        old.forEach { runCatching { it.expire() } }
    }

    private inner class Lease(private val id: Long) : AcpPreparationLease {
        override fun finish(after: () -> Unit) = release(id, all = false, after)

        override fun complete(after: () -> Unit) = release(id, all = true, after)
    }

    private data class Acquisition(val leaseId: Long, val generation: Long, val schedule: Boolean, val expedite: Boolean)
}

private fun showDefaultNotice(project: Project): AcpPreparationNotice {
    val notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code4Me ACP preparation")
            .createNotification(
                "Preparing Code4Me agent",
                "Code4Me is preparing the ACP agent and adding it to the JetBrains agent list. You can keep working while it finishes.",
                NotificationType.INFORMATION,
            )
    notification.notify(project)
    return AcpPreparationNotice { notification.expire() }
}
