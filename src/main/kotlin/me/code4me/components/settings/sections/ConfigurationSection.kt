package me.code4me.components.settings.sections

import PluginModule
import com.intellij.openapi.components.service
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import getModuleManager
import me.code4me.components.settings.fields.*
import me.code4me.services.state.*
import me.code4me.utils.configuration.PreferenceType
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.*
import javax.swing.border.EmptyBorder
import java.awt.GridBagLayout
import java.awt.GridBagConstraints

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

    // Module section components
    private val moduleTitleLabel = JBLabel("Modules").apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        border = EmptyBorder(0, 0, 5, 0)
    }

    private val moduleListModel = CollectionListModel<PluginModule>()
    private val moduleList = JBList(moduleListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        border = BorderFactory.createEtchedBorder()
        cellRenderer = DefaultListCellRenderer().apply {
            @Suppress("UNCHECKED_CAST")
            (this as ListCellRenderer<PluginModule>).apply {
                ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                    val component = getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (component is JLabel && value is PluginModule) {
                        component.text = value.moduleName
                    }
                    component
                }
            }
        }
    }

    private val modulePreferencesPanel = JPanel(GridLayout(0, 2, 5, 5))
    private val modulePreferenceFields = mutableMapOf<String, StateValueField<*>>()

    // Reference to the auth state
    private val authState = getAuthState()

    init {
        storeCompletionField.isSelected = getPrefState().storeCompletions
        storeContextField.isSelected = getPrefState().storeContext

        // Register sample modules for testing
        registerSampleModules()

        // Initialize module list
        updateModuleList()

        // Add selection listener to module list
        moduleList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                updateModulePreferencesPanel()
            }
        }
    }

    private fun registerSampleModules() {
        // Sample module 1: Code Completion
        val mainModule = getModuleManager()

        PrefState.registerModule(mainModule)
    }

    private fun updateModuleList() {
        moduleListModel.removeAll()
        moduleListModel.add(getPrefState().availableModules)
    }

    private fun updateModulePreferencesPanel() {
        modulePreferencesPanel.removeAll()
        modulePreferenceFields.clear()

        val selectedModule = moduleList.selectedValue
        if (selectedModule != null) {
            val preferences = PrefState.getModulePreferences(selectedModule.getPreferenceId())

            // Add module enabled checkbox
            val enabledCheckBox = JBCheckBox("Enabled")
            enabledCheckBox.isSelected = PrefState.getEnabledModules().contains(selectedModule.getPreferenceId())
            enabledCheckBox.addActionListener {
                if (enabledCheckBox.isSelected) {
                    PrefState.enableModule(selectedModule.getPreferenceId())
                } else {
                    PrefState.disableModule(selectedModule.getPreferenceId())
                }
            }
            modulePreferencesPanel.add(JLabel("Module Status:"))
            modulePreferencesPanel.add(enabledCheckBox)

            // Add preference fields
            for (pref in preferences) {
                modulePreferencesPanel.add(JLabel(pref.displayName + ":"))

                val field = when (pref.type) {
                    PreferenceType.BOOLEAN -> {
                        val checkBox = JBCheckBox()
                        checkBox.isSelected = PrefState.getPreferenceValue(selectedModule.getPreferenceId(), pref.key) == "true"
                        val svf = object : ToggleButtonField(checkBox) {
                            override fun getStateValue(): Boolean? {
                                return checkBox.isSelected
                            }

                            override fun setStateValue(value: Boolean) {
                                checkBox.isSelected = value
                                PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value.toString())
                            }
                        }
                        modulePreferenceFields["${selectedModule}.${pref.key}"] = svf
                        checkBox
                    }
                    PreferenceType.STRING -> {
                        val textField = JBTextField(PrefState.getPreferenceValue(selectedModule.getPreferenceId(), pref.key) ?: pref.defaultValue)
                        val svf = object : TextField(textField) {
                            override fun getStateValue(): String? {
                                return textField.text
                            }

                            override fun setStateValue(value: String) {
                                textField.text = value
                                PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value)
                            }
                        }
                        modulePreferenceFields["${selectedModule.getPreferenceId()}.${pref.key}"] = svf
                        textField
                    }
                    else -> JLabel("Unsupported type: ${pref.type}")
                }

                modulePreferencesPanel.add(field)
            }
        }

        modulePreferencesPanel.revalidate()
        modulePreferencesPanel.repaint()
    }

    override fun applyTo(builder: FormBuilder, stateValueFields: MutableList<StateValueField<*>>) {
        stateValueFields.addAll(
            listOf(
                storeCompletionFieldSVF,
                storeContextFieldSVF
            )
        )

        // Add module preference fields
        stateValueFields.addAll(modulePreferenceFields.values)

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

            // Create module panel
            val modulePanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(15, 0, 0, 0)

                add(moduleTitleLabel, BorderLayout.NORTH)

                val moduleContent = JPanel(GridBagLayout()).apply {
                    border = JBUI.Borders.empty(5, 0, 0, 0)
                    
                    val gbc = GridBagConstraints().apply {
                        fill = GridBagConstraints.BOTH
                        weightx = 0.4  // This makes the list take 40% of the width
                        weighty = 1.0
                        gridx = 0
                        gridy = 0
                    }
                    
                    // Left side: module list with scroll pane
                    val scrollPane = JScrollPane(moduleList)
                    add(scrollPane, gbc)
                    
                    // Right side: module preferences
                    gbc.gridx = 1
                    gbc.weightx = 0.6  // This makes the preferences panel take the remaining 60%
                    val preferencesScrollPane = JScrollPane(modulePreferencesPanel).apply {
                        border = JBUI.Borders.empty(0, 10, 0, 0)
                    }
                    add(preferencesScrollPane, gbc)
                }

                add(moduleContent, BorderLayout.CENTER)
            }

            // Create main content panel
            val contentPanel = JPanel(BorderLayout()).apply {
                add(configTitle, BorderLayout.NORTH)
                add(options, BorderLayout.CENTER)
                add(modulePanel, BorderLayout.SOUTH)
            }

            add(contentPanel, BorderLayout.CENTER)
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