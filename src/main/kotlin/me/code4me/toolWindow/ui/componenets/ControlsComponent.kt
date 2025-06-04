package me.code4me.toolWindow.ui.componenets

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Component containing the bottom control buttons and model selector
 */
class ControlsComponent(
    private val onWebToggle: (Boolean) -> Unit,
    private val onFileAdd: () -> Unit,
    private val onSend: () -> Unit,
) : JBPanel<ControlsComponent>(BorderLayout()) {
    private val addFileButton = createAddFileButton()
    private val webToggleButton = createWebToggleButton()
    private val modelComboBox = ModelComboBox()
    private val sendButton = createSendButton()

    init {
        setupLayout()
        setupStyling()
    }

    /**
     * Constructs the layout of the component by arranging control buttons
     * on the left and the send button on the right.
     */
    private fun setupLayout() {
        val leftPanel =
            JPanel().apply {
                background = this@ControlsComponent.background
                layout = BoxLayout(this, BoxLayout.X_AXIS)

                add(addFileButton)
                add(Box.createHorizontalStrut(8))
                add(webToggleButton)
                add(Box.createHorizontalStrut(4))
                add(modelComboBox)
                add(Box.createHorizontalGlue())
            }

        val rightPanel =
            JPanel().apply {
                background = this@ControlsComponent.background
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(sendButton)
            }

        add(leftPanel, BorderLayout.WEST)
        add(rightPanel, BorderLayout.EAST)
    }

    /**
     * Applies basic styling: background color and padding borders.
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

    private fun createWebToggleButton() =
        IconToggleButton(
            tooltip = "Enable web search",
            action = onWebToggle,
        )

    private fun createSendButton(): JButton {
        val sendIcon = IconLoader.getIcon("/icons/send.svg", ControlsComponent::class.java)
        val hoverIcon = IconLoader.getIcon("/icons/send_hover.svg", ControlsComponent::class.java)
        val clickIcon = IconLoader.getIcon("/icons/send_click.svg", ControlsComponent::class.java)

        return CleanIconButton(
            defaultIcon = sendIcon,
            hoverIcon = hoverIcon,
            clickIcon = clickIcon, // Pass it in
            tooltip = "Send (Enter)",
            action = onSend,
        )
    }

    fun getAddFileButton(): JButton = addFileButton

    fun updateModels(models: Array<String>) = modelComboBox.updateModels(models)

    fun getSelectedModel(): String? = modelComboBox.getSelectedModel()
}
