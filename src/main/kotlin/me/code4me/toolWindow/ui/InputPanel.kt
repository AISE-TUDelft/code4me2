package me.code4me.toolWindow.chatPanelUI

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.ui.componenets.ControlsComponent
import me.code4me.toolWindow.ui.componenets.FileSelectionDialog
import me.code4me.toolWindow.ui.componenets.FileTabsComponent
import java.awt.BorderLayout
import javax.swing.BorderFactory

/**
 * Main input panel that orchestrates:
 * - Text input
 * - File attachment tabs
 * - Control buttons (send, web toggle, model selector)
 *
 * Acts as the bridge between user actions and logic handled by the surrounding tool window.
 *
 * @param project Current IntelliJ project instance.
 * @param onSend Callback triggered when the send button or Enter key is pressed.
 * @param onWebToggle Callback triggered when the web toggle is enabled/disabled.
 * @param onFileClose Callback triggered when a file tab's close button is clicked.
 * @param onFileSelected Callback triggered when a file is selected from the file dialog.
 */
class InputPanel(
    private val project: Project,
    private val onSend: () -> Unit,
    private val onWebToggle: (Boolean) -> Unit,
    private val onFileClose: (VirtualFile) -> Unit,
    private val onFileSelected: (VirtualFile) -> Unit,
) : JBPanel<InputPanel>(BorderLayout()) {
    private val textInputComponent = TextInputComponent(onSend)
    private val fileTabsComponent = FileTabsComponent(onFileClose)
    private val controlsComponent =
        ControlsComponent(
            onWebToggle = onWebToggle,
            onFileAdd = { showFileSelectionDialog() },
            onSend = onSend,
        )

    private val fileSelectionDialog = FileSelectionDialog(project, onFileSelected)

    init {
        setupLayout()
        setupStyling()
    }

    private fun setupLayout() {
        border = JBUI.Borders.empty(8)
        layout = BorderLayout(0, 8)

        add(createMainContainer(), BorderLayout.CENTER)
    }

    private fun setupStyling() {
        background = JBColor.background()
        isOpaque = true
    }

    /**
     * Assembles the core vertical structure containing:
     * - Text input at the top
     * - File tab list in the middle
     * - Control buttons at the bottom
     *
     * @return A styled and bordered panel with vertical layout.
     */
    private fun createMainContainer() =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 2, true),
                    JBUI.Borders.empty(4),
                )
            background = JBColor.background()

            add(textInputComponent, BorderLayout.NORTH)
            add(fileTabsComponent, BorderLayout.CENTER)
            add(controlsComponent, BorderLayout.SOUTH)
        }

    /**
     * Opens the file selection popup dialog anchored to the "Add File" button.
     * Filters out files that are already added as tabs.
     */
    private fun showFileSelectionDialog() {
        fileSelectionDialog.excludedFiles = fileTabsComponent.getAllOpenFiles()
        fileSelectionDialog.show(controlsComponent.getAddFileButton())
    }

    // Public API
    fun clearInput() = textInputComponent.clearText()

    fun addFileTab(file: VirtualFile) = fileTabsComponent.addTab(file)

    fun removeFileTab(file: VirtualFile) = fileTabsComponent.removeTab(file)

    fun updateModelComboBox(models: Array<String>) = controlsComponent.updateModels(models)

    fun getSelectedModel(): String? = controlsComponent.getSelectedModel()

    val inputText: String get() = textInputComponent.text
}
