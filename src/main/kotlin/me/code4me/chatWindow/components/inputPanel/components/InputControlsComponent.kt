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
 * Component containing the bottom control buttons and model selector
 */
class InputControlsComponent(
    private val onWebToggle: (Boolean) -> Unit,
    private val onFileAdd: () -> Unit,
    private val onSend: () -> Unit,
) : JBPanel<InputControlsComponent>(BorderLayout()) {
    private val addFileButton = createAddFileButton()
    private val webToggleButton = createWebToggleButton()
    private val modelComboBox = ModelComboBox()
    private val sendButton = createSendButton()
    private val stopButton = createStopButton()
    private val leftPanel =
        JPanel().apply {
            background = this@InputControlsComponent.background
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
    private val rightPanel =
        JPanel().apply {
            background = this@InputControlsComponent.background
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

    /** Optional stop handler assigned externally */
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

    fun getAddFileButton(): JButton = addFileButton

    fun updateModels(models: Array<String>) = modelComboBox.updateModels(models)

    fun getSelectedModel(): String? = modelComboBox.getSelectedModel()

    fun setGeneratingState(isGenerating: Boolean) {
        rightPanel.removeAll()
        rightPanel.add(if (isGenerating) stopButton else sendButton)
        rightPanel.revalidate()
        rightPanel.repaint()
    }
}
