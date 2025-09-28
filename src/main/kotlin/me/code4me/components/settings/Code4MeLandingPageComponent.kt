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
                Code4Me V2 is an open-source JetBrains plugin that provides code completions and an integrated chat assistant for developers. It is designed with modularity and transparency in mind, which allows researchers and advanced developers to experiment with different AI models, configure data collection, and explore human-AI interaction in software engineering. The plugin supports inline “ghost text” code suggestions, manual completions on demand, and a conversational assistant for context-aware programming help. All features are configurable through an intuitive settings panel which allows for fine-grained control over model selection, context retrieval, and telemetry modules.

                As a research-focused tool, Code4Me V2 prioritizes openness and flexibility over commercial-grade performance. Users have full control over what data is collected, stored, and transmitted; every telemetry and context module can be enabled, disabled, or customized individually. While the plugin includes safeguards (e.g., automatic redaction of potential secrets and GDPR-compliant storage practices), ultimate responsibility lies with the user to configure modules according to their privacy needs and ensure that no sensitive code or data is shared inadvertently.

                By installing and using this plugin, users acknowledge that code completions and chat outputs are suggestions only, not guaranteed to be correct or secure. The plugin is a research prototype, and users remain responsible for reviewing and validating any generated code before integrating it into their projects.
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
