package me.code4me.chatWindow.components.chatDisplayPanel.components

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.TextTransferable
import me.code4me.services.config.getConfig
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.regex.Pattern
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

/**
 * Chat bubble component that displays messages in the Code4Me chat interface.
 *
 * Renders messages with support for markdown formatting, syntax-highlighted code blocks,
 * and interactive elements like copy buttons and regenerate options. The bubble automatically
 * styles itself differently for user and assistant messages.
 *
 * @param sender The name of the message sender (e.g., "You", "Code4Me V2")
 * @param message The message content, supporting markdown and code blocks
 * @param isUser Whether this message is from the user (affects styling and available actions)
 * @param project The IntelliJ project for editor creation and file type detection
 * @param onRegenerate Optional callback for regenerating assistant responses
 * @param onEdit Optional callback for editing user messages
 * @param showRegenerate Whether to show the regenerate button for assistant messages
 */
class ChatBubble(
    sender: String,
    message: String,
    isUser: Boolean,
    private val project: Project,
    private val onRegenerate: (() -> Unit)? = null,
    private val onEdit: (() -> Unit)? = null,
    private val showRegenerate: Boolean = true,
) : JPanel() {
    /**
     * List of IntelliJ editors created for code blocks within this bubble.
     * Tracked for proper disposal to prevent memory leaks.
     */
    private val editors = mutableListOf<Editor>()

    /**
     * Flag indicating whether this bubble has been disposed.
     */
    private var isDisposed = false

    /**
     * The main message display pane for non-code content.
     */
    private var messagePane: JEditorPane? = null

    init {
        layout = BorderLayout()
        isOpaque = false
        background = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        border = JBUI.Borders.empty(6, 12)

        val bubbleColor = if (isUser) Gray._40 else Gray._60

        val container = RoundedPanel(bubbleColor, 12)
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.border = JBUI.Borders.empty(8, 12, 12, 12)
        container.alignmentX = LEFT_ALIGNMENT

        val senderPanel =
            JPanel().apply {
                layout = BorderLayout()
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }

        val iconPath: String? =
            when (sender) {
                "You" -> "/icons/user_dark.svg"
                "Code4Me V2" -> "/icons/pluginIcon_chatSize.svg"
                else -> null
            }
        if (iconPath == null) {
            throw IllegalArgumentException("Unknown sender: $sender")
        }
        val icon = IconLoader.getIcon(iconPath, ChatBubble::class.java)
        val iconLabel = JLabel(icon)
        iconLabel.border = JBUI.Borders.emptyRight(5)

        val senderLabel =
            JLabel(sender).apply {
                foreground = Color.WHITE
                font = Font("SansSerif", Font.BOLD, 12)
            }

        val leftPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(iconLabel)
                add(senderLabel)
            }

        senderPanel.add(leftPanel, BorderLayout.WEST)

        if (isUser && onEdit != null) {
            val editButton =
                JButton("✎").apply {
                    toolTipText = "Edit this message"
                    font = Font("SansSerif", Font.PLAIN, 11)
                    foreground = Color.LIGHT_GRAY
                    isFocusPainted = false
                    isContentAreaFilled = false
                    isBorderPainted = false
                    isOpaque = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = JBUI.Borders.empty()
                }
            editButton.addActionListener { onEdit.invoke() }

            senderPanel.add(editButton, BorderLayout.EAST)
        }

        container.add(senderPanel)
        container.add(Box.createVerticalStrut(4))

        val fixedMessage = fixUnclosedCodeBlocks(message)
        val codeBlocks = extractCodeBlocks(fixedMessage)

        if (codeBlocks.isNotEmpty()) {
            renderMessageWithCodeBlocks(container, fixedMessage, codeBlocks)
        } else {
            val virtualFile = LightVirtualFile("chat.md", fixedMessage)
            val html =
                try {
                    MarkdownUtil.generateMarkdownHtml(virtualFile, fixedMessage, project)
                } catch (e: Exception) {
                    "<html><body><pre>$fixedMessage</pre></body></html>"
                }

            val htmlPane = createStyledHtmlPane(html)
            messagePane = htmlPane
            htmlPane.alignmentX = LEFT_ALIGNMENT
            container.add(htmlPane)
        }
        if (!isUser && onRegenerate != null && showRegenerate) {
            container.add(Box.createVerticalStrut(8))

            val regenerateButton =
                JButton("↻").apply {
                    toolTipText = "Regenerate this response"
                    font = Font("SansSerif", Font.PLAIN, 12)
                    foreground = Color.WHITE
                    isFocusPainted = false
                    isContentAreaFilled = false
                    isBorderPainted = false
                    isOpaque = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

            regenerateButton.addActionListener {
                onRegenerate.invoke()
            }

            val buttonWrapper =
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = LEFT_ALIGNMENT
                    preferredSize = Dimension(container.maximumSize.width, regenerateButton.preferredSize.height)
                    maximumSize = Dimension(Int.MAX_VALUE, regenerateButton.preferredSize.height)
                    add(regenerateButton)
                }

            container.add(buttonWrapper)
        }

        add(container, BorderLayout.CENTER)
    }

    override fun removeNotify() {
        super.removeNotify()
        disposeEditors()
    }

    /**
     * Releases all IntelliJ editors created for code blocks to prevent memory leaks.
     * Should be called when the bubble is no longer needed.
     */
    fun disposeEditors() {
        if (!isDisposed) {
            editors.forEach { editor ->
                try {
                    if (!editor.isDisposed) {
                        EditorFactory.getInstance().releaseEditor(editor)
                    }
                } catch (e: Exception) {
                    // Log but don't throw to avoid cascade failures
                    println("Error disposing editor: ${e.message}")
                }
            }
            editors.clear()
            isDisposed = true
        }
    }

    /**
     * Ensures markdown code blocks are properly closed by adding missing closing markers.
     * @param text The message text to fix
     * @return Text with properly closed code blocks
     */
    private fun fixUnclosedCodeBlocks(text: String): String {
        val parts = text.split("```")

        if (parts.size % 2 == 0) {
            return text + "\n```"
        }

        return text
    }

    /**
     * Extracts code blocks from markdown text using regex pattern matching.
     * @param text The message text to parse
     * @return List of detected code blocks with their positions
     */
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

    /**
     * Renders a message that contains code blocks by separating text and code sections.
     * @param container The parent container to add components to
     * @param message The complete message text
     * @param codeBlocks List of detected code blocks
     */
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

    /**
     * Adds a text component with markdown rendering to the container.
     * @param container The parent container
     * @param text The text content to render
     */
    private fun addTextComponent(
        container: JPanel,
        text: String,
    ) {
        // format the text to contain a <llm-snippet-file> tag with a closing </llm-snippet-file> tag
        //      before the first ``` so that it can be rendered as HTML
        //      this is a workaround to make sure the text is rendered correctly
        val formattedText =
            text.replace(
                Regex("```([\\s\\S]*?)```"),
            ) { "<llm-snippet-file>chat.md</llm-snippet-file>\n${it.groupValues[1]}" }

        val virtualFile = LightVirtualFile("chat.md", formattedText)
        val html =
            try {
                MarkdownUtil.generateMarkdownHtml(virtualFile, text, project)
            } catch (e: Exception) {
                "<html><body>$formattedText</body></html>"
            }

        val htmlPane = createStyledHtmlPane(html)
        htmlPane.alignmentX = LEFT_ALIGNMENT
        container.add(htmlPane)
    }

    /**
     * Creates a syntax-highlighted code editor component for a code block.
     * @param container The parent container
     * @param codeBlock The code block data to render
     */
    private fun addCodeComponent(
        container: JPanel,
        codeBlock: CodeBlock,
    ) {
        if (isDisposed) return

        val ext = getExtensionForLanguage(codeBlock.language)
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)

        // Debug logging
        println("Language: '${codeBlock.language}' -> Extension: '$ext' -> FileType: ${fileType.name} (${fileType.defaultExtension})")

        val virtualFile = LightVirtualFile("code.$ext", fileType, codeBlock.code)
        val document = EditorFactory.getInstance().createDocument(codeBlock.code)

        val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile, true) as EditorEx
        editors.add(editor)

        // Force refresh the color scheme to prevent green background issue
        val scheme = EditorColorsManager.getInstance().globalScheme.clone()
        editor.colorsScheme = scheme as EditorColorsScheme

        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isRightMarginShown = false
        }

        // Disable the editor's internal scrollbars
        editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

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
                background = Gray._40
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }

        val languageLabel =
            JLabel(codeBlock.language.uppercase()).apply {
                // Should stay so for now since JBColor is theme aware whcih results in text not being visible with current bubble colors. TODO make all colors theme aware
                foreground = Color.WHITE
                font = Font("Monospaced", Font.BOLD, 10)
            }
        headerPanel.add(languageLabel, BorderLayout.WEST)

        val copyButton =
            JButton("⧉").apply {
                toolTipText = "Copy code"
                font = Font("SansSerif", Font.PLAIN, 10)
                foreground = Color.WHITE
                isFocusPainted = false
                isContentAreaFilled = false
                isBorderPainted = false
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(28, 18)
            }

        copyButton.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                }

                override fun mouseExited(e: MouseEvent?) {
                }
            },
        )
        copyButton.addActionListener {
            try {
                CopyPasteManager.getInstance().setContents(TextTransferable(codeBlock.code as CharSequence))

                // Create the popup label
                val popup = JWindow()
                val label =
                    JLabel("Copied").apply {
                        // Should stay so for now since JBColor is theme aware whcih results in text not being visible with current bubble colors. TODO make all colors theme aware
                        foreground = Color.WHITE
                        background = Gray._60
                        isOpaque = true
                        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                        font = Font("SansSerif", Font.PLAIN, 12)
                    }
                popup.contentPane.add(label)
                popup.pack()

                // Position popup near the button
                val location = copyButton.locationOnScreen
                popup.setLocation(location.x, location.y - popup.height - 5)
                popup.isVisible = true

                Timer(900) {
                    popup.isVisible = false
                    popup.dispose()
                }.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        headerPanel.add(copyButton, BorderLayout.EAST)

        val scrollPane =
            JBScrollPane(editorComponent).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                preferredSize = Dimension(400, minOf(preferredHeight, 200))
                maximumSize = Dimension(Int.MAX_VALUE, minOf(preferredHeight, 600))
                border = null
            }
        scrollPane.viewport.background = Gray._25
        scrollPane.border = BorderFactory.createLineBorder(JBColor.DARK_GRAY, 1)

        val codePanel =
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createLineBorder(JBColor.GRAY, 1)
                add(headerPanel, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }

        codeBlockWrapper.add(codePanel, BorderLayout.CENTER)
        codeBlockWrapper.maximumSize = Dimension(Int.MAX_VALUE, codePanel.preferredSize.height)

        container.add(Box.createVerticalStrut(4))
        container.add(codeBlockWrapper)
        container.add(Box.createVerticalStrut(4))
    }

    /**
     * Creates a styled HTML pane for displaying rich text content.
     * @param html The HTML content to display
     * @return Configured JEditorPane for HTML rendering
     */
    private fun createStyledHtmlPane(html: String): JEditorPane {
        return JEditorPane("text/html", html).apply {
            isOpaque = false
            isEditable = false
            // Should stay so for now since JBColor is theme aware whcih results in text not being visible with current bubble colors. TODO make all colors theme aware
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

    /**
     * Data class representing a code block within a chat message.
     * @param language The programming language of the code block
     * @param code The actual code content
     * @param start Start position in the original message text
     * @param end End position in the original message text
     */
    data class CodeBlock(
        val language: String,
        val code: String,
        val start: Int,
        val end: Int,
    )

    /**
     * Custom JPanel that renders with rounded corners for chat bubble styling.
     * @param backgroundColor The background color for the panel
     * @param cornerRadius The radius for rounded corners in pixels
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

    /**
     * Maps programming language names to their corresponding file extensions.
     * Uses both direct mapping and fuzzy matching with plugin configuration.
     * @param language The language name from the code block
     * @return The appropriate file extension for syntax highlighting
     */
    private fun getExtensionForLanguage(language: String): String {
        // Create a mapping from language names to file extensions
        val languageToExtension =
            mapOf(
                "java" to "java",
                "kotlin" to "kt",
                "python" to "py",
                "javascript" to "js",
                "typescript" to "ts",
                "shell script" to "sh",
                "bash" to "sh",
                "sh" to "sh",
                "c#" to "cs",
                "c++" to "cpp",
                "c/c++" to "cpp",
                "cpp" to "cpp",
                "c" to "c",
                "go" to "go",
                "rust" to "rs",
                "php" to "php",
                "ruby" to "rb",
                "swift" to "swift",
                "scala" to "scala",
                "groovy" to "groovy",
                "html" to "html",
                "css" to "css",
                "scss" to "scss",
                "sass" to "sass",
                "less" to "less",
                "xml" to "xml",
                "json" to "json",
                "yaml" to "yml",
                "yml" to "yml",
                "toml" to "toml",
                "ini" to "ini",
                "markdown" to "md",
                "md" to "md",
                "sql" to "sql",
                "dockerfile" to "dockerfile",
                "makefile" to "makefile",
                "gradle" to "gradle",
                "properties" to "properties",
                "vuejs" to "vue",
                "vue" to "vue",
                "jsx" to "jsx",
                "tsx" to "tsx",
                "dart" to "dart",
                "r" to "r",
                "matlab" to "m",
                "perl" to "pl",
                "lua" to "lua",
                "powershell" to "ps1",
                "batch" to "bat",
                "vb" to "vb",
                "f#" to "fs",
                "clojure" to "clj",
                "haskell" to "hs",
                "erlang" to "erl",
                "elixir" to "ex",
                "coffeescript" to "coffee",
                "tex" to "tex",
                "latex" to "tex",
                "asm" to "asm",
                "assembly" to "asm",
            )

        // First try direct mapping with the provided language
        val normalizedLanguage = language.lowercase().trim()
        languageToExtension[normalizedLanguage]?.let { return it }

        // If direct mapping fails, try fuzzy matching against config
        val languagesConfig = getConfig().getLanguagesConfig() ?: return "txt"
        val langId = languagesConfig.getLanguageIdFuzzy(language)

        // Find the language name from config
        val configLanguageName =
            languagesConfig.languageMap.entries
                .find { it.value == langId }?.key?.lowercase()?.trim()

        if (configLanguageName != null) {
            languageToExtension[configLanguageName]?.let { return it }

            // Try some additional mappings for config language names
            when {
                configLanguageName.contains("java") -> return "java"
                configLanguageName.contains("kotlin") -> return "kt"
                configLanguageName.contains("python") -> return "py"
                configLanguageName.contains("javascript") -> return "js"
                configLanguageName.contains("typescript") -> return "ts"
                configLanguageName.contains("shell") -> return "sh"
                configLanguageName.contains("c#") -> return "cs"
                configLanguageName.contains("c++") || configLanguageName.contains("c/c++") -> return "cpp"
                configLanguageName.contains("go") && !configLanguageName.contains("django") -> return "go"
                configLanguageName.contains("html") -> return "html"
                configLanguageName.contains("css") -> return "css"
                configLanguageName.contains("json") -> return "json"
                configLanguageName.contains("xml") -> return "xml"
                configLanguageName.contains("yaml") -> return "yml"
                configLanguageName.contains("sql") -> return "sql"
                configLanguageName.contains("markdown") -> return "md"
                configLanguageName.contains("dockerfile") -> return "dockerfile"
                configLanguageName.contains("vue") -> return "vue"
                configLanguageName.contains("groovy") -> return "groovy"
                configLanguageName.contains("scala") -> return "scala"
                configLanguageName.contains("swift") -> return "swift"
                configLanguageName.contains("rust") -> return "rs"
                configLanguageName.contains("php") -> return "php"
                configLanguageName.contains("ruby") -> return "rb"
            }
        }

        return "txt"
    }

    /**
     * Resets editor color schemes to fix rendering issues.
     * Called when theme changes or color issues are detected.
     */
    fun forceResetEditorColors() {
        if (isDisposed) return
        for (editor in editors) {
            if (editor is EditorEx && !editor.isDisposed) {
                val scheme = EditorColorsManager.getInstance().globalScheme.clone()
                editor.colorsScheme = scheme as EditorColorsScheme
            }
        }
    }

    /**
     * Updates only the message text content without rebuilding the entire bubble.
     * Used for streaming message updates to prevent flickering during generation.
     * @param newMessage The updated message content
     */
    fun updateMessageTextOnly(newMessage: String) {
        if (isDisposed) return
        val fixedMessage = fixUnclosedCodeBlocks(newMessage)
        val virtualFile = LightVirtualFile("chat.md", fixedMessage)
        val html =
            try {
                MarkdownUtil.generateMarkdownHtml(virtualFile, fixedMessage, project)
            } catch (e: Exception) {
                "<html><body><pre>$fixedMessage</pre></body></html>"
            }
        messagePane?.text = html
    }
}
