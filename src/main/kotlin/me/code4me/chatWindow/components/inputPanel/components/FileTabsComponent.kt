package me.code4me.chatWindow.components.inputPanel.components

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Component for managing file tabs display with dynamic height adjustment
 */
class FileTabsComponent(
    private val onFileClose: (VirtualFile) -> Unit,
) : JBPanel<FileTabsComponent>(FlowLayout(FlowLayout.LEFT, 5, 4)) {
    private val fileTabs = mutableMapOf<VirtualFile, JPanel>()

    companion object {
        private const val MIN_HEIGHT = 0 // No minimum height when no tabs
        private const val TAB_HEIGHT = 32 // Approximate height of each tab row
        private const val VERTICAL_PADDING = 8 // Top and bottom padding
    }

    init {
        setupStyling()
    }

    /**
     * Configures the initial styling and layout properties for the tab container.
     */
    private fun setupStyling() {
        background = JBColor.background()
        border = JBUI.Borders.emptyBottom(4)
        // Start with no preferred size - will be calculated dynamically
        preferredSize = Dimension(0, MIN_HEIGHT)
    }

    /**
     * Adds a new file tab to the component if it's not already present.
     *
     * @param file The VirtualFile to add as a tab.
     */
    fun addTab(file: VirtualFile) {
        if (fileTabs.containsKey(file)) return

        val tab = FileTab(file, onFileClose)
        fileTabs[file] = tab

        add(tab)
        updateHeight()
    }

    /**
     * Removes the tab corresponding to the given file.
     *
     * @param file The VirtualFile whose tab should be removed.
     */
    fun removeTab(file: VirtualFile) {
        fileTabs[file]?.let { tab ->
            remove(tab)
            fileTabs.remove(file)
            updateHeight()
        }
    }

    /**
     * Removes all tabs and resets the component. Not currently used, but we could use it when user switches to differnet editor or different chat.
     */
    fun clearAllTabs() {
        removeAll()
        fileTabs.clear()
        updateHeight()
    }

    /**
     * Calculates and applies a new preferred height for the tab container based on tab wrapping.
     *
     * Called after adding or removing tabs.
     */
    private fun updateHeight() {
        SwingUtilities.invokeLater {
            val newHeight = calculateRequiredHeight()
            updateDimensions(newHeight)
            refreshLayout()
        }
    }

    /**
     * Determines how many rows of tabs are needed and computes total vertical height.
     *
     * @return The required height for the current tab layout.
     */
    private fun calculateRequiredHeight(): Int {
        if (fileTabs.isEmpty()) {
            return MIN_HEIGHT
        }

        // Force layout to calculate how tabs are arranged
        doLayout()

        val availableWidth = calculateAvailableWidth()
        if (availableWidth <= 0) return TAB_HEIGHT + VERTICAL_PADDING

        val rowCount = calculateRowCount(availableWidth)

        return (rowCount * TAB_HEIGHT) + VERTICAL_PADDING
    }

    /**
     * Estimates the available horizontal space for laying out tabs.
     *
     * @return The usable width in pixels.
     */
    private fun calculateAvailableWidth(): Int {
        val containerWidth = parent?.width ?: 0
        val insets = insets
        return if (containerWidth > 0) {
            containerWidth - insets.left - insets.right - 20 // Account for margins
        } else {
            400 // Default width fallback
        }
    }

    /**
     * Computes how many rows of tabs are needed based on the container width and tab sizes.
     *
     * @param availableWidth The width available for laying out tabs.
     * @return The number of rows required.
     */
    private fun calculateRowCount(availableWidth: Int): Int {
        if (fileTabs.isEmpty()) return 0

        val flowLayout = layout as FlowLayout
        val hgap = flowLayout.hgap
        var currentRowWidth = 0
        var rowCount = 1

        for (tab in fileTabs.values) {
            val tabWidth = tab.preferredSize.width
            val requiredWidth = if (currentRowWidth == 0) tabWidth else currentRowWidth + hgap + tabWidth

            if (requiredWidth > availableWidth && currentRowWidth > 0) {
                // Start new row
                rowCount++
                currentRowWidth = tabWidth
            } else {
                currentRowWidth = requiredWidth
            }
        }

        return maxOf(1, rowCount)
    }

    /**
     * Applies a new height to the tab component if it's different from the current height.
     *
     * @param newHeight The target height in pixels.
     */
    private fun updateDimensions(newHeight: Int) {
        val currentHeight = preferredSize.height
        if (currentHeight != newHeight) {
            preferredSize = Dimension(preferredSize.width, newHeight)
            minimumSize = Dimension(0, newHeight)
            maximumSize = Dimension(Int.MAX_VALUE, newHeight)
        }
    }

    /**
     * Invalidates and revalidates the component hierarchy to force layout update.
     *
     * Called after tabs are added or removed to adjust layout.
     */
    private fun refreshLayout() {
        // Refresh the parent container to accommodate height changes
        val parentContainer = parent?.parent
        parentContainer?.let {
            it.invalidate()
            it.revalidate()
            it.repaint()
        }

        invalidate()
        revalidate()
        repaint()
    }

    /**
     * Override to trigger height recalculation when container is resized.
     *
     * Monitors width changes and updates tab layout accordingly since
     * width changes can affect how many tabs fit per row.
     */
    override fun setBounds(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val oldWidth = this.width
        super.setBounds(x, y, width, height)

        if (width != oldWidth && fileTabs.isNotEmpty()) {
            SwingUtilities.invokeLater { updateHeight() }
        }
    }

    fun getAllOpenFiles(): Set<VirtualFile> = fileTabs.keys.toSet()
}

