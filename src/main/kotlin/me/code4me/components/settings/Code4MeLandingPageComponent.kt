package me.code4me.components.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ex.Settings
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import me.code4me.settings.ConfigurationConfigurable
import me.code4me.settings.UserConfigurable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Landing page component for the main Code4Me V2 settings.
 *
 * This component provides:
 * - Navigation buttons to Configuration and User Profile pages
 * - Information about how the plugin works
 * - Data collection and privacy information
 * - Terms of service information
 *
 * @since 1.0.0
 */
class LandingPageComponent {
    companion object {
        private val LOG = thisLogger()

        // UI Constants
        private const val PADDING = 20
        private const val BUTTON_SPACING = 15
        private const val TEXT_AREA_ROWS = 25
        private const val MIN_WIDTH = 600
        private const val MIN_HEIGHT = 500
        private const val PREFERRED_WIDTH = 700
        private const val PREFERRED_HEIGHT = 600
    }

    /**
     * Main container panel.
     */
    private var mainPanel: JPanel

    init {
        mainPanel = buildUI()
        LOG.debug("LandingPageComponent initialized")
    }

    /**
     * Constructs the complete landing page UI.
     */
    private fun buildUI(): JPanel {
        val panel =
            JPanel(BorderLayout()).apply {
                minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
                preferredSize = Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)
                border = JBUI.Borders.empty(PADDING)
                background = JBColor.background()
            }

        // Header with title
        val headerPanel = createHeaderPanel()
        panel.add(headerPanel, BorderLayout.NORTH)

        // Navigation buttons
        val navigationPanel = createNavigationPanel()
        panel.add(navigationPanel, BorderLayout.CENTER)

        // Information text area
        val informationPanel = createInformationPanel()
        panel.add(informationPanel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Creates the header panel with the main title.
     */
    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border = JBUI.Borders.emptyBottom(8)

            val titleLabel =
                JBLabel("Code4Me V2 Settings").apply {
                    font = font.deriveFont(Font.BOLD, 20f)
                    horizontalAlignment = JLabel.CENTER
                    isFocusable = false
                }

            val subtitleLabel =
                JBLabel("AI-Powered Code Completion and Chat Assistant").apply {
                    font = font.deriveFont(Font.ITALIC, 14f)
                    foreground = JBColor.GRAY
                    horizontalAlignment = JLabel.CENTER
                    border = JBUI.Borders.emptyTop(6)
                    isFocusable = false
                }

            val titleContainer =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = JBColor.background()
                    add(titleLabel)
                    add(subtitleLabel)
                }

