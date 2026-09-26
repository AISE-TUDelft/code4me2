package me.code4me.research.status

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineScope
import me.code4me.research.session.ResearchSessionService
import java.awt.Component
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reads the participant status view defensively (Issue 10).
 *
 * A research exception must never break the status bar or the editor, so any
 * failure degrades to [ParticipantStatusPresentation.unavailable].
 */
internal object ParticipantStatusSupport {
    fun of(project: Project): ParticipantStatusView =
        try {
            ParticipantStatusPresentation.of(ResearchSessionService.getInstance(project).state())
        } catch (_: Exception) {
            ParticipantStatusPresentation.unavailable()
        }
}

/**
 * Participant status-bar widget factory (Issue 10).
 *
 * Registered as a `statusBarWidgetFactory`. It exposes the current
 * [ParticipantStatusView] (state + typed reason + recovery action) in the IDE
 * status bar. It reveals no secret, path, or study internal, and degrades to an
 * "unavailable" presentation rather than failing if the research service errors.
 */
class ResearchStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "Code4Me Research Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun isEnabledByDefault(): Boolean = true

    override fun isConfigurable(): Boolean = false

    override fun createWidget(project: Project): StatusBarWidget = ResearchStatusBarWidget(project)

    override fun createWidget(
        project: Project,
        scope: CoroutineScope,
    ): StatusBarWidget = ResearchStatusBarWidget(project)

    companion object {
        const val WIDGET_ID: String = "me.code4me.research.status"
    }
}

/**
 * The status-bar widget itself; text/tooltip are recomputed on each presentation.
 *
 * The research state changes off the UI thread (activation, revocation, a new
 * login) and nothing pushes those changes to the status bar, so the widget asks
 * the status bar to repaint it periodically. Reading the state is cheap.
 */
class ResearchStatusBarWidget(private val project: Project) : StatusBarWidget {
    @Volatile private var statusBar: StatusBar? = null
    @Volatile private var refresh: ScheduledFuture<*>? = null

    override fun ID(): String = ResearchStatusBarWidgetFactory.WIDGET_ID

    @Suppress("DEPRECATION")
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = presentation()

    // StatusBarWidget.getPresentation() delegates to this deprecated variant, so
    // it must be overridden for the status bar to show any text.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getPresentation(type: StatusBarWidget.PlatformType): StatusBarWidget.WidgetPresentation = presentation()

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        statusBar.updateWidget(ID())
        refresh?.cancel(false)
        refresh =
            AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                {
                    val bar = this.statusBar
                    if (bar != null && !project.isDisposed) {
                        ApplicationManager.getApplication().invokeLater(
                            { if (!project.isDisposed) bar.updateWidget(ID()) },
                            ModalityState.any(),
                        )
                    }
                },
                REFRESH_SECONDS,
                REFRESH_SECONDS,
                TimeUnit.SECONDS,
            )
    }

    override fun dispose() {
        refresh?.cancel(false)
        refresh = null
        statusBar = null
    }

    private fun presentation(): StatusBarWidget.WidgetPresentation =
        object : StatusBarWidget.TextPresentation {
            override fun getText(): String = ParticipantStatusSupport.of(project).headline

            override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

            override fun getTooltipText(): String = ParticipantStatusSupport.of(project).tooltip
        }

    private companion object {
        const val REFRESH_SECONDS: Long = 5L
    }
}
