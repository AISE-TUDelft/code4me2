package me.code4me.chatWindow.components.inputPanel.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Component containing the bottom control buttons and model selector for chat input.
 *
 * Provides controls for adding files, toggling web search, selecting models, and sending
 * messages. Dynamically switches between send/stop buttons and can show cancel edit
 * functionality when in edit mode.
 *
 * @param onWebToggle Callback invoked when web search toggle state changes
 * @param onFileAdd Callback invoked when add file button is clicked
 * @param onSend Callback invoked when send button is clicked
 */
class InputControlsComponent(
    private val onWebToggle: (Boolean) -> Unit,
    private val onFileAdd: () -> Unit,
    private val onSend: () -> Unit,
) : JBPanel<InputControlsComponent>(BorderLayout()) {
    /**
     * Button for adding files to the chat context.
     */
    private val addFileButton = createAddFileButton()

    /**
     * Toggle button for enabling/disabling web search functionality.
     */
    private val webToggleButton = createWebToggleButton()

    /**
     * Dropdown for selecting AI model for responses.
     */
    private val modelComboBox = ModelComboBox()

    /**
     * Button for sending chat messages.
     */
    private val sendButton = createSendButton()

    /**
     * Button for stopping ongoing generation.
     */
    private val stopButton = createStopButton()

    /**
     * Callback for canceling edit mode, set when edit mode is active.
     */
    private var onCancelEdit: (() -> Unit)? = null

    /**
     * Left panel containing file, web toggle, and model selection controls.
     */
    private val leftPanel =
        JPanel().apply {
            background = this@InputControlsComponent.background
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

    /**
     * Right panel containing send/stop and optional cancel edit buttons.
     */
    private val rightPanel =
        JPanel().apply {
            background = this@InputControlsComponent.background
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

    /**
     * Optional stop handler assigned externally for stopping generation.
     */
    var onStop: (() -> Unit)? = null

    init {
        setupLayout()
        setupStyling()
    }

    /**
     * Constructs the layout of the component by arranging control buttons
     * on the left and the send button on the right.
     */
    private fun setupLayout() {
        leftPanel.apply {
            add(addFileButton)
            add(Box.createHorizontalStrut(8))
            add(webToggleButton)
            add(Box.createHorizontalStrut(4))
            add(modelComboBox)
            add(Box.createHorizontalGlue())
        }

        rightPanel.add(sendButton)

        add(leftPanel, BorderLayout.WEST)
        add(rightPanel, BorderLayout.EAST)
    }

    /**
     * Configures the component's background, border, and padding.
     */
    private fun setupStyling() {
        background = JBColor.background()
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(8, 8, 4, 8),
            )
    }

    private fun createAddFileButton() =
        IconButton(
            icon = AllIcons.General.Add,
            tooltip = "Add file to context",
            action = onFileAdd,
        )

    private var cancelEditButton =
        JButton("Cancel").apply {
            toolTipText = "Cancel editing"
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = true
            isOpaque = false
            foreground = JBColor.foreground()
            addActionListener { onCancelEdit?.invoke() }
        }

    private fun createWebToggleButton() =
        IconToggleButton(
            tooltip = "Enable web search",
            action = onWebToggle,
        )

    private fun createSendButton(): JButton {
        val sendIcon = IconLoader.getIcon("/icons/send.svg", InputControlsComponent::class.java)
        val hoverIcon = IconLoader.getIcon("/icons/send_hover.svg", InputControlsComponent::class.java)
        val clickIcon = IconLoader.getIcon("/icons/send_click.svg", InputControlsComponent::class.java)

        return CleanIconButton(
            defaultIcon = sendIcon,
            hoverIcon = hoverIcon,
            clickIcon = clickIcon,
            tooltip = "Send (Enter)",
            action = onSend,
        )
    }

    private fun createStopButton(): JButton {
        val stopIcon = AllIcons.Process.Stop
        return CleanIconButton(
            defaultIcon = stopIcon,
            hoverIcon = stopIcon,
            clickIcon = stopIcon,
            tooltip = "Stop generation",
            action = { onStop?.invoke() },
        )
    }

    /**
     * Shows the cancel edit button and configures edit mode layout.
     *
     * Adds a cancel button next to the send button for canceling message edits.
     * The cancel button appears between other controls and the send button.
     *
     * @param onCancel Callback invoked when cancel edit button is clicked
     */
    fun showCancelEditButton(onCancel: () -> Unit) {
        onCancelEdit = onCancel
        cancelEditButton.isVisible = true
        rightPanel.removeAll()
        rightPanel.add(cancelEditButton)
        rightPanel.add(Box.createHorizontalStrut(8))
        rightPanel.add(sendButton)
        rightPanel.revalidate()
        rightPanel.repaint()
    }

    /**
     * Hides the cancel edit button and returns to normal layout.
     *
     * Removes the cancel button and resets the right panel to show only the send button.
     */
    fun hideCancelEditButton() {
        rightPanel.removeAll()
        rightPanel.add(sendButton)
        rightPanel.revalidate()
        rightPanel.repaint()
        cancelEditButton.isVisible = false
        onCancelEdit = null
    }

    fun getAddFileButton(): JButton = addFileButton

    fun updateModels(models: Array<String>) = modelComboBox.updateModels(models)

    fun getSelectedModel(): String? = modelComboBox.getSelectedModel()

    /**
     * Switches between send and stop buttons based on generation state.
     *
     * Shows stop button during generation and send button when ready for input.
     *
     * @param isGenerating Whether response generation is currently active
     */
    fun setGeneratingState(isGenerating: Boolean) {
        rightPanel.removeAll()
        rightPanel.add(if (isGenerating) stopButton else sendButton)
        rightPanel.revalidate()
        rightPanel.repaint()
    }
}
