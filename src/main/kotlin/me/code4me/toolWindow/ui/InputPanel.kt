package me.code4me.toolWindow.chatPanelUI

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class InputPanel(
    val onSend: () -> Unit,
    val onWebToggle: (Boolean) -> Unit,
    val fileComboBox: ComboBox<VirtualFile>,
    val onFileClose: (VirtualFile) -> Unit,
    val onFileSelected: (VirtualFile) -> Unit,
) : JBPanel<InputPanel>(BorderLayout()) {
    val inputField =
        JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("TextField.font")
            border = JBUI.Borders.empty(8, 12)
            isOpaque = false
            background = JBColor.background()
            caretPosition = 0
        }

    private val scrollPane =
        JScrollPane(inputField).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            background = JBColor.background()
        }

    private val fileTabsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
    private val fileTabs = mutableMapOf<VirtualFile, JPanel>()
    private val encapsulatedContainer = JPanel(BorderLayout())

    private val modelComboBox = setupModelComboBox()
    private lateinit var addFileButton: JButton

    private val minTextAreaHeight = 44
    private val maxTextAreaHeight = 500

    init {
        border = JBUI.Borders.empty(8)
        layout = BorderLayout(0, 8)
        background = JBColor.background()
        isOpaque = true

        setupEncapsulatedContainer()
        setupFileTabsPanel()

        add(fileTabsPanel, BorderLayout.NORTH)
        add(encapsulatedContainer, BorderLayout.CENTER)

        setupDocumentListener()

        inputField.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "send")
        inputField.inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-newline")
        inputField.actionMap.put(
            "send",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    onSend()
                }
            },
        )

        inputField.actionMap.put(
            "insert-newline",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    inputField.append("\n")
                }
            },
        )
    }

    private fun setupModelComboBox(): ComboBox<String> {
        return ComboBox(arrayOf("DeepSeek", "GPT", "Best model ever")).apply {
            // Set initial reasonable size that will be updated later
            preferredSize = Dimension(180, 26)
            maximumSize = Dimension(300, 26)
            minimumSize = Dimension(120, 26)

            // 👇 Remove background color
            isOpaque = false
            background = Color(0, 0, 0, 0) // Transparent background

            // Also remove border if needed
            border = null

            // Optional: transparent renderer too
            renderer =
                object : DefaultListCellRenderer() {
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

            addActionListener {
                SwingUtilities.invokeLater { updateComboBoxSize() }
            }

            SwingUtilities.invokeLater { updateComboBoxSize() }
        }
    }

    private fun ComboBox<String>.calculateOptimalWidth(): Int {
        // Ensure we have a valid font and graphics context
        val fontMetrics =
            try {
                getFontMetrics(font ?: UIManager.getFont("ComboBox.font") ?: Font("Dialog", Font.PLAIN, 12))
            } catch (e: Exception) {
                // Fallback if getFontMetrics fails
                val canvas = Canvas()
                canvas.getFontMetrics(font ?: Font("Dialog", Font.PLAIN, 12))
            }

        // Only calculate width for the currently selected item
        val selectedItem = selectedItem?.toString() ?: ""
        val textWidth = fontMetrics.stringWidth(selectedItem)

        // Add padding for the dropdown arrow and internal spacing
        val padding = 50 // Padding for dropdown arrow and margins
        val calculatedWidth = textWidth + padding

        // Set reasonable bounds
        return maxOf(60, minOf(calculatedWidth, 350))
    }

    private fun ComboBox<String>.updateComboBoxSize() {
        try {
            val newWidth = calculateOptimalWidth()
            preferredSize = Dimension(newWidth, 26)
            maximumSize = Dimension(newWidth, 26)

            // Force layout update
            revalidate()
            parent?.revalidate()
            repaint()
        } catch (e: Exception) {
            // Fallback to a reasonable default if calculation fails
            preferredSize = Dimension(180, 26)
            maximumSize = Dimension(180, 26)
        }
    }

    private fun setupEncapsulatedContainer() {
        encapsulatedContainer.apply {
            // Create a rounded border with padding
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1, true),
                    JBUI.Borders.empty(4),
                )
            background = JBColor.background()
            layout = BorderLayout()

            // Main input area
            val inputContainer =
                JPanel(BorderLayout()).apply {
                    background = JBColor.background()
                    isOpaque = true

                    // Add the scroll pane instead of direct text area
                    add(scrollPane, BorderLayout.CENTER)
                }

            // Bottom section with buttons
            val bottomSection =
                JPanel(BorderLayout()).apply {
                    background = JBColor.background()
                    border =
                        BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                            JBUI.Borders.empty(8, 8, 4, 8),
                        )

                    val leftPanel =
                        JPanel().apply {
                            background = background
                            layout = BoxLayout(this, BoxLayout.X_AXIS)

                            addFileButton =
                                createIconButton(AllIcons.General.Add, "Add file to context") {
                                    showFileSelectionPopup()
                                }
                            val webToggleButton =
                                createIconToggleButton(AllIcons.General.Web, "Enable web search") { isSelected ->
                                    onWebToggle(isSelected)
                                }

                            add(addFileButton)
                            add(Box.createHorizontalStrut(8))

                            add(webToggleButton)
                            add(Box.createHorizontalStrut(4))

                            add(modelComboBox)
                            add(Box.createHorizontalGlue())
                        }

                    val rightPanel =
                        JPanel().apply {
                            background = background
                            layout = BoxLayout(this, BoxLayout.X_AXIS)

                            val action = IconLoader.getIcon("/icons/send.svg", InputPanel::class.java) // TODO support dark and light mode

                            val sendButton =
                                createIconButton(action, "Send (Enter)") {
                                    onSend()
                                }
                            add(sendButton)
                        }

                    add(leftPanel, BorderLayout.WEST)
                    add(rightPanel, BorderLayout.EAST)
                }

            add(inputContainer, BorderLayout.CENTER)
            add(bottomSection, BorderLayout.SOUTH)
        }
    }

    private fun setupFileTabsPanel() {
        fileTabsPanel.apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
    }

    private fun setupDocumentListener() {
        inputField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateHeight()

                override fun removeUpdate(e: DocumentEvent) = updateHeight()

                override fun changedUpdate(e: DocumentEvent) = updateHeight()
            },
        )
    }

    fun updateHeight() {
        SwingUtilities.invokeLater {
            val text = inputField.text
            val fontMetrics = inputField.getFontMetrics(inputField.font)
            val fontHeight = fontMetrics.height

            // Get the actual available width for text
            val containerWidth = encapsulatedContainer.width
            val availableWidth = if (containerWidth > 32) containerWidth - 32 else parent?.width?.let { it - 64 } ?: 400

            val lineCount = countVisibleLines(text, fontMetrics, availableWidth)
            val calculatedHeight = (lineCount * fontHeight) + 16
            val newHeight = minOf(maxOf(calculatedHeight, minTextAreaHeight), maxTextAreaHeight)

            // Update both scroll pane and text area dimensions
            val newDimension = Dimension(availableWidth, newHeight)
            scrollPane.preferredSize = newDimension
            scrollPane.minimumSize = Dimension(availableWidth, minTextAreaHeight)
            scrollPane.maximumSize = Dimension(Int.MAX_VALUE, maxTextAreaHeight)

            inputField.preferredSize = null // Let it size naturally within scroll pane

            // Ensure the text area scrolls to show the caret (current position)
            SwingUtilities.invokeLater {
                try {
                    val caretPos = inputField.caretPosition
                    val rect = inputField.modelToView(caretPos)
                    if (rect != null) {
                        inputField.scrollRectToVisible(rect)
                    }
                } catch (e: Exception) {
                    // Fallback: scroll to end
                    inputField.caretPosition = inputField.text.length
                }
            }

            // Force layout update from the top
            val parentContainer = this@InputPanel.parent
            parentContainer?.invalidate()
            parentContainer?.revalidate()
            parentContainer?.repaint()

            this@InputPanel.invalidate()
            this@InputPanel.revalidate()
            this@InputPanel.repaint()
        }
    }

    private fun countVisibleLines(
        text: String,
        metrics: FontMetrics,
        width: Int,
    ): Int {
        if (text.isEmpty()) return 1
        val availableWidth = width - 24 // Account for padding
        if (availableWidth <= 0) return 1

        val lines = text.split("\n")
        var totalLines = 0
        for (line in lines) {
            if (line.isEmpty()) {
                totalLines += 1
                continue
            }
            val lineWidth = metrics.stringWidth(line)
            val wrappedLines = if (lineWidth <= availableWidth) 1 else Math.ceil(lineWidth.toDouble() / availableWidth).toInt()
            totalLines += wrappedLines
        }
        return Math.max(1, totalLines)
    }

    private fun createIconButton(
        icon: Icon,
        tooltip: String,
        action: () -> Unit,
    ): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(26, 26)
            maximumSize = Dimension(26, 26)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = Insets(2, 2, 2, 2)
            addActionListener { action() }

            // Add hover effect
            addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                        isContentAreaFilled = true
                        background = JBColor(Color(240, 240, 240), Color(60, 60, 60))
                        repaint()
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent?) {
                        isContentAreaFilled = false
                        repaint()
                    }
                },
            )
        }
    }

    private fun createIconToggleButton(
        icon: Icon,
        tooltip: String,
        action: (Boolean) -> Unit,
    ): JToggleButton {
        return JToggleButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(26, 26)
            maximumSize = Dimension(26, 26)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = Insets(2, 2, 2, 2)
            addActionListener { action(isSelected) }

            // Add hover and selection effects
            addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                        if (!isSelected) {
                            isContentAreaFilled = true
                            background = JBColor(Color(240, 240, 240), Color(60, 60, 60))
                        }
                        repaint()
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent?) {
                        if (!isSelected) {
                            isContentAreaFilled = false
                        }
                        repaint()
                    }
                },
            )

            addItemListener { e ->
                if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                    isContentAreaFilled = true
                    background = JBColor(Color(220, 220, 255), Color(80, 80, 120))
                } else {
                    isContentAreaFilled = false
                }
                repaint()
            }
        }
    }

    private fun showFileSelectionPopup() {
        val model = fileComboBox.model
        if (model.size <= 1) {
            JBPopupFactory.getInstance()
                .createMessage("No files available to add")
                .showInCenterOf(addFileButton)
            return
        }

        val allFiles = (1 until model.size).mapNotNull { model.getElementAt(it) }

        val listModel =
            DefaultListModel<VirtualFile>().apply {
                allFiles.forEach { addElement(it) }
            }

        val fileList =
            JList(listModel).apply {
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                isOpaque = false
                background = JBColor.PanelBackground
                selectionBackground = JBColor(Color(0, 120, 215, 40), Color(0, 120, 215, 60))
                border = JBUI.Borders.empty()
                visibleRowCount = minOf(8, allFiles.size) // Limit visible rows

                cellRenderer =
                    object : DefaultListCellRenderer() {
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
                            foreground = if (isSelected) JBColor.foreground() else JBColor.foreground()
                            icon = file?.fileType?.icon ?: AllIcons.FileTypes.Text
                            background = if (isSelected) selectionBackground else Color(0, 0, 0, 0)
                            border = JBUI.Borders.empty(6, 12, 6, 12)
                            isOpaque = isSelected

                            return comp
                        }
                    }
            }

        // Calculate dynamic width based on content
        val fontMetrics = fileList.getFontMetrics(fileList.font)
        val iconWidth = 16 + 8 // icon + gap
        val padding = 24 // left + right padding
        val maxTextWidth = allFiles.maxOfOrNull { fontMetrics.stringWidth(it.name) } ?: 200
        val dynamicWidth = minOf(maxOf(maxTextWidth + iconWidth + padding, 180), 320)

        val searchField =
            JTextField().apply {
                preferredSize = Dimension(dynamicWidth - 16, 28)
                font = font.deriveFont(Font.PLAIN, 13f)
                margin = Insets(2, 6, 2, 6)
                background = JBColor.PanelBackground
                foreground = JBColor.foreground()
                border =
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor.border(), 1, true),
                        JBUI.Borders.empty(4, 8),
                    )

                document.addDocumentListener(
                    object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) = filter()

                        override fun removeUpdate(e: DocumentEvent?) = filter()

                        override fun changedUpdate(e: DocumentEvent?) = filter()

                        fun filter() {
                            val query = text.trim().lowercase()
                            val filtered = allFiles.filter { it.name.lowercase().contains(query) }
                            listModel.clear()
                            filtered.forEach { listModel.addElement(it) }

                            // Update visible row count based on filtered results
                            fileList.visibleRowCount = minOf(8, filtered.size)
                        }
                    },
                )
            }

        val sectionLabel =
            JLabel("Recent files").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.BOLD, 11f)
                border = JBUI.Borders.empty(6, 12, 4, 12)
            }

        val listScrollPane =
            JScrollPane(fileList).apply {
                isOpaque = false
                viewport.isOpaque = false
                border = BorderFactory.createEmptyBorder()

                // Dynamic height based on content
                val rowHeight = fileList.fixedCellHeight.takeIf { it > 0 } ?: 28
                val maxRows = minOf(8, allFiles.size)
                val calculatedHeight = maxRows * rowHeight + 4

                preferredSize = Dimension(dynamicWidth - 16, calculatedHeight)
                verticalScrollBarPolicy = if (allFiles.size > 8) JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED else JScrollPane.VERTICAL_SCROLLBAR_NEVER
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }

        val contentPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(8, 8, 4, 8)

                add(searchField)
                add(Box.createVerticalStrut(8))
                add(sectionLabel)
                add(Box.createVerticalStrut(2))
                add(listScrollPane)
            }

        val wrapper =
            JPanel(BorderLayout()).apply {
                background = JBColor.PanelBackground
                border =
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor.border(), 1, true),
                        BorderFactory.createEmptyBorder(),
                    )
                add(contentPanel, BorderLayout.CENTER)

                // Set the wrapper size to match content
                preferredSize = Dimension(dynamicWidth, contentPanel.preferredSize.height + 16)
            }

        val popup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(wrapper, searchField)
                .setFocusable(true)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .createPopup()

        fileList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    if (e?.clickCount == 2) {
                        fileList.selectedValue?.let { onFileSelected(it) }
                        popup.closeOk(null)
                    }
                }
            },
        )

        // Add keyboard support
        searchField.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent?) {
                    when (e?.keyCode) {
                        java.awt.event.KeyEvent.VK_DOWN -> {
                            if (fileList.model.size > 0) {
                                fileList.requestFocus()
                                fileList.selectedIndex = 0
                            }
                        }
                        java.awt.event.KeyEvent.VK_ESCAPE -> popup.cancel()
                    }
                }
            },
        )

        fileList.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent?) {
                    when (e?.keyCode) {
                        java.awt.event.KeyEvent.VK_ENTER -> {
                            fileList.selectedValue?.let { onFileSelected(it) }
                            popup.closeOk(null)
                        }
                        java.awt.event.KeyEvent.VK_ESCAPE -> popup.cancel()
                    }
                }
            },
        )

        // popup to the top right of the button
        val buttonLocation = addFileButton.locationOnScreen
        val buttonSize = addFileButton.size
        val popupSize = wrapper.preferredSize

        // calculate position: topright of button, with popup extending upward and rightward
        val x = buttonLocation.x + buttonSize.width - popupSize.width
        val y = buttonLocation.y - popupSize.height - 4 // 4px gap above button

        popup.showInScreenCoordinates(addFileButton, Point(x, y))
    }

    fun addFileTab(file: VirtualFile) {
        if (fileTabs.containsKey(file)) return

        val fileTab =
            JPanel().apply {
                layout = BorderLayout(4, 0)
                background = JBColor(Color(235, 240, 245), Color(70, 75, 80))
                border =
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(Color(200, 210, 220), Color(90, 95, 100)), 1, true),
                        JBUI.Borders.empty(4, 8, 4, 6),
                    )

                val fileNameLabel =
                    JLabel(file.name).apply {
                        foreground = JBColor(Color(50, 60, 70), Color(220, 225, 230))
                        font = UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 12f)
                        border = JBUI.Borders.empty(0, 2, 0, 2)
                    }

                val closeButton =
                    JButton("×").apply {
                        isFocusPainted = false
                        isContentAreaFilled = false
                        isBorderPainted = false
                        border = JBUI.Borders.empty()
                        foreground = JBColor(Color(120, 130, 140), Color(180, 185, 190))
                        font = font.deriveFont(Font.BOLD, 12f)
                        margin = Insets(0, 0, 0, 0)
                        preferredSize = Dimension(16, 16)

                        addMouseListener(
                            object : java.awt.event.MouseAdapter() {
                                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                                    foreground = JBColor(Color(200, 50, 50), Color(255, 100, 100))
                                }

                                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                                    foreground = JBColor(Color(120, 130, 140), Color(180, 185, 190))
                                }
                            },
                        )

                        addActionListener { onFileClose(file) }
                    }

                add(fileNameLabel, BorderLayout.CENTER)
                add(closeButton, BorderLayout.EAST)
            }

        fileTabs[file] = fileTab
        fileTabsPanel.add(fileTab)
        fileTabsPanel.revalidate()
        fileTabsPanel.repaint()
    }

    fun clearInput() {
        inputField.text = ""
        inputField.caretPosition = 0
        updateHeight()
    }

    fun removeFileTab(file: VirtualFile) {
        fileTabs[file]?.let { tab ->
            fileTabsPanel.remove(tab)
            fileTabs.remove(file)
            fileTabsPanel.revalidate()
            fileTabsPanel.repaint()
        }
    }

    // not using rn
    fun updateModelComboBox(models: Array<String>) {
        modelComboBox.removeAllItems()
        models.forEach { modelComboBox.addItem(it) }

        // Recalculate and update size
        val newWidth = modelComboBox.calculateOptimalWidth()
        modelComboBox.preferredSize = Dimension(newWidth, 26)
        modelComboBox.maximumSize = Dimension(newWidth, 26)
        modelComboBox.revalidate()
        modelComboBox.parent?.revalidate()
    }
}
