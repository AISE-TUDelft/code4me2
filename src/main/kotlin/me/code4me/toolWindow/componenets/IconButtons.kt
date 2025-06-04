package me.code4me.toolWindow.componenets

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import me.code4me.toolWindow.chatPanelUI.ControlsComponent
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton

/**
 * Reusable icon button with hover effects
 */
class IconButton(
    icon: Icon,
    tooltip: String,
    action: () -> Unit,
) : JButton(icon) {
    private var isHovered = false

    init {
        toolTipText = tooltip
        preferredSize = Dimension(26, 26)
        maximumSize = Dimension(26, 26)
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        margin = Insets(2, 2, 2, 2)

        addActionListener { action() }
        setupHoverEffect()
    }

    private fun setupHoverEffect() {
        addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    isHovered = false
                    repaint()
                }
            },
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D

        if (isHovered) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(240, 240, 240), Color(60, 60, 60))
            g2.fillOval(0, 0, width, height)
        }

        super.paintComponent(g)
        g2.dispose()
    }
}

/**
 * Reusable icon button without hover effects but with click effects
 */
class CleanIconButton(
    private val defaultIcon: Icon,
    private val hoverIcon: Icon,
    private val clickIcon: Icon, // NEW
    tooltip: String,
    action: () -> Unit,
) : JButton(defaultIcon) {
    init {
        toolTipText = tooltip
        preferredSize = Dimension(16, 16)
        maximumSize = Dimension(16, 16)
        minimumSize = Dimension(16, 16)

        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isFocusable = false
        isOpaque = false
        background = null
        border = null
        margin = Insets(0, 0, 0, 0)

        addActionListener { action() }

        addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    icon = hoverIcon
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    icon = defaultIcon
                }

                override fun mousePressed(e: java.awt.event.MouseEvent?) {
                    icon = clickIcon
                }

                override fun mouseReleased(e: java.awt.event.MouseEvent?) {
                    icon = if (contains(e!!.point)) hoverIcon else defaultIcon
                }
            },
        )
    }
}

/**
 * Reusable icon toggle button with selection states and custom icons
 */
class IconToggleButton(
    tooltip: String,
    action: (Boolean) -> Unit,
) : JToggleButton() {
    private val selectedIcon = IconLoader.getIcon("/icons/webService_selected.svg", ControlsComponent::class.java)
    private val unselectedIcon = IconLoader.getIcon("/icons/webService_notSelected.svg", ControlsComponent::class.java)

    init {
        icon = unselectedIcon
        toolTipText = tooltip
        preferredSize = Dimension(16, 16)
        maximumSize = Dimension(16, 16)
        minimumSize = Dimension(16, 16)

        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isFocusable = false
        margin = Insets(0, 0, 0, 0)

        background = null
        border = null

        addActionListener {
            updateIcon()
            action(isSelected)
        }

        setupEffects()
    }

    private fun updateIcon() {
        icon = if (isSelected) selectedIcon else unselectedIcon
    }

    private fun setupEffects() {
        addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    // overriding with empty so it doesn't add unwanted hover effects
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    // overriding with empty so it doesn't add unwanted hover effects
                }
            },
        )

        addItemListener { e ->
            updateIcon()
            repaint()
        }
    }

    /**
     * Reusable "X" close button with hover effects
     */
    class CloseIconButton(
        tooltip: String = "Close",
        onClick: () -> Unit,
    ) : JButton("×") {
        init {
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            isFocusable = false
            margin = Insets(0, 0, 0, 0)
            preferredSize = Dimension(16, 16)
            toolTipText = tooltip
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(Color(120, 130, 140), Color(180, 185, 190))

            addActionListener { onClick() }
            setupHoverEffect()
        }

        private fun setupHoverEffect() {
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
        }
    }
}
