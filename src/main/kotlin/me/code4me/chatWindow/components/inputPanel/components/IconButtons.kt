package me.code4me.chatWindow.components.inputPanel.components

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton


/**
 * Reusable icon button with circular hover effects.
 *
 * A 26x26 icon button that displays a circular gray background when hovered.
 * Designed for toolbar and panel actions with consistent styling across the plugin.
 *
 * @param icon The icon to display on the button
 * @param tooltip The tooltip text shown on hover
 * @param action Callback invoked when the button is clicked
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
        margin = JBUI.insets(2)

        addActionListener { action() }
        setupHoverEffect()
    }
    /**
     * Configures mouse listeners for hover state tracking.
     */
    private fun setupHoverEffect() {
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent?) {
                    isHovered = false
                    repaint()
                }
            },
        )
    }
    /**
     * Custom painting to draw circular hover background when needed.
     */
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D

        if (isHovered) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Gray._240, Gray._60)
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
    private val clickIcon: Icon,
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
        margin = JBUI.emptyInsets()

        addActionListener { action() }

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    icon = hoverIcon
                }

                override fun mouseExited(e: MouseEvent?) {
                    icon = defaultIcon
                }

                override fun mousePressed(e: MouseEvent?) {
                    icon = clickIcon
                }

                override fun mouseReleased(e: MouseEvent?) {
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
    private val selectedIcon = IconLoader.getIcon("/icons/webService_selected.svg", InputControlsComponent::class.java)
    private val unselectedIcon = IconLoader.getIcon("/icons/webService_notSelected.svg", InputControlsComponent::class.java)

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
        margin = JBUI.emptyInsets()

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
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    // overriding with empty so it doesn't add unwanted hover effects
                }

                override fun mouseExited(e: MouseEvent?) {
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
            margin = JBUI.emptyInsets()
            preferredSize = Dimension(16, 16)
            toolTipText = tooltip
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(Color(120, 130, 140), Color(180, 185, 190))

            addActionListener { onClick() }
            setupHoverEffect()
        }

        private fun setupHoverEffect() {
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        foreground = JBColor(Color(200, 50, 50), Color(255, 100, 100))
                    }

                    override fun mouseExited(e: MouseEvent?) {
                        foreground = JBColor(Color(120, 130, 140), Color(180, 185, 190))
                    }
                },
            )
        }
    }
}
