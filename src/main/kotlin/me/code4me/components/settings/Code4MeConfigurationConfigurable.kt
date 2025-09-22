package me.code4me.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.util.ui.FormBuilder
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.sections.ConfigurationSection
import me.code4me.services.state.TOKEN_PROPERTY
import me.code4me.services.state.getAuthState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class ConfigurationConfigurable : SearchableConfigurable {
    override fun getId(): String = "me.code4me.settings.ConfigurationConfigurable"

    private val fieldStates = mutableListOf<StateValueField<*>>()
    private var mainPanel: JPanel? = null
    private val authService = getAuthState()

    /**
     * Listener that responds to authentication token changes by rebuilding the UI.
     */
    private val tokenChangeListener =
        PropertyChangeListener { event ->
            if (event.propertyName == TOKEN_PROPERTY) {
                SwingUtilities.invokeLater { refreshUI() }
            }
        }

    override fun getDisplayName(): String = "Configuration"

    override fun createComponent(): JComponent? {
        // Register for authentication state changes
        authService.addPropertyChangeListener(tokenChangeListener)

        val panel = JPanel(BorderLayout())
        mainPanel = panel
        refreshUI()
        return panel
    }

    private fun refreshUI() {
        mainPanel?.let { panel ->
            panel.removeAll()
            fieldStates.clear()

            if (!authService.isAuthenticated()) {
                // Show login prompt
                panel.add(JLabel("Please sign in first to access configuration settings.", SwingConstants.CENTER), BorderLayout.CENTER)
                val goToLoginButton =
                    JButton("Go to Login").apply {
                        addActionListener {
                            navigateToParentConfigurable()
                        }
                    }
                val buttonPanel =
                    JPanel(FlowLayout()).apply {
                        add(goToLoginButton)
                    }
                panel.add(buttonPanel, BorderLayout.SOUTH)
            } else {
                // Show normal configuration UI for authenticated users
                try {
                    val builder = FormBuilder.createFormBuilder()
                    val configSection =
                        ConfigurationSection()
                    configSection.applyTo(builder, fieldStates)
                    panel.add(builder.panel, BorderLayout.CENTER)
                } catch (e: Exception) {
                    // If ConfigurationSection fails to initialize, show error message
                    panel.add(
                        JLabel("Error loading configuration. Please try signing in again.", SwingConstants.CENTER),
                        BorderLayout.CENTER,
                    )
                }
            }

            panel.revalidate()
            panel.repaint()
        }
    }

    private fun navigateToParentConfigurable() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(100)
                if (dataContext != null) {
                    val settingsDialog = Settings.KEY.getData(dataContext)

                    if (settingsDialog != null) {
                        val mainConfigurable = settingsDialog.find(Code4MeConfigurable::class.java)
                        if (mainConfigurable != null) {
                            settingsDialog.select(mainConfigurable)
                        } else {
                            // Fallback: open new dialog
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(null, Code4MeConfigurable::class.java)
                        }
                    } else {
                        // Fallback: open new dialog
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(null, Code4MeConfigurable::class.java)
                    }
                } else {
                    // Fallback: open new dialog
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(null, Code4MeConfigurable::class.java)
                }
            } catch (e: Exception) {
                // Fallback: open new dialog
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(null, Code4MeConfigurable::class.java)
            }
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun reset() {}

    override fun disposeUIResources() {
        authService.removePropertyChangeListener(tokenChangeListener)
        mainPanel = null
        fieldStates.clear()
    }
}