/**
 * Individual file tab with close button
 */
private class FileTab(
    private val file: VirtualFile,
    private val onClose: (VirtualFile) -> Unit,
) : JPanel(BorderLayout(4, 0)) {
    private val arc = 20 // Very rounded corners

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 4, 8)
        setupComponents()

        // Set a reasonable preferred size for layout calculations
        preferredSize = Dimension(calculatePreferredWidth(), 24)
    }

    private fun calculatePreferredWidth(): Int {
        val fileName = file.name
        val fontMetrics = getFontMetrics(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 12f))
        val textWidth = fontMetrics.stringWidth(fileName)

        val iconWidth = file.fileType.icon?.iconWidth ?: 0
        val iconTextGap = 4 // space between icon and text
        val closeButtonWidth = 16
        val padding = 32 // side padding + layout spacing

        return textWidth + iconWidth + iconTextGap + closeButtonWidth + padding
    }

    /**
     * Custom rendering to draw rounded tab background and border.
     */
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val width = width
        val height = height

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Simulate thick border by filling a slightly larger rounded rect (border color)
        g2.color = JBColor(Color(180, 180, 190), Color(90, 95, 100))
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)

        // Fill inner background with panel background (to create hollow center)
        g2.color = background // Should be transparent
        g2.fillRoundRect(1, 1, width - 3, height - 3, arc - 2, arc - 2)
    }

    /**
     * Sets up the internal components (file name label and close button).
     */
    private fun setupComponents() {
        val fileNameLabel = createFileNameLabel()
        val closeButton = createCloseButton()

        add(fileNameLabel, BorderLayout.CENTER)
        add(closeButton, BorderLayout.EAST)
    }

    /**
     * Creates the label displaying the file name and icon.
     *
     * @return Configured JLabel with file name, icon, and appropriate styling
     */
    private fun createFileNameLabel() =
        JLabel(file.name, file.fileType.icon, JLabel.LEFT).apply {
            foreground = JBColor(Color(50, 60, 70), Color(220, 225, 230))
            font = UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 12f)
            border = JBUI.Borders.empty(0, 2)
        }

    /**
     * Creates the close button for removing this tab.
     *
     * @return Configured close button that triggers the onClose callback
     */
    private fun createCloseButton() = IconToggleButton.CloseIconButton(onClick = { onClose(file) })
}