            add(titleContainer, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the navigation panel with buttons to access different settings sections.
     */
    private fun createNavigationPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(2, 0, 0, 0)

            val gbc =
                GridBagConstraints().apply {
                    insets = Insets(BUTTON_SPACING, BUTTON_SPACING, BUTTON_SPACING, BUTTON_SPACING)
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                }

            // Configuration button
            val configButton =
                createNavigationButton(
                    "Configuration & Modules",
                    "Manage plugin settings, modules, and preferences",
                ) { navigateToConfiguration() }

            // User Profile button
            val profileButton =
                createNavigationButton(
                    "User Profile",
                    "Manage your account, profile information, and privacy settings",
                ) { navigateToUserProfile() }

            // Add buttons in a 2-column layout
            gbc.gridx = 0
            gbc.gridy = 0
            add(configButton, gbc)

            gbc.gridx = 1
            gbc.gridy = 0
            add(profileButton, gbc)
        }
    }

    /**
     * Creates a styled navigation button.
     */
    private fun createNavigationButton(
        title: String,
        description: String,
        action: () -> Unit,
    ): JComponent {
        return JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border =
                JBUI.Borders.compound(
                    JBUI.Borders.customLineTop(JBColor.border()),
                    JBUI.Borders.empty(15, 15, 15, 15),
                )

            val button =
                JButton(title).apply {
                    font = font.deriveFont(Font.BOLD, 14f)
                    addActionListener { action() }
                    preferredSize = Dimension(250, 40)
                    alignmentX = Component.CENTER_ALIGNMENT
                }

            val descLabel =
                JBTextArea(description).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    background = JBColor.background()
                    font = JLabel().font.deriveFont(12f)
                    border = JBUI.Borders.emptyTop(8)
                    isOpaque = false
                }

            val container =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = JBColor.background()
                    alignmentX = Component.CENTER_ALIGNMENT
                    add(button)
                    add(descLabel)
                }

            add(container, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the information panel with plugin details and terms.
     */
    private fun createInformationPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border =
                JBUI.Borders.compound(
                    JBUI.Borders.customLineTop(JBColor.border()),
                    JBUI.Borders.emptyTop(8),
                )

            val infoTitle =
                JBLabel("About Code4Me V2").apply {
                    font = font.deriveFont(Font.BOLD, 16f)
                    border = JBUI.Borders.emptyBottom(8)
                }

            val infoText = createInformationText()
            val scrollPane =
                JBScrollPane(infoText).apply {
                    preferredSize = Dimension(PREFERRED_WIDTH - 40, 250)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }

            add(infoTitle, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the informational text area with plugin details.
     */
    private fun createInformationText(): JBTextArea {
        val informationContent =
            """


            Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nullam dignissim semper quam, eget ornare arcu semper in. Nulla facilisi.
            Nullam pharetra vulputate ante ut bibendum. Quisque nec neque libero. Proin sit amet condimentum velit, nec sagittis justo.
            Aenean tristique tempor justo, ac feugiat purus fringilla sit amet. Sed interdum leo quam, quis dapibus justo feugiat sed. In eget nisl porttitor risus congue sagittis id eget nulla. Sed nec metus pulvinar, faucibus magna in, congue sem. Suspendisse ac facilisis massa. Aenean non libero odio. Duis condimentum ante nec lectus commodo scelerisque. Pellentesque cursus, nunc sed sodales malesuada, orci dolor hendrerit tellus, sed placerat odio libero suscipit nibh. In hac habitasse platea dictumst.

            Vestibulum quis erat laoreet, ullamcorper justo porttitor, blandit tellus.
            Pellentesque condimentum, enim id auctor auctor, sem eros bibendum ex, eleifend luctus quam lorem sit amet felis. Integer vitae est varius, fringilla arcu vehicula, dapibus dui. Nulla tincidunt, ipsum id maximus rutrum, augue lorem suscipit risus, vel ullamcorper augue quam in odio. Ut maximus id augue in consequat. Vestibulum id tempus odio, non consequat augue. Aliquam fringilla sem orci, a dignissim quam hendrerit nec. Pellentesque ultricies arcu non ornare pharetra. Mauris dapibus posuere erat sit amet feugiat.

            Nulla ut tortor at metus maximus aliquet et non tortor.
            Class aptent taciti sociosqu ad litora torquent per conubia nostra, per inceptos himenaeos. Vivamus ornare posuere interdum. Vestibulum eu massa dolor. Phasellus dictum ex et nulla condimentum aliquam. Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia curae; Vestibulum felis sapien, maximus id fermentum vel, facilisis eu mi. Pellentesque eget mi placerat risus consequat tristique eu vitae metus. Quisque dapibus iaculis nisi sit amet accumsan. Donec hendrerit quis erat ut consectetur. In accumsan urna in purus pretium gravida.

            Duis pellentesque quam eu quam convallis iaculis. Suspendisse posuere nunc vel lorem sagittis, sed porta erat tristique. Praesent nisl eros, hendrerit id libero at, tincidunt feugiat tortor. Vestibulum dignissim venenatis quam. Quisque et fringilla velit, vitae congue ligula. Integer gravida elit erat, non eleifend magna aliquet sit amet. Etiam in luctus magna, vel pretium elit. Sed mollis lacinia eros a sodales.
            Integer sapien felis, interdum sed erat sed, ultricies tempor nisl. Ut id venenatis nibh. Morbi maximus semper scelerisque. In in turpis quis elit luctus porttitor ullamcorper sodales libero. Vivamus volutpat, nunc in pharetra faucibus, ante nulla vehicula dui, ut accumsan sapien diam nec ante. Quisque mattis libero non ante dignissim, ac faucibus lectus tempor. Aenean sagittis leo ut est bibendum, id placerat mauris vehicula. Nam eget eros lectus.

            Vivamus efficitur nunc quis orci dignissim vestibulum consequat id odio. Integer efficitur id velit ut convallis. Duis pretium lorem nisi, vel ultrices ipsum laoreet ac. Integer iaculis finibus nisl at consectetur. Etiam scelerisque quam vel tellus finibus finibus. Curabitur ultricies ipsum eu feugiat rutrum. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Ut efficitur tellus magna, eu fermentum est tincidunt ac. Morbi maximus semper lorem, nec sollicitudin quam iaculis et. Donec gravida arcu ante, sit amet efficitur tortor eleifend id. Sed tellus justo, venenatis sed quam vitae, venenatis vehicula nisi. Proin ornare diam in leo iaculis, ac tristique nisi ultrices. Fusce sit amet semper nisi.
            """.trimIndent()

        return JBTextArea(informationContent).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = JBColor.background()
            font = JLabel().font
            border = JBUI.Borders.empty(5)
            rows = TEXT_AREA_ROWS
        }
    }

    /**
     * Navigates to the Configuration settings page.
     */
    private fun navigateToConfiguration() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(100)
                if (dataContext != null) {
                    val settingsDialog = Settings.KEY.getData(dataContext)

                    if (settingsDialog != null) {
                        val configurable = settingsDialog.find(ConfigurationConfigurable::class.java)
                        if (configurable != null) {
                            settingsDialog.select(configurable)
                            LOG.debug("Successfully navigated to Configuration page")
                        } else {
                            LOG.warn("Could not find Configuration configurable")
                            // Fallback: open new dialog
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(null, ConfigurationConfigurable::class.java)
                        }
                    } else {
                        // Fallback: open new dialog
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(null, ConfigurationConfigurable::class.java)
                    }
                } else {
                    // Fallback: open new dialog
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(null, ConfigurationConfigurable::class.java)
                }
            } catch (e: Exception) {
                LOG.error("Failed to navigate to Configuration page", e)
                // Fallback: open new dialog
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(null, ConfigurationConfigurable::class.java)
            }
        }
    }

    /**
     * Navigates to the User Profile settings page.
     */
    private fun navigateToUserProfile() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(100)
                if (dataContext != null) {
                    val settingsDialog = Settings.KEY.getData(dataContext)

                    if (settingsDialog != null) {
                        val configurable = settingsDialog.find(UserConfigurable::class.java)
                        if (configurable != null) {
                            settingsDialog.select(configurable)
                            LOG.debug("Successfully navigated to User Profile page")
                        } else {
                            LOG.warn("Could not find User Profile configurable")
                            // Fallback: open new dialog
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(null, UserConfigurable::class.java)
                        }
                    } else {
                        // Fallback: open new dialog
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(null, UserConfigurable::class.java)
                    }
                } else {
                    // Fallback: open new dialog
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(null, UserConfigurable::class.java)
                }
            } catch (e: Exception) {
                LOG.error("Failed to navigate to User Profile page", e)
                // Fallback: open new dialog
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(null, UserConfigurable::class.java)
            }
        }
    }

    /**
     * Returns the main panel containing all UI components.
     */
    fun getPanel(): JPanel = mainPanel

    /**
     * Performs cleanup when the component is being disposed.
     */
    fun dispose() {
        LOG.debug("LandingPageComponent disposed")
    }
}
