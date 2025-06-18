package me.code4me.chatWindow.components.inputPanel

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import me.code4me.chatWindow.components.inputPanel.components.FileSelectionDialog
import me.code4me.chatWindow.components.inputPanel.components.FileTabsComponent
import me.code4me.chatWindow.components.inputPanel.components.InputControlsComponent
import me.code4me.chatWindow.components.inputPanel.components.TextInputComponent
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

/**
 * Main input panel for the chat interface containing text input, file tabs, and controls.
 *
 * Combines multiple input components into a unified interface with rounded border styling
 * and focus effects. Handles text input, file management, model selection, and various
 * chat interaction modes including editing and generation states.
 *
 * @param project The IntelliJ project context for file operations
 * @param onSend Callback invoked when a message is sent
 * @param onWebToggle Callback invoked when web search toggle changes
 * @param onFileClose Callback invoked when a file tab is closed
 * @param onFileSelected Callback invoked when a file is selected from the dialog
 */
class InputPanel(
    project: Project,
    onSend: () -> Unit,
    onWebToggle: (Boolean) -> Unit,
    onFileClose: (VirtualFile) -> Unit,
    onFileSelected: (VirtualFile) -> Unit,
) : JBPanel<InputPanel>(BorderLayout()) {
    /**
     * Text input component with auto-resizing functionality.
     */
    private val textInputComponent = TextInputComponent(onSend)

    /**
     * Component for displaying and managing selected file tabs.
     */
    private val fileTabsComponent = FileTabsComponent(onFileClose)

    /**
     * Bottom controls containing buttons and model selector.
     */
    private val inputControlsComponent =
        InputControlsComponent(
            onWebToggle = onWebToggle,
            onFileAdd = { showFileSelectionDialog() },
            onSend = onSend,
        )

    /**
     * Dialog for selecting files to add to the chat context.
     */
    private val fileSelectionDialog = FileSelectionDialog(project, onFileSelected)

    /**
     * Main container with rounded border that wraps all input components.
     */
    private val mainContainer = createMainContainer()

    init {
        setupLayout()
        setupStyling()
        setupListeners()
    }

    /**
     * Configures the overall layout and spacing of the input panel.
     */
    private fun setupLayout() {
        border = JBUI.Borders.empty(8)
        layout = BorderLayout(0, 8)
        add(mainContainer, BorderLayout.CENTER)
    }

    /**
     * Applies background styling and opacity settings.
     */
    private fun setupStyling() {
        background = JBColor.background()
        isOpaque = true
        mainContainer.isOpaque = true
    }

    /**
     * Configures focus listeners for border color changes.
     *
     * Sets up dynamic border styling that highlights the input area
     * with an accent color when focused and returns to default when blurred.
     */
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

    /**
     * Creates the main container panel with rounded border and component layout.
     *
     * Arranges components vertically: text input at top, file tabs in center,
     * and controls at bottom, all wrapped in a rounded border container.
     *
     * @return Configured container panel with all input components
     */
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
            add(inputControlsComponent, BorderLayout.SOUTH)
        }

    /**
     * Shows the file selection dialog anchored to the add file button.
     *
     * Configures the dialog to exclude already open files and displays
     * it positioned relative to the add file button.
     */
    private fun showFileSelectionDialog() {
        fileSelectionDialog.excludedFiles = fileTabsComponent.getAllOpenFiles()
        fileSelectionDialog.show(inputControlsComponent.getAddFileButton())
    }

    // Public API

    /**
     * Clears all text from the input field.
     */
    fun clearInput() = textInputComponent.clearText()

    /**
     * Adds a new file tab to the file tabs component.
     *
     * @param file The VirtualFile to add as a tab
     */
    fun addFileTab(file: VirtualFile) = fileTabsComponent.addTab(file)

    /**
     * Removes a file tab from the file tabs component.
     *
     * @param file The VirtualFile whose tab should be removed
     */
    fun removeFileTab(file: VirtualFile) = fileTabsComponent.removeTab(file)

    /**
     * Updates the available models in the model selection dropdown.
     *
     * @param models Array of model names to populate the dropdown
     */
    fun updateModelComboBox(models: Array<String>) = inputControlsComponent.updateModels(models)

    /**
     * Gets the currently selected model from the dropdown.
     *
     * @return The selected model name, or null if none selected
     */
    fun getSelectedModel(): String? = inputControlsComponent.getSelectedModel()

    /**
     * Switches between normal and generating states.
     *
     * Changes the send button to a stop button during generation and back
     * to send button when ready for new input.
     *
     * @param isGenerating Whether response generation is currently active
     */
    fun setGeneratingState(isGenerating: Boolean) {
        inputControlsComponent.setGeneratingState(isGenerating)
    }

    /**
     * Sets the callback for stopping ongoing generation.
     *
     * @param action Callback to invoke when stop button is clicked
     */
    fun setOnStop(action: () -> Unit) {
        inputControlsComponent.onStop = action
    }

    /**
     * Sets the text content of the input field.
     *
     * @param text The text content to set in the input area
     */
    fun setInputText(text: String) {
        textInputComponent.setText(text)
    }

    /**
     * Shows the cancel edit button for message editing mode.
     *
     * Displays a cancel button next to the send button to allow
     * canceling of message edits in progress.
     *
     * @param onCancel Callback invoked when cancel button is clicked
     */
    fun showCancelEditButton(onCancel: () -> Unit) {
        inputControlsComponent.showCancelEditButton(onCancel)
    }

    /**
     * Hides the cancel edit button and returns to normal mode.
     */
    fun hideCancelEditButton() {
        inputControlsComponent.hideCancelEditButton()
    }

    /**
     * Requests focus for the text input field.
     *
     * Brings keyboard focus to the text input area and positions
     * the cursor at the end of any existing content.
     */
    fun focusInputField() {
        textInputComponent.focusInput()
    }

    val inputText: String get() = textInputComponent.text
}

/**
 * Custom border implementation for drawing rounded rectangles with configurable styling.
 *
 * Provides a rounded border with adjustable color, thickness, and corner radius
 * for creating modern UI components with smooth corners.
 *
 * @param color The border color
 * @param thickness The border line thickness in pixels
 * @param arc The corner radius for rounded corners
 */
class RoundedBorder(
    private val color: Color,
    private val thickness: Int = 2,
    private val arc: Int = 16,
) : AbstractBorder() {
    /**
     * Paints the rounded border around the component.
     *
     * Draws a rounded rectangle outline with the specified color, thickness,
     * and corner radius using antialiased graphics for smooth edges.
     */
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

    /**
     * Returns the insets required by this border.
     *
     * @param c The component (unused)
     * @return Insets equal to the border thickness on all sides
     */
    override fun getBorderInsets(c: Component) = JBUI.insets(thickness, thickness, thickness, thickness)

    /**
     * Updates the provided insets object with this border's inset values.
     *
     * @param c The component (unused)
     * @param insets The insets object to update
     * @return The updated insets object
     */
    override fun getBorderInsets(
        c: Component,
        insets: Insets,
    ): Insets {
        insets.set(thickness, thickness, thickness, thickness)
        return insets
    }
}
