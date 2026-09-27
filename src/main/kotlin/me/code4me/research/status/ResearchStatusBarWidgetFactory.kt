package me.code4me.research.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import kotlinx.coroutines.CoroutineScope
import me.code4me.research.session.ResearchSessionService
import java.awt.Component
import javax.swing.Timer

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

/** The status-bar widget itself; refreshes when the participant state changes. */
class ResearchStatusBarWidget(private val project: Project) : StatusBarWidget {
    private var refreshTimer: Timer? = null

    override fun ID(): String = ResearchStatusBarWidgetFactory.WIDGET_ID

    @Suppress("DEPRECATION")
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = presentation()

    // StatusBarWidget.getPresentation() delegates to this deprecated variant, so
    // it must be overridden for the status bar to show any text.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getPresentation(type: StatusBarWidget.PlatformType): StatusBarWidget.WidgetPresentation = presentation()

    override fun install(statusBar: StatusBar) {
        refreshTimer?.stop()
        var displayed = ParticipantStatusSupport.of(project)
        statusBar.updateWidget(ID())
        // IntelliJ caches TextPresentation.getText() until updateWidget() is
        // called. The tooltip is queried afresh, which otherwise leaves the
        // visible label stale after asynchronous study activation or teardown.
        refreshTimer =
            Timer(2_000) {
                if (project.isDisposed) {
                    refreshTimer?.stop()
                } else {
                    val current = ParticipantStatusSupport.of(project)
                    if (current != displayed) {
                        displayed = current
                        statusBar.updateWidget(ID())
                    }
                }
            }.also { it.start() }
    }

    override fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }

    private fun presentation(): StatusBarWidget.WidgetPresentation =
        object : StatusBarWidget.TextPresentation {
            override fun getText(): String = ParticipantStatusSupport.of(project).headline

            override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

            override fun getTooltipText(): String = ParticipantStatusSupport.of(project).tooltip
        }
}
