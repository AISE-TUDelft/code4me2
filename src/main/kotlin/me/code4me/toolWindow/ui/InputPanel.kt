package me.code4me.toolWindow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.toolWindow.ui.components.ControlsComponent
import me.code4me.toolWindow.ui.components.FileSelectionDialog
import me.code4me.toolWindow.ui.components.FileTabsComponent
import me.code4me.toolWindow.ui.components.TextInputComponent
import java.awt.*
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

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

    // Make the main container a field so we can update its border
    private val mainContainer = createMainContainer()

    init {
        setupLayout()
        setupStyling()
        setupListeners()
    }

    private fun setupLayout() {
        border = JBUI.Borders.empty(8)
        layout = BorderLayout(0, 8)
        add(mainContainer, BorderLayout.CENTER)
    }

    private fun setupStyling() {
        background = JBColor.background()
        isOpaque = true
        mainContainer.isOpaque = true
    }

    private fun setupListeners() {
        val accentColor = UIManager.getColor("Button.select") ?: JBColor.BLUE
        val defaultBorder =
            BorderFactory.createCompoundBorder(
                RoundedBorder(JBColor.border(), 2, 12),
                JBUI.Borders.empty(4),
            )

        textInputComponent.onFocus = {
            mainContainer.border =
                BorderFactory.createCompoundBorder(
                    RoundedBorder(accentColor, 2, 12),
                    JBUI.Borders.empty(4),
                )
        }

        textInputComponent.onBlur = {
            mainContainer.border = defaultBorder
        }
    }

    private fun createMainContainer() =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 2, true),
                    JBUI.Borders.empty(4),
                )
//            background = JBColor.background()
            border =
                BorderFactory.createCompoundBorder(
                    RoundedBorder(JBColor.border(), 2, 12),
                    JBUI.Borders.empty(4),
                )

            add(textInputComponent, BorderLayout.NORTH)
            add(fileTabsComponent, BorderLayout.CENTER)
            add(controlsComponent, BorderLayout.SOUTH)
        }

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

class RoundedBorder(
    private val color: Color,
    private val thickness: Int = 2,
    private val arc: Int = 16,
) : AbstractBorder() {
    override fun paintBorder(
        c: Component,
        g: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val g2 = g.create() as Graphics2D
        g2.color = color
        g2.stroke = BasicStroke(thickness.toFloat())
        g2.drawRoundRect(x + thickness / 2, y + thickness / 2, width - thickness, height - thickness, arc, arc)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component) = Insets(thickness, thickness, thickness, thickness)

    override fun getBorderInsets(
        c: Component,
        insets: Insets,
    ): Insets {
        insets.set(thickness, thickness, thickness, thickness)
        return insets
    }
}
