package me.code4me.toolWindow.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.Gray
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.TextTransferable
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import java.awt.*
import java.util.regex.Pattern
import javax.swing.*
import javax.swing.border.EmptyBorder

class ChatBubble(
    private val sender: String,
    private val message: String,
    private val isUser: Boolean,
    private val project: Project,
) : JPanel() {
    private val editors = mutableListOf<Editor>()

    init {
        layout = BorderLayout()
        isOpaque = false
        background = Color(0, 0, 0, 0)
        border = EmptyBorder(6, 12, 6, 12)

        val bubbleColor = if (isUser) Gray._40 else Gray._60

        val container = RoundedPanel(bubbleColor, 12)
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.border = EmptyBorder(8, 12, 12, 12)
        container.alignmentX = LEFT_ALIGNMENT

        val senderLabel = JLabel(sender)
        senderLabel.foreground = Color.WHITE
        senderLabel.font = Font("SansSerif", Font.BOLD, 12)
        senderLabel.alignmentX = LEFT_ALIGNMENT
        container.add(senderLabel)
        container.add(Box.createVerticalStrut(4))

        val codeBlocks = extractCodeBlocks(message)

        if (codeBlocks.isNotEmpty()) {
            renderMessageWithCodeBlocks(container, message, codeBlocks)
        } else {
            val virtualFile = LightVirtualFile("chat.md", message)
            val html =
                try {
                    MarkdownUtil.generateMarkdownHtml(virtualFile, message, project)
                } catch (e: Exception) {
                    "<html><body><pre>$message</pre></body></html>"
                }

            val htmlPane = createStyledHtmlPane(html)
            htmlPane.alignmentX = LEFT_ALIGNMENT
            container.add(htmlPane)
        }

        add(container, BorderLayout.CENTER)
    }

    override fun removeNotify() {
        super.removeNotify()
        editors.forEach {
            EditorFactory.getInstance().releaseEditor(it)
        }
        editors.clear()
    }

    private fun extractCodeBlocks(text: String): List<CodeBlock> {
        val codeBlocks = mutableListOf<CodeBlock>()
        val pattern = Pattern.compile("```(\\w+)?\\n([\\s\\S]*?)```", Pattern.MULTILINE)
        val matcher = pattern.matcher(text)

        while (matcher.find()) {
            val language = matcher.group(1) ?: "text"
            val code = matcher.group(2)
            val start = matcher.start()
            val end = matcher.end()
            codeBlocks.add(CodeBlock(language, code, start, end))
        }

        return codeBlocks
    }

    private fun renderMessageWithCodeBlocks(
        container: JPanel,
        message: String,
        codeBlocks: List<CodeBlock>,
    ) {
        var lastIndex = 0

        for (codeBlock in codeBlocks) {
            if (codeBlock.start > lastIndex) {
                val textBefore = message.substring(lastIndex, codeBlock.start)
                if (textBefore.trim().isNotEmpty()) {
                    addTextComponent(container, textBefore)
                }
            }

            addCodeComponent(container, codeBlock)
            lastIndex = codeBlock.end
        }

        if (lastIndex < message.length) {
            val textAfter = message.substring(lastIndex)
            if (textAfter.trim().isNotEmpty()) {
                addTextComponent(container, textAfter)
            }
        }
    }

    private fun addTextComponent(
        container: JPanel,
        text: String,
    ) {
        val virtualFile = LightVirtualFile("chat.md", text)
        val html =
            try {
                MarkdownUtil.generateMarkdownHtml(virtualFile, text, project)
            } catch (e: Exception) {
                "<html><body>$text</body></html>"
            }

        val htmlPane = createStyledHtmlPane(html)
        htmlPane.alignmentX = LEFT_ALIGNMENT
        container.add(htmlPane)
    }

    private fun addCodeComponent(
        container: JPanel,
        codeBlock: CodeBlock,
    ) {
        val fileType =
            FileTypeManager.getInstance().getFileTypeByExtension(codeBlock.language)
                ?: PlainTextFileType.INSTANCE

        val virtualFile = LightVirtualFile("code.${codeBlock.language}", fileType, codeBlock.code)
        val document = EditorFactory.getInstance().createDocument(codeBlock.code)

        val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile, true) as EditorEx
        editors.add(editor)

        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isRightMarginShown = false
        }

        val scheme = EditorColorsManager.getInstance().globalScheme
        editor.colorsScheme = scheme

        val lineCount = codeBlock.code.lines().size
        val lineHeight = editor.lineHeight
        val preferredHeight = (lineCount * lineHeight) + 10

        val editorComponent = editor.component
        editorComponent.preferredSize = Dimension(400, preferredHeight)
        editorComponent.maximumSize = Dimension(Int.MAX_VALUE, preferredHeight)

        val codeBlockWrapper =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }

        val headerPanel =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = EmptyBorder(4, 8, 4, 8)
                background = Gray._50
            }

        val languageLabel =
            JLabel(codeBlock.language.uppercase()).apply {
                foreground = Color.LIGHT_GRAY
                font = Font("Monospaced", Font.BOLD, 10)
            }
        headerPanel.add(languageLabel, BorderLayout.WEST)

        val copyButton =
            JButton("Copy").apply {
                font = Font("SansSerif", Font.PLAIN, 10)
                foreground = Color.WHITE
                background = Gray._70
                border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

        copyButton.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    copyButton.background = Gray._80
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    copyButton.background = Gray._70
                }
            },
        )

        copyButton.addActionListener {
            try {
                CopyPasteManager.getInstance().setContents(TextTransferable(codeBlock.code as CharSequence))
                // Temporarily change button text to show feedback
                val originalText = copyButton.text
                copyButton.text = "Copied!"
                Timer(1000) {
                    copyButton.text = originalText
                }.apply {
                    isRepeats = false
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        headerPanel.add(copyButton, BorderLayout.EAST)

        val scrollPane =
            JBScrollPane(editorComponent).apply {
                preferredSize = Dimension(400, minOf(preferredHeight, 200))
                maximumSize = Dimension(Int.MAX_VALUE, minOf(preferredHeight, 200))
                border = null
            }

        val codePanel =
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createLineBorder(Color.GRAY, 1)
                add(headerPanel, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }

        codeBlockWrapper.add(codePanel, BorderLayout.CENTER)
        codeBlockWrapper.maximumSize = Dimension(Int.MAX_VALUE, codePanel.preferredSize.height)

        container.add(Box.createVerticalStrut(4))
        container.add(codeBlockWrapper)
        container.add(Box.createVerticalStrut(4))
    }

    private fun createStyledHtmlPane(html: String): JEditorPane {
        return JEditorPane("text/html", html).apply {
            isOpaque = false
            isEditable = false
            foreground = Color.WHITE
            font = Font("SansSerif", Font.PLAIN, 14)
            border = null
            caretPosition = 0
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
    }

    override fun getMaximumSize(): Dimension {
        return Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    data class CodeBlock(
        val language: String,
        val code: String,
        val start: Int,
        val end: Int,
    )

    /**
     * Custom JPanel with rounded corners.
     */
    class RoundedPanel(
        private val backgroundColor: Color,
        private val cornerRadius: Int,
    ) : JPanel() {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = backgroundColor
            g2.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius)

            g2.dispose()
        }
    }
}
