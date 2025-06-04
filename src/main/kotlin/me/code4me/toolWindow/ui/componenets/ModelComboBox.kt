package me.code4me.toolWindow.ui.componenets

import com.intellij.openapi.ui.ComboBox
import java.awt.*
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min

/**
 * A combo box UI component for selecting AI models.
 *
 * Displays a dropdown list of available model names
 */
class ModelComboBox : ComboBox<String>() {
    companion object {
        private const val MIN_WIDTH = 60
        private const val MAX_WIDTH = 350
        private const val DROPDOWN_PADDING = 50
        private const val HEIGHT = 26
        private val DEFAULT_MODELS = arrayOf("No models available")
    }

    init {
        setupDefaultModels()
        setupInitialSize()
        setupStyling()
        setupAutoResize()
    }

    private fun setupDefaultModels() {
        DEFAULT_MODELS.forEach { addItem(it) }
    }

    private fun setupInitialSize() {
        preferredSize = Dimension(180, HEIGHT)
        maximumSize = Dimension(300, HEIGHT)
        minimumSize = Dimension(120, HEIGHT)
    }

    private fun setupStyling() {
        isOpaque = false
        background = Color(0, 0, 0, 0)
        border = null
        setRenderer(TransparentRenderer())
    }

    private fun setupAutoResize() {
        addActionListener {
            SwingUtilities.invokeLater { resizeToFitContent() }
        }
        SwingUtilities.invokeLater { resizeToFitContent() }
    }

    private fun resizeToFitContent() {
        try {
            val optimalWidth = calculateOptimalWidth()
            val newSize = Dimension(optimalWidth, HEIGHT)

            preferredSize = newSize
            maximumSize = newSize

            revalidate()
            parent?.revalidate()
            repaint()
        } catch (e: Exception) {
            // Fallback to reasonable default
            preferredSize = Dimension(180, HEIGHT)
            maximumSize = Dimension(180, HEIGHT)
        }
    }

    private fun calculateOptimalWidth(): Int {
        val fontMetrics =
            try {
                getFontMetrics(font ?: UIManager.getFont("ComboBox.font") ?: Font("Dialog", Font.PLAIN, 12))
            } catch (e: Exception) {
                Canvas().getFontMetrics(font ?: Font("Dialog", Font.PLAIN, 12))
            }

        val selectedText = selectedItem?.toString() ?: ""
        val textWidth = fontMetrics.stringWidth(selectedText)
        val totalWidth = textWidth + DROPDOWN_PADDING

        return max(MIN_WIDTH, min(totalWidth, MAX_WIDTH))
    }

    /**
     * Replaces the current list of models in the dropdown with a new set.
     *
     * Clears existing entries and repopulates the combo box.
     *
     * @param models The array of model names to display.
     */
    fun updateModels(models: Array<String>) {
        val currentSelection = selectedItem?.toString()

        removeAllItems()

        if (models.isEmpty()) {
            addItem("No models available")
            isEnabled = false
        } else {
            models.forEach { addItem(it) }
            isEnabled = true

            // Try to restore previous selection if it exists in new models
            if (currentSelection != null && models.contains(currentSelection)) {
                selectedItem = currentSelection
            }
        }

        resizeToFitContent()
    }

    fun getSelectedModel(): String? {
        return selectedItem?.toString()?.takeIf { it != "Loading models..." && it != "No models available" }
    }

    private class TransparentRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                background = Color(0, 0, 0, 0)
                setOpaque(false)
            }
        }
    }
}
