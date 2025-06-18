package me.code4me.chatWindow.components.inputPanel.components

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.AbstractAction
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Text input component with automatic height adjustment based on content.
 *
 * Provides a multi-line text area that automatically resizes its height as the user
 * types, with support for placeholder text, Enter/Shift+Enter key handling, and
 * focus events. The component grows vertically to accommodate content while
 * maintaining minimum and maximum height constraints.
 *
 * @param onSend Callback invoked when Enter key is pressed (without Shift)
 */
class TextInputComponent(
    private val onSend: () -> Unit,
) : JBPanel<TextInputComponent>(BorderLayout()) {
    /**
     * The main text area for user input with placeholder and focus handling.
     */
    private val textArea = createTextArea()

    /**
     * Scroll pane wrapping the text area for overflow handling.
     */
    private val scrollPane = createScrollPane()


    companion object {
        private const val MIN_HEIGHT = 44
        private const val MAX_HEIGHT = 500
        private const val PADDING = 24
    }

    val text: String get() = textArea.text
    /**
     * Callback invoked when the input gains focus.
     */
    var onFocus: (() -> Unit)? = null

    /**
     * Callback invoked when the input loses focus.
     */
    var onBlur: (() -> Unit)? = null

    init {
        setupLayout()
        setupKeyBindings()
        setupDocumentListener()
        SwingUtilities.invokeLater { updateHeight() }
    }
    /**
     * Creates the main text area with placeholder text and focus handling.
     *
     * Configures word wrapping, styling, and focus listeners that manage
     * placeholder text display and color changes.
     *
     * @return Configured JTextArea with all event handlers
     */
    private fun createTextArea() =
        JTextArea().apply {
            val placeholder = "Ask Code4Me V2!"
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("TextField.font")
            border = JBUI.Borders.empty(8, 12)
            isOpaque = false
            background = JBColor.background()
            foreground = JBColor.GRAY
            text = placeholder

            addFocusListener(
                object : FocusListener {
                    override fun focusGained(e: FocusEvent?) {
                        if (text == placeholder) {
                            text = ""
                            foreground = JBColor.foreground()
                        }
                        onFocus?.invoke()
                    }

                    override fun focusLost(e: FocusEvent?) {
                        if (text.isBlank()) {
                            text = placeholder
                            foreground = JBColor.GRAY
                        }
                        onBlur?.invoke()
                    }
                },
            )
        }
    /**
     * Creates the scroll pane container for the text area.
     *
     * Configures transparent styling and scroll policies for vertical
     * scrolling when content exceeds the maximum height.
     *
     * @return Configured JBScrollPane wrapping the text area
     */
    private fun createScrollPane() =
        JBScrollPane(textArea).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            background = JBColor.background()
        }

    private fun setupLayout() {
        background = JBColor.background()
        isOpaque = true
        add(scrollPane, BorderLayout.CENTER)
    }
    /**
     * Configures keyboard shortcuts for sending messages and inserting newlines.
     *
     * Maps Enter to send action and Shift+Enter to newline insertion,
     * providing intuitive chat-like input behavior.
     */
    private fun setupKeyBindings() {
        val inputMap = textArea.inputMap
        val actionMap = textArea.actionMap

        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "send")
        inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-newline")

        actionMap.put(
            "send",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    onSend()
                }
            },
        )

        actionMap.put(
            "insert-newline",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    textArea.insert("\n", textArea.caretPosition)
                }
            },
        )
    }
    /**
     * Adds document listener to trigger height updates when text content changes.
     *
     * Monitors all text modifications (insertions, deletions, changes) to
     * automatically adjust the component height as needed.
     */
    private fun setupDocumentListener() {
        textArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateHeight()

                override fun removeUpdate(e: DocumentEvent) = updateHeight()

                override fun changedUpdate(e: DocumentEvent) = updateHeight()
            },
        )
    }

    private fun updateHeight() {
        SwingUtilities.invokeLater {
            val availableWidth = calculateAvailableWidth()
            val lineCount = calculateLineCount(textArea.text, textArea.getFontMetrics(textArea.font), availableWidth)
            val newHeight = calculateNewHeight(lineCount, textArea.getFontMetrics(textArea.font))

            updateDimensions(availableWidth, newHeight)
            scrollToCaretPosition()
            refreshLayout()
        }
    }

    private fun calculateAvailableWidth(): Int {
        val containerWidth = parent?.width ?: 0
        return if (containerWidth > 32) containerWidth - 32 else 400
    }
    /**
     * Counts the number of visual lines needed to display the given text.
     *
     * Considers word wrapping by measuring text width against available space
     * and calculating how many lines each paragraph will occupy when wrapped.
     *
     * @param text The text content to measure
     * @param metrics Font metrics for width calculations
     * @param width Available width for text layout
     * @return Total number of visual lines needed
     */

    private fun calculateLineCount(
        text: String,
        metrics: FontMetrics,
        width: Int,
    ): Int {
        if (text.isEmpty()) return 1

        val availableWidth = width - PADDING
        if (availableWidth <= 0) return 1

        return text.split("\n").sumOf { line ->
            if (line.isEmpty()) {
                1
            } else {
                val lineWidth = metrics.stringWidth(line)
                if (lineWidth <= availableWidth) {
                    1
                } else {
                    ceil(lineWidth.toDouble() / availableWidth).toInt()
                }
            }
        }.let { max(1, it) }
    }

    private fun calculateNewHeight(
        lineCount: Int,
        metrics: FontMetrics,
    ): Int {
        val calculatedHeight = (lineCount * metrics.height) + 16
        return min(max(calculatedHeight, MIN_HEIGHT), MAX_HEIGHT)
    }

    private fun updateDimensions(
        availableWidth: Int,
        newHeight: Int,
    ) {
        scrollPane.preferredSize = Dimension(availableWidth, newHeight)
        scrollPane.minimumSize = Dimension(availableWidth, MIN_HEIGHT)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, MAX_HEIGHT)
        textArea.preferredSize = null
    }

    private fun scrollToCaretPosition() {
        SwingUtilities.invokeLater {
            try {
                val caretPos = textArea.caretPosition
                val rect = textArea.modelToView2D(caretPos)
                rect?.let { textArea.scrollRectToVisible(it.bounds) }
            } catch (e: Exception) {
                textArea.caretPosition = textArea.text.length
            }
        }
    }

    private fun refreshLayout() {
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

    fun clearText() {
        textArea.text = ""
        textArea.caretPosition = 0
        updateHeight()
    }

    fun setText(value: String) {
        textArea.text = value
        textArea.caretPosition = value.length
    }
    /**
     * Requests focus for the input area and positions cursor at the end.
     *
     * Brings keyboard focus to the text area and moves the cursor to
     * the end of any existing content for immediate typing.
     */
    fun focusInput() {
        textArea.requestFocusInWindow()
        textArea.caretPosition = textArea.text.length
    }
}
