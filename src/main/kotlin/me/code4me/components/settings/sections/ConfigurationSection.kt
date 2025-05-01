package me.code4me.components.settings.sections

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import me.code4me.components.settings.fields.*
import me.code4me.services.state.AuthState
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.EmptyBorder

class ConfigurationSection : SettingsSection {
    private val storeContextField = JCheckBox()
    private val storeContextFieldSVF = object : ToggleButtonField(storeContextField) {
        override fun getStateValue(): Boolean? {
            return storeContextField.isSelected
        }

        override fun setStateValue(value: Boolean) {
            storeContextField.isSelected = value
        }

    }

    private val storeCompletionField = JCheckBox()
    private val storeCompletionFieldSVF = object : ToggleButtonField(storeCompletionField) {
        override fun getStateValue(): Boolean? {
            return storeCompletionField.isSelected
        }

        override fun setStateValue(value: Boolean) {
            storeCompletionField.isSelected = value
        }

    }

    private val storeCompletionsLabel = JLabel("Store Completions")
    private val storeContextLabel = JLabel("Store Context")

    // User information section components
    private val userInfoTitleLabel = JBLabel("User Information").apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        border = EmptyBorder(0, 0, 5, 0)
    }

    private val signOutButton = JButton("Sign Out")

    // Reference to the auth state
    private val authState = getAuthState()

    init {
        storeCompletionField.isSelected = getPrefState().storeCompletions
        storeContextField.isSelected = getPrefState().storeContext
    }

    override fun applyTo(builder: FormBuilder, stateValueFields: MutableList<StateValueField<*>>) {
        stateValueFields.addAll(
            listOf(
                storeCompletionFieldSVF,
                storeContextFieldSVF
            )
        )

        val mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
        }

        // Create content panel with BorderLayout
        val contentPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0)
        }

        // User information panel
        val userInfoPanel = createUserInfoPanel()
        contentPanel.add(userInfoPanel, BorderLayout.NORTH)
        
        // Add separator
        contentPanel.add(JSeparator(), BorderLayout.CENTER)
        
        // Configuration panel
        val configPanel = createConfigPanel()
        contentPanel.add(configPanel, BorderLayout.SOUTH)

        // Add content panel to main panel
        mainPanel.add(contentPanel, BorderLayout.NORTH)

        // Important: Add the panel to the builder
        builder.addComponent(mainPanel)

        // Add listeners
        addFieldListeners()
    }

    private fun createUserInfoPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 10, 0)
            
            val titleAndInfo = JPanel(BorderLayout()).apply {
                add(userInfoTitleLabel, BorderLayout.NORTH)
                
                val userInfo = JPanel(GridLayout(2, 1, 5, 5)).apply {
                    add(JLabel("Name: ${authState.getUserName() ?: "Unknown User"}"))
                    add(JLabel("Email: ${authState.getUserEmail() ?: "Unknown Email"}"))
                    border = JBUI.Borders.empty(5, 0, 10, 0)
                }
                add(userInfo, BorderLayout.CENTER)
            }
            
            signOutButton.addActionListener {
                handleSignOut()
            }
            
            add(titleAndInfo, BorderLayout.NORTH)
            add(signOutButton, BorderLayout.CENTER)
        }
    }

    private fun handleSignOut() {
        // Clear user data from auth state
        authState.clearUserData()

        // Show confirmation message
        JOptionPane.showMessageDialog(
            null,
            "You have been signed out successfully.",
            "Sign Out",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun createConfigPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 0, 0, 0)
            
            val configTitle = JBLabel("Configuration Options").apply {
                font = font.deriveFont(font.style or Font.BOLD)
                border = JBUI.Borders.empty(0, 0, 5, 0)
            }
            
            val options = JPanel(GridLayout(2, 2, 5, 5)).apply {
                add(storeCompletionsLabel)
                add(storeCompletionField)
                add(storeContextLabel)
                add(storeContextField)
            }
            
            add(configTitle, BorderLayout.NORTH)
            add(options, BorderLayout.CENTER)
        }
    }

    private fun addFieldListeners() {
        storeCompletionField.addActionListener {
            getPrefState().storeCompletions = storeCompletionField.isSelected
        }

        storeContextField.addActionListener {
            getPrefState().storeContext = storeContextField.isSelected
        }
    }
}