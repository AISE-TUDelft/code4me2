package me.code4me.toolWindow.chatPanelUI

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.componenets.FileTabsComponent
import java.awt.BorderLayout
import javax.swing.BorderFactory

/**
 * Main input panel that orchestrates text input, file management, and controls
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

    private fun showFileSelectionDialog() {
        fileSelectionDialog.show(controlsComponent.getAddFileButton())
    }

    // Public API
    fun clearInput() = textInputComponent.clearText()

    fun addFileTab(file: VirtualFile) = fileTabsComponent.addTab(file)

    fun removeFileTab(file: VirtualFile) = fileTabsComponent.removeTab(file)

    fun updateModelComboBox(models: Array<String>) = controlsComponent.updateModels(models)

    val inputText: String get() = textInputComponent.text
}
