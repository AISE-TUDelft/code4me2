package me.code4me.toolWindow.ui.componenets

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max
import kotlin.math.min

/**
 * * Dialog for selecting files from the project, with search functionality and dynamic sizing.
 */
class FileSelectionDialog(
    private val project: Project,
    private val onFileSelected: (VirtualFile) -> Unit,
) {
    companion object {
        private const val MIN_WIDTH = 250
        private const val MAX_WIDTH = 400
        private const val MAX_VISIBLE_ROWS = 12
        private const val ROW_HEIGHT = 28
        private const val ICON_WIDTH = 16
        private const val ICON_GAP = 8
        private const val PADDING = 24
        private const val SEARCH_FIELD_HEIGHT = 28
        private const val VERTICAL_PADDING = 8
        private const val LABEL_HEIGHT = 25
    }

    private var currentPopup: JBPopup? = null
    private lateinit var listModel: DefaultListModel<VirtualFile>
    private lateinit var fileList: JList<VirtualFile>
    private lateinit var emptyStateLabel: JLabel
    private lateinit var listScrollPane: JScrollPane
    private lateinit var contentPanel: JPanel
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var anchorComponent: JComponent? = null
    var excludedFiles: Set<VirtualFile> = emptySet()

    /**
     * Displays the file selection popup anchored to a given UI component.
     *
     * @param anchorComponent The UI component to align the popup with.
     */
    fun show(anchorComponent: JComponent) {
        this.anchorComponent = anchorComponent
        currentPopup = createFileSelectionPopup()
        showPopup(currentPopup!!, anchorComponent)

        // Request focus after popup is visible
        SwingUtilities.invokeLater {
            val searchField = contentPanel.components.find { it is JTextField } as? JTextField
            searchField?.requestFocusInWindow()
        }
    }

    /**
     * Builds and returns the main file selection popup UI with search and list.
     *
     * @return A configured JBPopup ready to be shown.
     */
    private fun createFileSelectionPopup(): JBPopup {
        val openFiles = FileEditorManager.getInstance(project).openFiles
        listModel =
            DefaultListModel<VirtualFile>().apply {
                openFiles
                    .filterNot { excludedFiles.contains(it) } // ✅ correct filtering
                    .forEach { addElement(it) }
            }

        fileList = createFileList()
        val searchField = createSearchField()
        val content = createContentPanel(searchField, fileList)

        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField)
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
    }

    /**
     * Creates the file list UI component showing VirtualFiles as selectable items.
     *
     * @return A JList of VirtualFiles with custom rendering and click behavior.
     */
    private fun createFileList(): JList<VirtualFile> =
        JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            isOpaque = false
            background = JBColor.PanelBackground
            selectionBackground = JBColor(Color(0, 120, 215, 40), Color(0, 120, 215, 60))
            border = JBUI.Borders.empty()
            cellRenderer = FileListCellRenderer()

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (e?.clickCount == 1) {
                            val index = locationToIndex(e.point)
                            if (index in 0 until model.size) {
                                model.getElementAt(index)?.let {
                                    onFileSelected(it)
                                    currentPopup?.cancel()
                                }
                            }
                        }
                    }
                },
            )
        }

    /**
     * Builds the search field that filters the file list in real time.
     *
     * @return A configured JTextField with listeners.
     */
    private fun createSearchField(): JTextField =
        JTextField().apply {
            font = font.deriveFont(Font.PLAIN, 13f)
            background = JBColor.PanelBackground
            foreground = JBColor.foreground()
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    JBUI.Borders.empty(),
                )

            fun filter(query: String) {
                val trimmedQuery = query.trim().lowercase()
                coroutineScope.coroutineContext.cancelChildren()

                if (trimmedQuery.isEmpty()) {
                    SwingUtilities.invokeLater {
                        listModel.clear()
                        FileEditorManager.getInstance(project).openFiles.forEach { listModel.addElement(it) }
                        updatePopupSize()
                    }
                    return
                }

                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val matchingFiles =
                            ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
                                val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
                                val allFiles = mutableListOf<VirtualFile>()

                                // Collect all project files
                                projectFileIndex.iterateContent { file ->
                                    if (!file.isDirectory && file.name.contains(trimmedQuery, ignoreCase = true)) {
                                        allFiles.add(file)
                                    }
                                    true // Continue iteration
                                }

                                allFiles
                            }

                        SwingUtilities.invokeLater {
                            listModel.clear()
                            matchingFiles.forEach { listModel.addElement(it) }
                            emptyStateLabel.text = if (listModel.isEmpty) "No files found" else ""
                            emptyStateLabel.isVisible = listModel.isEmpty
                            updatePopupSize()
                        }
                    } catch (_: Exception) {
                        // Suppress error display
                    }
                }
            }

            document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = filter(text)

                    override fun removeUpdate(e: DocumentEvent?) = filter(text)

                    override fun changedUpdate(e: DocumentEvent?) = filter(text)
                },
            )

            addActionListener {
                if (listModel.size() > 0) {
                    val firstFile = listModel.getElementAt(0)
                    onFileSelected(firstFile)
                    currentPopup?.cancel()
                }
            }
        }

    /**
     * Combines the search field, file list, and labels into a single content panel.
     *
     * @param searchField The search input.
     * @param fileList The list of files to display.
     * @return The fully assembled content JPanel for the popup.
     */
    private fun createContentPanel(
        searchField: JTextField,
        fileList: JList<VirtualFile>,
    ): JPanel {
        val initialWidth = calculateDynamicWidth()
        searchField.preferredSize = Dimension(initialWidth - 16, SEARCH_FIELD_HEIGHT)

        listScrollPane =
            JBScrollPane(fileList).apply {
                isOpaque = false
                viewport.isOpaque = false
                border = BorderFactory.createEmptyBorder()
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }

        emptyStateLabel =
            JLabel("").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 12f)
                alignmentX = Component.CENTER_ALIGNMENT
                border = JBUI.Borders.empty(12)
                isVisible = false
            }

        contentPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(VERTICAL_PADDING, VERTICAL_PADDING, 4, VERTICAL_PADDING)
                add(searchField)
                add(Box.createVerticalStrut(VERTICAL_PADDING))
                add(emptyStateLabel)
                add(
                    JLabel("Files").apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.BOLD, 11f)
                        border = JBUI.Borders.empty(6, 12, 4, 12)
                    },
                )
                add(Box.createVerticalStrut(2))
                add(listScrollPane)
            }

        return JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    BorderFactory.createEmptyBorder(),
                )
            add(contentPanel, BorderLayout.CENTER)
            preferredSize = Dimension(initialWidth, contentPanel.preferredSize.height + 16)
        }
    }

    /**
     * Calculates optimal popup width based on file name lengths. todo improve
     *
     * @return The desired width within predefined bounds.
     */
    private fun calculateDynamicWidth(): Int {
        val fontMetrics = fileList.getFontMetrics(fileList.font)
        var maxTextWidth = 0
        for (i in 0 until listModel.size()) {
            val file = listModel.getElementAt(i)
            val textWidth = fontMetrics.stringWidth(file.name)
            maxTextWidth = max(maxTextWidth, textWidth)
        }
        val calculatedWidth = maxTextWidth + ICON_WIDTH + ICON_GAP + PADDING
        return min(max(calculatedWidth, MIN_WIDTH), MAX_WIDTH)
    }

    /**
     * Dynamically updates the popup dimensions based on content and layout. todo improve
     *
     * Called when filtering results or resizing.
     */
    private fun updatePopupSize() {
        SwingUtilities.invokeLater {
            val isEmpty = listModel.isEmpty
            emptyStateLabel.isVisible = isEmpty

            val dynamicWidth = calculateDynamicWidth()
            val emptyListHeight = 1

            val listHeight =
                if (isEmpty) {
                    emptyListHeight
                } else {
                    val rows = min(listModel.size(), MAX_VISIBLE_ROWS)
                    fileList.visibleRowCount = rows
                    rows * ROW_HEIGHT
                }

            listScrollPane.preferredSize = Dimension(dynamicWidth - 16, listHeight)
            listScrollPane.maximumSize = listScrollPane.preferredSize

            val searchField = contentPanel.components.find { it is JTextField } as? JTextField
            searchField?.preferredSize = Dimension(dynamicWidth - 16, SEARCH_FIELD_HEIGHT)

            val totalHeight = VERTICAL_PADDING * 3 + SEARCH_FIELD_HEIGHT + LABEL_HEIGHT + listHeight + 8

            contentPanel.preferredSize = Dimension(dynamicWidth, totalHeight)
            contentPanel.maximumSize = contentPanel.preferredSize
            (contentPanel.parent as? JPanel)?.preferredSize = Dimension(dynamicWidth, totalHeight + 16)

            currentPopup?.content?.apply {
                preferredSize = Dimension(dynamicWidth, totalHeight + 16)
                maximumSize = preferredSize
                validate()
                repaint()
            }

            // Reposition popup to keep bottom-left aligned with anchor
            anchorComponent?.let { anchor ->
                val buttonLocation = anchor.locationOnScreen
                val popupHeight = totalHeight + 16
                val x = buttonLocation.x
                val y = buttonLocation.y - popupHeight
                currentPopup?.setLocation(Point(x, y))
            }

            SwingUtilities.getWindowAncestor(contentPanel)?.pack()
        }
    }

    /**
     * Positions and shows the popup near the given anchor component.
     *
     * @param popup The JBPopup to show.
     * @param anchorComponent The UI anchor.
     */
    private fun showPopup(
        popup: JBPopup,
        anchorComponent: JComponent,
    ) {
        val buttonLocation = anchorComponent.locationOnScreen
        val popupSize = popup.content.preferredSize
        val x = buttonLocation.x
        val y = buttonLocation.y - popupSize.height
        popup.showInScreenCoordinates(anchorComponent, Point(x, y))
    }

    /**
     * Custom list cell renderer for VirtualFiles.
     * Shows file icon and name, styled with padding and selection color.
     */
    private inner class FileListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val file = value as? VirtualFile
            val comp = super.getListCellRendererComponent(list, file?.name ?: "", index, isSelected, cellHasFocus)
            font = font.deriveFont(Font.PLAIN, 13f)
            foreground = JBColor.foreground()
            icon = file?.fileType?.icon ?: AllIcons.FileTypes.Text
            background = if (isSelected) JBColor(Color(0, 120, 215, 40), Color(0, 120, 215, 60)) else Color(0, 0, 0, 0)
            border = JBUI.Borders.empty(6, 12)
            isOpaque = isSelected
            preferredSize = Dimension(preferredSize.width, ROW_HEIGHT)
            return comp
        }
    }
}
