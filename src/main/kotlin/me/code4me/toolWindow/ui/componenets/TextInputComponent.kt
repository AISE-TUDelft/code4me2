package me.code4me.toolWindow.chatPanelUI

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Component responsible for text input with auto-resizing functionality
 */
class TextInputComponent(
    private val onSend: () -> Unit,
) : JBPanel<TextInputComponent>(BorderLayout()) {
    private val textArea = createTextArea()
    private val scrollPane = createScrollPane()

    companion object {
        private const val MIN_HEIGHT = 44
        private const val MAX_HEIGHT = 500
        private const val PADDING = 24
    }

    val text: String get() = textArea.text

    init {
        setupLayout()
        setupKeyBindings()
        setupDocumentListener()
        SwingUtilities.invokeLater { updateHeight() }
    }

    private fun createTextArea() =
        JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("TextField.font")
            border = JBUI.Borders.empty(8, 12)
            isOpaque = false
            background = JBColor.background()
            caretPosition = 0
        }

    private fun createScrollPane() =
        JBScrollPane(textArea).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            background = JBColor.background()
        }

    private fun setupLayout() {
        background = JBColor.background()
        isOpaque = true
        add(scrollPane, BorderLayout.CENTER)
    }

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
                    textArea.append("\n")
                }
            },
        )
    }

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
                val rect = textArea.modelToView(caretPos)
                rect?.let { textArea.scrollRectToVisible(it) }
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
}
