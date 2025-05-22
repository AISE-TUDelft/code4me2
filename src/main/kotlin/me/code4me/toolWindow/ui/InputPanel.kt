package me.code4me.toolWindow.chatPanelUI

import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.border.Border

class InputPanel(
    private val onSend: () -> Unit,
    private val onWebToggle: (Boolean) -> Unit,
    private val onFileSelect: () -> Unit,
    private val borderColor: Color,
) : JBPanel<InputPanel>(BorderLayout()) {
    val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val plusButton = JButton("+")
    private val webButton = JButton("Web")

    private var webEnabled = false

    init {
        background = Color(43, 43, 43)

        val rightPanel =
            JBPanel<Nothing>(FlowLayout(FlowLayout.RIGHT)).apply {
                isOpaque = false
                add(plusButton)
                add(webButton)
                add(sendButton)
            }

        add(inputField, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

        styleButtons()
        bindEvents()
    }

    private fun styleButtons() {
        plusButton.apply {
            preferredSize = Dimension(28, 28)
            font = Font("SansSerif", Font.BOLD, 14)
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = Color(75, 75, 75)
            foreground = Color.WHITE
            border = roundedBorder(10, borderColor)
        }

        webButton.apply {
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = Color(60, 63, 65)
            foreground = Color.WHITE
            preferredSize = Dimension(50, 28)
            font = Font("SansSerif", Font.PLAIN, 12)
            horizontalAlignment = SwingConstants.CENTER
            border = roundedBorder(15, borderColor)
        }
    }

    private fun bindEvents() {
        sendButton.addActionListener { onSend() }
        inputField.addActionListener { onSend() }

        webButton.addActionListener {
            webEnabled = !webEnabled
            webButton.background = if (webEnabled) Color(75, 110, 175) else Color(60, 63, 65)
            onWebToggle(webEnabled)
        }

        plusButton.addActionListener { onFileSelect() }
    }

    /**
     *  rounded borders for buttons
     *  //TODO maybe move to a utils class so others can easily use it
     */
    private fun roundedBorder(
        radius: Int,
        color: Color,
    ): Border {
        return object : Border {
            override fun getBorderInsets(c: Component) = Insets(4, 10, 4, 10)

            override fun isBorderOpaque() = false

            override fun paintBorder(
                c: Component,
                g: Graphics,
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ) {
                val g2 = g as Graphics2D
                g2.color = color
                g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
            }
        }
    }
}
