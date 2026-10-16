package me.code4me.research.status

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import java.awt.BorderLayout
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Participant editor banner (Issue 10).
 *
 * Registered as an `editorNotificationProvider`. It shows a restrained banner
 * only for states that need the participant's attention (paused/blocked/failed/
 * recovering), so an inactive or fully-active study never nags in the editor and
 * ordinary, non-research plugin behaviour is unaffected. The banner text is the
 * same non-identifying [ParticipantStatusView] used by the status bar.
 */
class ResearchEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?> {
        val view = ParticipantStatusSupport.of(project)
        if (!ParticipantStatusPresentation.shouldNotify(view)) {
            return Function { null }
        }
        return Function { buildBanner(view) }
    }

    private fun buildBanner(view: ParticipantStatusView): JComponent {
        val panel = JPanel(BorderLayout())
        val message = view.actionHint ?: view.headline
        val label = JLabel("<html><b>${view.headline}</b> — $message</html>")
        label.border = EmptyBorder(4, 8, 4, 8)
        panel.add(label, BorderLayout.CENTER)
        return panel
    }
}
