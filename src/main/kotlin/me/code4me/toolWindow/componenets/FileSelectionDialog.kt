package me.code4me.toolWindow.chatPanelUI

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max
import kotlin.math.min

/**
 * Dialog for selecting files to add to the chat context
 */
class FileSelectionDialog(
    private val fileComboBox: ComboBox<VirtualFile>,
    private val onFileSelected: (VirtualFile) -> Unit,
) {
    companion object {
        private const val MIN_WIDTH = 180
        private const val MAX_WIDTH = 320
        private const val MAX_VISIBLE_ROWS = 8
        private const val ROW_HEIGHT = 28
        private const val ICON_WIDTH = 16
        private const val ICON_GAP = 8
        private const val PADDING = 24
    }

    private var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    fun show(anchorComponent: JComponent) {
        val availableFiles = getAvailableFiles()

        if (availableFiles.isEmpty()) {
            showNoFilesMessage(anchorComponent)
            return
        }

        currentPopup = createFileSelectionPopup(availableFiles, anchorComponent)
        showPopup(currentPopup!!, anchorComponent)
    }

    private fun getAvailableFiles(): List<VirtualFile> {
        val model = fileComboBox.model
        return (1 until model.size).mapNotNull { model.getElementAt(it) }
    }

    private fun showNoFilesMessage(anchorComponent: JComponent) {
        JBPopupFactory.getInstance()
            .createMessage("No files available to add")
            .showInCenterOf(anchorComponent)
    }

    private fun createFileSelectionPopup(
        files: List<VirtualFile>,
        anchorComponent: JComponent,
    ) = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(createContent(files), null)
        .setFocusable(true)
        .setRequestFocus(true)
        .setResizable(false)
        .setMovable(false)
        .createPopup()

    private fun createContent(files: List<VirtualFile>): JComponent {
        val listModel =
            DefaultListModel<VirtualFile>().apply {
                files.forEach { addElement(it) }
            }

        val fileList = createFileList(listModel)
        val searchField = createSearchField(files, listModel, fileList)
        val dynamicWidth = calculateDynamicWidth(files, fileList)

        return createContentPanel(searchField, fileList, dynamicWidth)
    }

    private fun createFileList(listModel: DefaultListModel<VirtualFile>) =
        JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            isOpaque = false
            background = JBColor.PanelBackground
            selectionBackground = JBColor(Color(0, 120, 215, 40), Color(0, 120, 215, 60))
            border = JBUI.Borders.empty()
            visibleRowCount = min(MAX_VISIBLE_ROWS, listModel.size())
            cellRenderer = FileListCellRenderer()

            // Add mouse listener to handle clicks
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (e?.clickCount == 1) {
                            val index = locationToIndex(e.point)
                            if (index >= 0 && index < model.size) {
                                val selectedFile = model.getElementAt(index)
                                selectedFile?.let { file ->
                                    onFileSelected(file)
                                    currentPopup?.cancel()
                                }
                            }
                        }
                    }
                },
            )
        }

    private fun createSearchField(
        allFiles: List<VirtualFile>,
        listModel: DefaultListModel<VirtualFile>,
        fileList: JList<VirtualFile>,
    ) = JTextField().apply {
        font = font.deriveFont(Font.PLAIN, 13f)
        margin = Insets(2, 6, 2, 6)
        background = JBColor.PanelBackground
        foreground = JBColor.foreground()
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(4, 8),
            )

        document.addDocumentListener(SearchDocumentListener(allFiles, listModel, fileList, this))

        // Add Enter key support for search field
        addActionListener {
            if (listModel.size() > 0) {
                val firstFile = listModel.getElementAt(0)
                firstFile?.let { file ->
                    onFileSelected(file)
                    currentPopup?.cancel()
                }
            }
        }
    }

    private fun calculateDynamicWidth(
        files: List<VirtualFile>,
        fileList: JList<VirtualFile>,
    ): Int {
        val fontMetrics = fileList.getFontMetrics(fileList.font)
        val maxTextWidth = files.maxOfOrNull { fontMetrics.stringWidth(it.name) } ?: 200
        val calculatedWidth = maxTextWidth + ICON_WIDTH + ICON_GAP + PADDING
        return min(max(calculatedWidth, MIN_WIDTH), MAX_WIDTH)
    }

    private fun createContentPanel(
        searchField: JTextField,
        fileList: JList<VirtualFile>,
        dynamicWidth: Int,
    ): JPanel {
        searchField.preferredSize = Dimension(dynamicWidth - 16, 28)

        val listScrollPane = createScrollPane(fileList, dynamicWidth)

        val contentPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(8, 8, 4, 8)

                add(searchField)
                add(Box.createVerticalStrut(8))
                add(createSectionLabel())
                add(Box.createVerticalStrut(2))
                add(listScrollPane)
            }

        return createWrapper(contentPanel, dynamicWidth)
    }

    private fun createScrollPane(
        fileList: JList<VirtualFile>,
        dynamicWidth: Int,
    ) = JScrollPane(fileList).apply {
        isOpaque = false
        viewport.isOpaque = false
        border = BorderFactory.createEmptyBorder()

        val maxRows = min(MAX_VISIBLE_ROWS, fileList.model.size)
        val calculatedHeight = maxRows * ROW_HEIGHT + 4

        preferredSize = Dimension(dynamicWidth - 16, calculatedHeight)
        verticalScrollBarPolicy =
            if (fileList.model.size > MAX_VISIBLE_ROWS) {
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            } else {
                JScrollPane.VERTICAL_SCROLLBAR_NEVER
            }
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private fun createSectionLabel() =
        JLabel("Recent files").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.empty(6, 12, 4, 12)
        }

    private fun createWrapper(
        contentPanel: JPanel,
        dynamicWidth: Int,
    ) = JPanel(BorderLayout()).apply {
        background = JBColor.PanelBackground
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(),
            )
        add(contentPanel, BorderLayout.CENTER)
        preferredSize = Dimension(dynamicWidth, contentPanel.preferredSize.height + 16)
    }

    private fun showPopup(
        popup: com.intellij.openapi.ui.popup.JBPopup,
        anchorComponent: JComponent,
    ) {
        val buttonLocation = anchorComponent.locationOnScreen
        val buttonSize = anchorComponent.size
        val popupSize = popup.content.preferredSize

        val x = buttonLocation.x + buttonSize.width - popupSize.width
        val y = buttonLocation.y - popupSize.height - 4

        popup.showInScreenCoordinates(anchorComponent, Point(x, y))
    }

    private inner class FileListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val file = value as? VirtualFile
            val comp =
                super.getListCellRendererComponent(
                    list,
                    file?.name ?: "",
                    index,
                    isSelected,
                    cellHasFocus,
                )

            font = font.deriveFont(Font.PLAIN, 13f)
            foreground = JBColor.foreground()
            icon = file?.fileType?.icon ?: AllIcons.FileTypes.Text
            background =
                if (isSelected) {
                    JBColor(Color(0, 120, 215, 40), Color(0, 120, 215, 60))
                } else {
                    Color(0, 0, 0, 0)
                }
            border = JBUI.Borders.empty(6, 12, 6, 12)
            isOpaque = isSelected

            return comp
        }
    }

    private inner class SearchDocumentListener(
        private val allFiles: List<VirtualFile>,
        private val listModel: DefaultListModel<VirtualFile>,
        private val fileList: JList<VirtualFile>,
        private val searchField: JTextField,
    ) : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = filter()

        override fun removeUpdate(e: DocumentEvent?) = filter()

        override fun changedUpdate(e: DocumentEvent?) = filter()

        private fun filter() {
            val query = searchField.text.trim().lowercase()
            val filtered = allFiles.filter { it.name.lowercase().contains(query) }

            listModel.clear()
            filtered.forEach { listModel.addElement(it) }
            fileList.visibleRowCount = min(MAX_VISIBLE_ROWS, filtered.size)
        }
    }
}
