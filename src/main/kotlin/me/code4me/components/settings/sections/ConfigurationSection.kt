package me.code4me.components.settings.sections

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.fields.TextField
import me.code4me.components.settings.fields.ToggleButtonField
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.PrefState
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.PreferenceType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTree
import javax.swing.border.EmptyBorder
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.PlainDocument
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class ConfigurationSection : SettingsSection {
    private val storeContextField = JCheckBox()
    private val storeContextFieldSVF =
        object : ToggleButtonField(storeContextField) {
            override fun getStateValue(): Boolean? {
                return storeContextField.isSelected
            }

            override fun setStateValue(value: Boolean) {
                storeContextField.isSelected = value
            }
        }

    private val storeCompletionField = JCheckBox()
    private val storeCompletionFieldSVF =
        object : ToggleButtonField(storeCompletionField) {
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
    private val userInfoTitleLabel =
        JBLabel("User Information").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = EmptyBorder(0, 0, 5, 0)
        }

    private val signOutButton = JButton("Sign Out")

    // Module section components
    private val moduleTitleLabel =
        JBLabel("Modules").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = EmptyBorder(0, 0, 5, 0)
        }

    // Tree model for hierarchical module display
    private val moduleTreeModel = DefaultTreeModel(DefaultMutableTreeNode("Modules"))
    private val moduleTree =
        Tree(moduleTreeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            border = BorderFactory.createEtchedBorder()

            // Custom renderer to display module names
            cellRenderer =
                object : DefaultTreeCellRenderer() {
                    override fun getTreeCellRendererComponent(
                        tree: JTree,
                        value: Any,
                        selected: Boolean,
                        expanded: Boolean,
                        leaf: Boolean,
                        row: Int,
                        hasFocus: Boolean,
                    ): Component {
                        val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

                        if (component is JLabel && value is DefaultMutableTreeNode) {
                            val userObject = value.userObject
                            if (userObject is PluginModule) {
                                // Use the module name from the interface
                                component.text = userObject.moduleName
                            }
                        }

                        return component
                    }
                }
        }

    private val modulePreferencesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(0, 0, 8, 0) // 8px bottom margin
    }
    private val modulePreferenceFields = mutableMapOf<String, StateValueField<*>>()

    // Reference to the auth state
    private val authState = getAuthState()

    init {
        storeCompletionField.isSelected = getPrefState().storeCompletions
        storeContextField.isSelected = getPrefState().storeContext

        // Initialize module tree
        updateModuleTree()

        // Add selection listener to module tree
        moduleTree.addTreeSelectionListener(
            object : TreeSelectionListener {
                override fun valueChanged(e: TreeSelectionEvent) {
                    // Always update panel when selection changes
                    updateModulePreferencesPanel()
                }
            },
        )

        // Expand all nodes by default for better visibility
        expandAllNodes()
    }

    private fun expandAllNodes() {
        for (i in 0 until moduleTree.rowCount) {
            moduleTree.expandRow(i)
        }
    }

    private fun updateModuleTree() {
        val rootNode = DefaultMutableTreeNode("Modules")

        // Use the actual registered modules from PrefState (which delegates to ModuleManager)
        val modules = PrefState.getAvailableModules()
        modules.forEach { module ->
            val moduleNode = DefaultMutableTreeNode(module)
            addSubmodulesRecursively(module, moduleNode)
            rootNode.add(moduleNode)
        }
        moduleTreeModel.setRoot(rootNode)
        moduleTreeModel.reload()
        expandAllNodes()
    }

    // Recursively add submodules to the tree
    private fun addSubmodulesRecursively(
        module: PluginModule,
        parentNode: DefaultMutableTreeNode,
    ) {
        try {
            val submodules = module.getSubmodules()
            submodules.forEach { submodule ->
                val submoduleNode = DefaultMutableTreeNode(submodule)
                addSubmodulesRecursively(submodule, submoduleNode)
                parentNode.add(submoduleNode)
            }
        } catch (e: Exception) {
            // Ignore if getSubmodules is not implemented or fails
        }
    }

    private fun updateModulePreferencesPanel() {
        modulePreferencesPanel.removeAll()
        modulePreferenceFields.clear()

        val selectedNode = moduleTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val selectedModule = selectedNode?.userObject as? PluginModule

        val prefState = getPrefState()

        if (selectedModule != null) {
            // First add a label with module name and description
            val moduleNameLabel =
                JBLabel(selectedModule.moduleName).apply {
                    font = font.deriveFont(Font.BOLD)
                }
            modulePreferencesPanel.add(moduleNameLabel)

            // Add an empty label for spacing
            modulePreferencesPanel.add(JLabel())

            // TODO: add a method to get module description and display it

            // Add a separator
            val separator = JSeparator()
            modulePreferencesPanel.add(separator)
            modulePreferencesPanel.add(JLabel()) // Empty cell for grid layout

            // Add module enabled checkbox
            val enabledCheckBox = JBCheckBox("Enabled")
            enabledCheckBox.isSelected = prefState.enabledModules.contains(selectedModule.getPreferenceId())
            enabledCheckBox.addActionListener {
                if (enabledCheckBox.isSelected) {
                    prefState.enabledModules = prefState.enabledModules + selectedModule.getPreferenceId()
                } else {
                    prefState.enabledModules = prefState.enabledModules - selectedModule.getPreferenceId()
                }
            }

            modulePreferencesPanel.add(JLabel("Module Status:"))
            modulePreferencesPanel.add(enabledCheckBox)

            // Get module preferences
            val preferences = PrefState.getModulePreferences(selectedModule.getPreferenceId())

            if (preferences.isNotEmpty()) {
                // Add a preferences section header
                val prefsHeaderLabel = JLabel("Module Preferences")
                prefsHeaderLabel.font = prefsHeaderLabel.font.deriveFont(Font.BOLD)
                modulePreferencesPanel.add(prefsHeaderLabel)
                modulePreferencesPanel.add(JLabel()) // Empty cell for grid layout
            }

            // Add preference fields
            for (pref in preferences) {
                modulePreferencesPanel.add(JLabel(pref.displayName + ":"))

                val field =
                    when (pref.type) {
                        PreferenceType.BOOLEAN -> {
                            val checkBox = JBCheckBox()
                            checkBox.isSelected = PrefState.getPreferenceValue(selectedModule.getPreferenceId(), pref.key) == "true"
                            val svf =
                                object : ToggleButtonField(checkBox) {
                                    override fun getStateValue(): Boolean? {
                                        return checkBox.isSelected
                                    }

                                    override fun setStateValue(value: Boolean) {
                                        checkBox.isSelected = value
                                        PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value.toString())
                                    }
                                }
                            modulePreferenceFields["${selectedModule.getPreferenceId()}.${pref.key}"] = svf
                            checkBox
                        }
                        PreferenceType.STRING -> {
                            val textField =
                                JBTextField(PrefState.getPreferenceValue(selectedModule.getPreferenceId(), pref.key) ?: pref.defaultValue)
                            val svf =
                                object : TextField(textField) {
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
                        PreferenceType.INT, PreferenceType.LONG -> {
                            val intField =
                                JBTextField(PrefState.getPreferenceValue(selectedModule.getPreferenceId(), pref.key) ?: pref.defaultValue)
                            val warningHint =
                                JBLabel().apply {
                                    foreground = UIUtil.getErrorForeground()
                                    isVisible = false
                                }

                            // Allow any input while typing
                            (intField.document as PlainDocument).documentFilter =
                                object : DocumentFilter() {
                                    override fun insertString(
                                        fb: FilterBypass,
                                        offset: Int,
                                        string: String?,
                                        attr: AttributeSet?,
                                    ) {
                                        if (string?.matches(Regex("-?\\d*")) == true || string?.isEmpty() == true) {
                                            super.insertString(fb, offset, string, attr)
                                            warningHint.isVisible = false
                                        }
                                    }

                                    override fun replace(
                                        fb: FilterBypass,
                                        offset: Int,
                                        length: Int,
                                        text: String?,
                                        attrs: AttributeSet?,
                                    ) {
                                        if (text?.matches(Regex("-?\\d*")) == true || text?.isEmpty() == true) {
                                            super.replace(fb, offset, length, text, attrs)
                                            warningHint.isVisible = false
                                        }
                                    }

                                    override fun remove(
                                        fb: FilterBypass,
                                        offset: Int,
                                        length: Int,
                                    ) {
                                        super.remove(fb, offset, length)
                                        warningHint.isVisible = false
                                    }
                                }

                            // Validate on focus loss
                            intField.addFocusListener(
                                object : java.awt.event.FocusAdapter() {
                                    override fun focusLost(e: java.awt.event.FocusEvent?) {
                                        val text = intField.text
                                        if (text.isEmpty()) {
                                            warningHint.isVisible = false
                                            return
                                        }

                                        try {
                                            val value =
                                                when (pref.type) {
                                                    PreferenceType.INT -> text.toInt()
                                                    PreferenceType.LONG -> text.toLong()
                                                    else -> return
                                                }
                                            PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value.toString())
                                            warningHint.isVisible = false
                                        } catch (ex: NumberFormatException) {
                                            val typeName = if (pref.type == PreferenceType.INT) "integer" else "long"
                                            warningHint.text = "Invalid $typeName value"
                                            warningHint.isVisible = true
                                        }
                                    }
                                },
                            )

                            val panel =
                                JPanel(BorderLayout(5, 0)).apply {
                                    add(intField, BorderLayout.CENTER)
                                    add(warningHint, BorderLayout.EAST)
                                }

                            val svf =
                                object : TextField(intField) {
                                    override fun getStateValue(): String? {
                                        return intField.text.takeIf { it.isNotEmpty() }
                                    }

                                    override fun setStateValue(value: String) {
                                        intField.text = value
                                        if (value.isNotEmpty()) {
                                            PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value)
                                        }
                                    }
                                }
                            modulePreferenceFields["${selectedModule.getPreferenceId()}.${pref.key}"] = svf
                            panel
                        }
                        PreferenceType.DOUBLE, PreferenceType.FLOAT -> {
                            val floatField =
                                JBTextField(PrefState.getPreferenceValue(selectedModule.getPreferenceId(), pref.key) ?: pref.defaultValue)
                            val warningHint =
                                JBLabel().apply {
                                    foreground = UIUtil.getErrorForeground()
                                    isVisible = false
                                }

                            // Allow decimal numbers while typing
                            (floatField.document as PlainDocument).documentFilter =
                                object : DocumentFilter() {
                                    override fun insertString(
                                        fb: FilterBypass,
                                        offset: Int,
                                        string: String?,
                                        attr: AttributeSet?,
                                    ) {
                                        if (string?.matches(Regex("-?\\d*\\.?\\d*")) == true || string?.isEmpty() == true) {
                                            super.insertString(fb, offset, string, attr)
                                            warningHint.isVisible = false
                                        }
                                    }

                                    override fun replace(
                                        fb: FilterBypass,
                                        offset: Int,
                                        length: Int,
                                        text: String?,
                                        attrs: AttributeSet?,
                                    ) {
                                        if (text?.matches(Regex("-?\\d*\\.?\\d*")) == true || text?.isEmpty() == true) {
                                            super.replace(fb, offset, length, text, attrs)
                                            warningHint.isVisible = false
                                        }
                                    }

                                    override fun remove(
                                        fb: FilterBypass,
                                        offset: Int,
                                        length: Int,
                                    ) {
                                        super.remove(fb, offset, length)
                                        warningHint.isVisible = false
                                    }
                                }

                            // Validate on focus loss
                            floatField.addFocusListener(
                                object : java.awt.event.FocusAdapter() {
                                    override fun focusLost(e: java.awt.event.FocusEvent?) {
                                        val text = floatField.text
                                        if (text.isEmpty()) {
                                            warningHint.isVisible = false
                                            return
                                        }

                                        try {
                                            val value =
                                                when (pref.type) {
                                                    PreferenceType.DOUBLE -> text.toDouble()
                                                    PreferenceType.FLOAT -> text.toFloat()
                                                    else -> return
                                                }
                                            PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value.toString())
                                            warningHint.isVisible = false
                                        } catch (ex: NumberFormatException) {
                                            val typeName = if (pref.type == PreferenceType.DOUBLE) "decimal" else "float"
                                            warningHint.text = "Invalid $typeName value"
                                            warningHint.isVisible = true
                                        }
                                    }
                                },
                            )

                            val panel =
                                JPanel(BorderLayout(5, 0)).apply {
                                    add(floatField, BorderLayout.CENTER)
                                    add(warningHint, BorderLayout.EAST)
                                }

                            val svf =
                                object : TextField(floatField) {
                                    override fun getStateValue(): String? {
                                        return floatField.text.takeIf { it.isNotEmpty() }
                                    }

                                    override fun setStateValue(value: String) {
                                        floatField.text = value
                                        if (value.isNotEmpty()) {
                                            PrefState.setPreferenceValue(selectedModule.getPreferenceId(), pref.key, value)
                                        }
                                    }
                                }
                            modulePreferenceFields["${selectedModule.getPreferenceId()}.${pref.key}"] = svf
                            panel
                        }

                        else -> JLabel("Unsupported type: ${pref.type}")
                    }

                modulePreferencesPanel.add(field)

                // Add description if available add it as an on-hover tooltip
                if( pref.description.isNotEmpty()) {
                    field.toolTipText = pref.description
                }
            }
        } else {
            // No module selected, show a prompt
            modulePreferencesPanel.add(JLabel("Select a module to view and edit its preferences"))
        }

        modulePreferencesPanel.add(Box.createRigidArea(java.awt.Dimension(0, 8)))

        modulePreferencesPanel.revalidate()
        modulePreferencesPanel.repaint()
    }

    override fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>,
    ) {
        stateValueFields.addAll(
            listOf(
                storeCompletionFieldSVF,
                storeContextFieldSVF,
            ),
        )

        // Add module preference fields
        stateValueFields.addAll(modulePreferenceFields.values)

        val mainPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(10)
            }

        // Create content panel with BorderLayout
        val contentPanel =
            JPanel(BorderLayout()).apply {
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

            val titleAndInfo =
                JPanel(BorderLayout()).apply {
                    add(userInfoTitleLabel, BorderLayout.NORTH)

                    val userInfo =
                        JPanel(GridLayout(2, 1, 5, 5)).apply {
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
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun createConfigPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 0, 0, 0)

            val configTitle =
                JBLabel("Configuration Options").apply {
                    font = font.deriveFont(font.style or Font.BOLD)
                    border = JBUI.Borders.empty(0, 0, 5, 0)
                }

            val options =
                JPanel(GridLayout(2, 2, 5, 5)).apply {
                    add(storeCompletionsLabel)
                    add(storeCompletionField)
                    add(storeContextLabel)
                    add(storeContextField)
                }

            // Create module panel
            val modulePanel =
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(15, 0, 0, 0)

                    add(moduleTitleLabel, BorderLayout.NORTH)

                    val moduleContent =
                        JPanel(GridBagLayout()).apply {
                            border = JBUI.Borders.empty(5, 0, 0, 0)

                            val gbc =
                                GridBagConstraints().apply {
                                    fill = GridBagConstraints.BOTH
                                    weightx = 0.4 // This makes the list take 40% of the width
                                    weighty = 1.0
                                    gridx = 0
                                    gridy = 0
                                }

                            // Left side: module tree with scroll pane
                            val scrollPane = JScrollPane(moduleTree)
                            scrollPane.preferredSize = java.awt.Dimension(220, 350)
                            scrollPane.minimumSize = java.awt.Dimension(200, 300)
                            scrollPane.border = BorderFactory.createEtchedBorder()
                            add(scrollPane, gbc)

                            // Right side: module preferences (scrollable)
                            gbc.gridx = 1
                            gbc.weightx = 0.6 // This makes the preferences panel take the remaining 60%

                            // Make sure modulePreferencesPanel uses a layout that respects preferred size
                            modulePreferencesPanel.layout = GridLayout(0, 2, 5, 5)

                            val preferencesScrollPane =
                                JScrollPane(modulePreferencesPanel).apply {
                                    border = JBUI.Borders.empty(0, 10, 0, 0)
                                    preferredSize = java.awt.Dimension(350, 350)
                                    minimumSize = java.awt.Dimension(300, 300)
                                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                                }
                            add(preferencesScrollPane, gbc)
                        }

                    add(moduleContent, BorderLayout.CENTER)
                }

            // Create main content panel
            val contentPanel =
                JPanel(BorderLayout()).apply {
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
