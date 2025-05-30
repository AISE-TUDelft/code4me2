package me.code4me.components.settings.sections

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import groovy.lang.Tuple2
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.fields.TextField
import me.code4me.components.settings.fields.ToggleButtonField
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.services.state.PrefState
import me.code4me.services.state.getAuthState
import me.code4me.services.state.getPrefState
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTree
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.PlainDocument

/**
 * Settings section for authenticated users to manage application configuration.
 *
 * This section provides comprehensive configuration management including:
 * - User profile information display
 * - Application-wide preference settings (completion/context storage)
 * - Module management with hierarchical tree view
 * - Module-specific preference configuration
 * - Dynamic form generation based on preference types
 * - Dependency validation for module enablement/disablement
 *
 * The section is only displayed when the user is authenticated and provides
 * a rich interface for customizing the plugin behavior.
 *
 * @since 1.0.0
 */
class ConfigurationSection : SettingsSection {

    companion object {
        private val LOG = thisLogger()

        // UI Constants
        private const val FORM_PADDING = 10
        private const val SECTION_SPACING = 10
        private const val MODULE_TREE_WIDTH = 220
        private const val MODULE_TREE_HEIGHT = 350
        private const val PREFERENCES_PANEL_WIDTH = 350
        private const val MIN_MODULE_TREE_WIDTH = 200
        private const val MIN_MODULE_TREE_HEIGHT = 300
        private const val MIN_PREFERENCES_PANEL_WIDTH = 300
    }

    // ================= APPLICATION PREFERENCE FIELDS =================

    /**
     * Checkbox for controlling completion data storage.
     */
    private val storeCompletionField = JBCheckBox("Store Completions").apply {
        toolTipText = "Enable storage of code completion data for analytics and improvements"
    }

    private val storeCompletionFieldSVF = object : ToggleButtonField(storeCompletionField) {
        override fun getStateValue(): Boolean = getPrefState().storeCompletions
        override fun setStateValue(value: Boolean) {
            storeCompletionField.isSelected = value
            getPrefState().storeCompletions = value
        }
    }

    /**
     * Checkbox for controlling context data storage.
     */
    private val storeContextField = JBCheckBox("Store Context").apply {
        toolTipText = "Enable storage of code context data for enhanced completions"
    }

    private val storeContextFieldSVF = object : ToggleButtonField(storeContextField) {
        override fun getStateValue(): Boolean = getPrefState().storeContext
        override fun setStateValue(value: Boolean) {
            storeContextField.isSelected = value
            getPrefState().storeContext = value
        }
    }

    // ================= UI COMPONENTS =================

    /**
     * Title label for user information section.
     */
    private val userInfoTitleLabel = JBLabel("User Information").apply {
        font = font.deriveFont(font.style or Font.BOLD)
        border = JBUI.Borders.empty(0, 0, 5, 0)
    }

    /**
     * Sign out button for user authentication management.
     */
    private val signOutButton = JButton("Sign Out").apply {
        toolTipText = "Sign out of your Code4Me account"
        addActionListener { handleSignOut() }
    }

    /**
     * Title label for module management section.
     */
    private val moduleTitleLabel = JBLabel("Module Management").apply {
        font = font.deriveFont(font.style or Font.BOLD)
        border = JBUI.Borders.empty(0, 0, 5, 0)
    }

    // ================= MODULE MANAGEMENT =================

    /**
     * Tree model for hierarchical module display.
     */
    private val moduleTreeModel = DefaultTreeModel(DefaultMutableTreeNode("Modules"))

    /**
     * Tree component for displaying available modules.
     */
    private val moduleTree = Tree(moduleTreeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        border = BorderFactory.createEtchedBorder()
        toolTipText = "Select a module to view and configure its preferences"

        // Custom renderer for displaying module information
        cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree,
                value: Any,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): Component {
                val component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus
                )

                if (component is JLabel && value is DefaultMutableTreeNode) {
                    val userObject = value.userObject
                    if (userObject is PluginModule) {
                        component.text = userObject.moduleName
                        component.toolTipText = "Click to configure ${userObject.moduleName}"
                    }
                }

                return component
            }
        }
    }

    /**
     * Panel for displaying module-specific preferences.
     */
    private val modulePreferencesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(0, 0, 8, 0)
    }

    /**
     * Map storing state value fields for module preferences.
     */
    private val modulePreferenceFields = mutableMapOf<String, StateValueField<*>>()

    // ================= SERVICES =================

    private val authState = getAuthState()

    init {
        initializeFields()
        setupModuleTree()
        LOG.debug("ConfigurationSection initialized")
    }

    /**
     * Initializes form fields with current preference values.
     */
    private fun initializeFields() {
        val prefState = getPrefState()
        storeCompletionField.isSelected = prefState.storeCompletions
        storeContextField.isSelected = prefState.storeContext
    }

    /**
     * Sets up the module tree with data and listeners.
     */
    private fun setupModuleTree() {
        updateModuleTree()

        // Add selection listener for dynamic preference panel updates
        moduleTree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent) {
                updateModulePreferencesPanel()
            }
        })

        // Expand all nodes for better visibility
        expandAllTreeNodes()
    }

    /**
     * Expands all nodes in the module tree.
     */
    private fun expandAllTreeNodes() {
        for (i in 0 until moduleTree.rowCount) {
            moduleTree.expandRow(i)
        }
    }

    /**
     * Updates the module tree with current available modules.
     */
    private fun updateModuleTree() {
        val rootNode = DefaultMutableTreeNode("Modules")

        try {
            val modules = PrefState.getAvailableModules()
            LOG.debug("Loading ${modules.size} modules into tree")

            modules.forEach { module ->
                val moduleNode = DefaultMutableTreeNode(module)
                addSubmodulesRecursively(module, moduleNode)
                rootNode.add(moduleNode)
            }

            moduleTreeModel.setRoot(rootNode)
            moduleTreeModel.reload()
            expandAllTreeNodes()

        } catch (e: Exception) {
            LOG.error("Failed to update module tree", e)
        }
    }

    /**
     * Recursively adds submodules to the tree structure.
     */
    private fun addSubmodulesRecursively(module: PluginModule, parentNode: DefaultMutableTreeNode) {
        try {
            val submodules = module.getSubmodules()
            submodules.forEach { submodule ->
                val submoduleNode = DefaultMutableTreeNode(submodule)
                addSubmodulesRecursively(submodule, submoduleNode)
                parentNode.add(submoduleNode)
            }
        } catch (e: Exception) {
            LOG.debug("Module ${module.getPreferenceId()} has no submodules or failed to retrieve them")
        }
    }

    /**
     * Checks if a module can be disabled based on dependency constraints.
     */
    private fun checkModuleCanBeDisabled(module: PluginModule): Tuple2<Boolean, List<String>> {
        val selectedNode = moduleTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val isTopLevelNode = selectedNode?.parent?.parent == null

        if (isTopLevelNode) {
            return Tuple2(false, emptyList())
        }

        try {
            val moduleId = module.getPreferenceId()
            val dependentModules = getConfig().getTransitiveHardDependants(moduleId)

            val hasTopLevelDependency = dependentModules.any { dependant ->
                val dependentNode = findModuleNodeById(dependant.id)
                dependentNode?.parent?.parent == null
            }

            return Tuple2(!hasTopLevelDependency, dependentModules.map { it.id })

        } catch (e: Exception) {
            LOG.warn("Failed to check module dependencies for ${module.getPreferenceId()}", e)
            return Tuple2(true, emptyList()) // Allow disabling if check fails
        }
    }

    /**
     * Finds a module node by its ID in the tree.
     */
    private fun findModuleNodeById(
        moduleId: String,
        root: DefaultMutableTreeNode = moduleTreeModel.root as DefaultMutableTreeNode
    ): DefaultMutableTreeNode? {
        val children = root.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as DefaultMutableTreeNode
            val module = child.userObject as? PluginModule
            if (module?.getPreferenceId() == moduleId) {
                return child
            }
            findModuleNodeById(moduleId, child)?.let { return it }
        }
        return null
    }

    /**
     * Updates the module preferences panel based on current selection.
     */
    private fun updateModulePreferencesPanel() {
        modulePreferencesPanel.removeAll()
        modulePreferenceFields.clear()

        val selectedNode = moduleTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val selectedModule = selectedNode?.userObject as? PluginModule

        if (selectedModule != null) {
            buildModulePreferencesUI(selectedModule)
        } else {
            showNoSelectionMessage()
        }

        modulePreferencesPanel.revalidate()
        modulePreferencesPanel.repaint()
    }

    /**
     * Builds the preferences UI for the selected module.
     */
    private fun buildModulePreferencesUI(module: PluginModule) {
        // Module header
        addModuleHeader(module)

        // Module enablement control
        addModuleEnablementControl(module)

        // Module preferences
        addModulePreferences(module)

        // Add spacing at the end
        modulePreferencesPanel.add(Box.createRigidArea(java.awt.Dimension(0, 8)))
    }

    /**
     * Adds module header information to the preferences panel.
     */
    private fun addModuleHeader(module: PluginModule) {
        val moduleNameLabel = JBLabel(module.moduleName).apply {
            font = font.deriveFont(Font.BOLD)
        }
        modulePreferencesPanel.add(moduleNameLabel)
        modulePreferencesPanel.add(JLabel()) // Spacing

        val separator = JSeparator()
        modulePreferencesPanel.add(separator)
        modulePreferencesPanel.add(JLabel()) // Spacing
    }

    /**
     * Adds module enablement control checkbox.
     */
    private fun addModuleEnablementControl(module: PluginModule) {
        val prefState = getPrefState()
        val enabledCheckBox = JBCheckBox("Module Enabled").apply {
            isSelected = prefState.enabledModules.contains(module.getPreferenceId())
            toolTipText = "Enable or disable this module"
        }

        val (canBeDisabled, dependentModules) = checkModuleCanBeDisabled(module)

        if (!(canBeDisabled as Boolean)) {
            enabledCheckBox.isEnabled = false
            enabledCheckBox.isSelected = true

            val warningMessage = if ((dependentModules as List<*>).isNotEmpty()) {
                val filteredDependents = dependentModules.filter { it != module.getPreferenceId() }
                "This module cannot be disabled because it has dependencies: ${filteredDependents.joinToString(", ")}"
            } else {
                "This module is required and cannot be disabled."
            }
            enabledCheckBox.toolTipText = warningMessage
        }

        enabledCheckBox.addActionListener {
            handleModuleEnablementChange(module, enabledCheckBox.isSelected)
        }

        modulePreferencesPanel.add(JLabel("Module Status:"))
        modulePreferencesPanel.add(enabledCheckBox)
    }

    /**
     * Handles module enablement state changes.
     */
    private fun handleModuleEnablementChange(module: PluginModule, isEnabled: Boolean) {
        val prefState = getPrefState()
        val moduleId = module.getPreferenceId()

        try {
            if (isEnabled) {
                enableModuleWithDependencies(moduleId, prefState)
            } else {
                disableModuleWithDependents(moduleId, prefState)
            }

            LOG.debug("Module $moduleId ${if (isEnabled) "enabled" else "disabled"}")

        } catch (e: Exception) {
            LOG.error("Failed to change module enablement state for $moduleId", e)
        }
    }

    /**
     * Enables a module and its hard dependencies.
     */
    private fun enableModuleWithDependencies(moduleId: String, prefState: me.code4me.services.state.PrefSettings) {
        prefState.enabledModules = HashSet(prefState.enabledModules + moduleId)

        try {
            val configService = getConfig()
            val availableModules = configService.getAvailableModules()
            val moduleConfig = availableModules.find { it.className == moduleId }

            moduleConfig?.dependencies?.forEach { dependency ->
                if (dependency.isHard) {
                    prefState.enabledModules = HashSet(prefState.enabledModules + dependency.moduleId)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to enable dependencies for module $moduleId", e)
        }
    }

    /**
     * Disables a module and its dependents.
     */
    private fun disableModuleWithDependents(moduleId: String, prefState: me.code4me.services.state.PrefSettings) {
        prefState.enabledModules = HashSet(prefState.enabledModules - moduleId)

        try {
            val configService = getConfig()
            val availableModules = configService.getAvailableModules()

            availableModules.forEach { moduleConfig ->
                moduleConfig.dependencies.forEach { dependency ->
                    if (dependency.moduleId == moduleId && dependency.isHard) {
                        prefState.enabledModules = HashSet(prefState.enabledModules - moduleConfig.id)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to disable dependents for module $moduleId", e)
        }
    }

    /**
     * Adds module-specific preference controls to the panel.
     */
    private fun addModulePreferences(module: PluginModule) {
        val preferences = PrefState.getModulePreferences(module.getPreferenceId())

        if (preferences.isNotEmpty()) {
            val prefsHeaderLabel = JLabel("Module Preferences").apply {
                font = font.deriveFont(Font.BOLD)
            }
            modulePreferencesPanel.add(prefsHeaderLabel)
            modulePreferencesPanel.add(JLabel()) // Spacing
        }

        preferences.forEach { preference ->
            addPreferenceField(module, preference)
        }
    }

    /**
     * Adds a single preference field to the panel.
     */
    private fun addPreferenceField(module: PluginModule, preference: Preference) {
        // Create label with tooltip indicator
        val labelText = if (preference.description.isNotEmpty()) {
            "${preference.displayName} ⓘ"
        } else {
            preference.displayName
        }

        val label = JLabel("$labelText:").apply {
            if (preference.description.isNotEmpty()) {
                toolTipText = preference.description
            }
        }

        modulePreferencesPanel.add(label)

        val field = createPreferenceField(module, preference)

        // Add tooltip to field as well
        if (preference.description.isNotEmpty()) {
            field.toolTipText = preference.description
        }

        modulePreferencesPanel.add(field)
    }

    /**
     * Creates appropriate UI component for a preference based on its type.
     */
    private fun createPreferenceField(module: PluginModule, preference: Preference): JComponent {
        val moduleId = module.getPreferenceId()

        return when (preference.type) {
            PreferenceType.BOOLEAN -> createBooleanField(moduleId, preference)
            PreferenceType.STRING -> createStringField(moduleId, preference)
            PreferenceType.INT, PreferenceType.LONG -> createIntegerField(moduleId, preference)
            PreferenceType.DOUBLE, PreferenceType.FLOAT -> createFloatField(moduleId, preference)
            else -> JLabel("Unsupported preference type: ${preference.type}")
        }
    }

    /**
     * Creates a boolean preference field (checkbox).
     */
    private fun createBooleanField(moduleId: String, preference: Preference): JComponent {
        val checkBox = JBCheckBox().apply {
            isSelected = PrefState.getPreferenceValue(moduleId, preference.key) == "true"
        }

        val svf = object : ToggleButtonField(checkBox) {
            override fun getStateValue(): Boolean = checkBox.isSelected
            override fun setStateValue(value: Boolean) {
                checkBox.isSelected = value
                PrefState.setPreferenceValue(moduleId, preference.key, value.toString())
            }
        }

        modulePreferenceFields["$moduleId.${preference.key}"] = svf
        return checkBox
    }

    /**
     * Creates a string preference field.
     */
    private fun createStringField(moduleId: String, preference: Preference): JComponent {
        val textField = JBTextField(
            PrefState.getPreferenceValue(moduleId, preference.key) ?: preference.defaultValue
        )

        val svf = object : TextField(textField) {
            override fun getStateValue(): String? = textField.text.takeIf { it.isNotBlank() }
            override fun setStateValue(value: String) {
                textField.text = value
                PrefState.setPreferenceValue(moduleId, preference.key, value)
            }
        }

        modulePreferenceFields["$moduleId.${preference.key}"] = svf
        return textField
    }

    /**
     * Creates an integer preference field with validation.
     */
    private fun createIntegerField(moduleId: String, preference: Preference): JComponent {
        val textField = JBTextField(
            PrefState.getPreferenceValue(moduleId, preference.key) ?: preference.defaultValue
        )

        val warningLabel = JBLabel().apply {
            foreground = UIUtil.getErrorForeground()
            isVisible = false
        }

        setupNumericValidation(textField, warningLabel, preference.type)

        val panel = JPanel(BorderLayout(5, 0)).apply {
            add(textField, BorderLayout.CENTER)
            add(warningLabel, BorderLayout.EAST)
        }

        val svf = object : TextField(textField) {
            override fun getStateValue(): String? = textField.text.takeIf { it.isNotBlank() }
            override fun setStateValue(value: String) {
                textField.text = value
                if (value.isNotEmpty()) {
                    PrefState.setPreferenceValue(moduleId, preference.key, value)
                }
            }
        }

        modulePreferenceFields["$moduleId.${preference.key}"] = svf
        return panel
    }

    /**
     * Creates a floating-point preference field with validation.
     */
    private fun createFloatField(moduleId: String, preference: Preference): JComponent {
        val textField = JBTextField(
            PrefState.getPreferenceValue(moduleId, preference.key) ?: preference.defaultValue
        )

        val warningLabel = JBLabel().apply {
            foreground = UIUtil.getErrorForeground()
            isVisible = false
        }

        setupDecimalValidation(textField, warningLabel, preference.type)

        val panel = JPanel(BorderLayout(5, 0)).apply {
            add(textField, BorderLayout.CENTER)
            add(warningLabel, BorderLayout.EAST)
        }

        val svf = object : TextField(textField) {
            override fun getStateValue(): String? = textField.text.takeIf { it.isNotBlank() }
            override fun setStateValue(value: String) {
                textField.text = value
                if (value.isNotEmpty()) {
                    PrefState.setPreferenceValue(moduleId, preference.key, value)
                }
            }
        }

        modulePreferenceFields["$moduleId.${preference.key}"] = svf
        return panel
    }

    /**
     * Sets up numeric input validation for integer fields.
     */
    private fun setupNumericValidation(textField: JBTextField, warningLabel: JBLabel, type: PreferenceType) {
        val document = textField.document as PlainDocument
        document.documentFilter = object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                if (string?.matches(Regex("-?\\d*")) == true || string?.isEmpty() == true) {
                    super.insertString(fb, offset, string, attr)
                    warningLabel.isVisible = false
                }
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                if (text?.matches(Regex("-?\\d*")) == true || text?.isEmpty() == true) {
                    super.replace(fb, offset, length, text, attrs)
                    warningLabel.isVisible = false
                }
            }

            override fun remove(fb: FilterBypass, offset: Int, length: Int) {
                super.remove(fb, offset, length)
                warningLabel.isVisible = false
            }
        }

        textField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                validateNumericInput(textField, warningLabel, type)
            }
        })
    }

    /**
     * Sets up decimal input validation for floating-point fields.
     */
    private fun setupDecimalValidation(textField: JBTextField, warningLabel: JBLabel, type: PreferenceType) {
        val document = textField.document as PlainDocument
        document.documentFilter = object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                if (string?.matches(Regex("-?\\d*\\.?\\d*")) == true || string?.isEmpty() == true) {
                    super.insertString(fb, offset, string, attr)
                    warningLabel.isVisible = false
                }
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                if (text?.matches(Regex("-?\\d*\\.?\\d*")) == true || text?.isEmpty() == true) {
                    super.replace(fb, offset, length, text, attrs)
                    warningLabel.isVisible = false
                }
            }

            override fun remove(fb: FilterBypass, offset: Int, length: Int) {
                super.remove(fb, offset, length)
                warningLabel.isVisible = false
            }
        }

        textField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                validateDecimalInput(textField, warningLabel, type)
            }
        })
    }

    /**
     * Validates numeric input and shows appropriate error messages.
     */
    private fun validateNumericInput(textField: JBTextField, warningLabel: JBLabel, type: PreferenceType) {
        val text = textField.text
        if (text.isEmpty()) {
            warningLabel.isVisible = false
            return
        }

        try {
            when (type) {
                PreferenceType.INT -> text.toInt()
                PreferenceType.LONG -> text.toLong()
                else -> return
            }
            warningLabel.isVisible = false
        } catch (ex: NumberFormatException) {
            val typeName = if (type == PreferenceType.INT) "integer" else "long"
            warningLabel.text = "Invalid $typeName value"
            warningLabel.isVisible = true
        }
    }

    /**
     * Validates decimal input and shows appropriate error messages.
     */
    private fun validateDecimalInput(textField: JBTextField, warningLabel: JBLabel, type: PreferenceType) {
        val text = textField.text
        if (text.isEmpty()) {
            warningLabel.isVisible = false
            return
        }

        try {
            when (type) {
                PreferenceType.DOUBLE -> text.toDouble()
                PreferenceType.FLOAT -> text.toFloat()
                else -> return
            }
            warningLabel.isVisible = false
        } catch (ex: NumberFormatException) {
            val typeName = if (type == PreferenceType.DOUBLE) "decimal" else "float"
            warningLabel.text = "Invalid $typeName value"
            warningLabel.isVisible = true
        }
    }

    /**
     * Shows a message when no module is selected.
     */
    private fun showNoSelectionMessage() {
        modulePreferencesPanel.add(
            JLabel("Select a module from the tree to view and configure its preferences")
        )
    }

    override fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>
    ) {
        // Register application preference fields
        stateValueFields.addAll(listOf(
            storeCompletionFieldSVF,
            storeContextFieldSVF
        ))

        // Register module preference fields
        stateValueFields.addAll(modulePreferenceFields.values)

        // Create main configuration panel
        val mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(FORM_PADDING)
        }

        // Create content panel
        val contentPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0)
        }

        // Add user information section
        contentPanel.add(createUserInfoPanel(), BorderLayout.NORTH)

        // Add configuration sections
        contentPanel.add(createConfigurationPanel(), BorderLayout.CENTER)

        mainPanel.add(contentPanel, BorderLayout.CENTER)
        builder.addComponent(mainPanel)

        LOG.debug("Configuration section applied to form builder")
    }

    /**
     * Creates the user information display panel.
     */
    private fun createUserInfoPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, SECTION_SPACING, 0)

            val titleAndInfo = JPanel(BorderLayout()).apply {
                add(userInfoTitleLabel, BorderLayout.NORTH)

                val userInfo = JPanel(GridLayout(2, 1, 5, 5)).apply {
                    add(JLabel("Name: ${authState.getUserName() ?: "Unknown User"}"))
                    add(JLabel("Email: ${authState.getUserEmail() ?: "Unknown Email"}"))
                    border = JBUI.Borders.empty(5, 0, 10, 0)
                }
                add(userInfo, BorderLayout.CENTER)
            }

            add(titleAndInfo, BorderLayout.CENTER)
            add(signOutButton, BorderLayout.SOUTH)
        }
    }

    /**
     * Creates the main configuration panel with preferences and modules.
     */
    private fun createConfigurationPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(SECTION_SPACING, 0, 0, 0)

            // Application preferences
            val appPrefsPanel = createApplicationPreferencesPanel()
            add(appPrefsPanel, BorderLayout.NORTH)

            // Module management
            val modulePanel = createModuleManagementPanel()
            add(modulePanel, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the application-wide preferences panel.
     */
    private fun createApplicationPreferencesPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val configTitle = JBLabel("Application Preferences").apply {
                font = font.deriveFont(font.style or Font.BOLD)
                border = JBUI.Borders.empty(0, 0, 5, 0)
            }

            val optionsPanel = JPanel(GridLayout(2, 1, 5, 5)).apply {
                add(storeCompletionField)
                add(storeContextField)
            }

            add(configTitle, BorderLayout.NORTH)
            add(optionsPanel, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the module management panel with tree and preferences.
     */
    private fun createModuleManagementPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(SECTION_SPACING, 0, 0, 0)

            add(moduleTitleLabel, BorderLayout.NORTH)

            val moduleContent = JPanel(GridBagLayout()).apply {
                border = JBUI.Borders.empty(5, 0, 0, 0)

                val gbc = GridBagConstraints().apply {
                    fill = GridBagConstraints.BOTH
                    weightx = 0.4
                    weighty = 1.0
                    gridx = 0
                    gridy = 0
                }

                // Module tree with scroll pane
                val treeScrollPane = JBScrollPane(moduleTree).apply {
                    preferredSize = java.awt.Dimension(MODULE_TREE_WIDTH, MODULE_TREE_HEIGHT)
                    minimumSize = java.awt.Dimension(MIN_MODULE_TREE_WIDTH, MIN_MODULE_TREE_HEIGHT)
                    border = BorderFactory.createEtchedBorder()
                }
                add(treeScrollPane, gbc)

                // Module preferences panel
                gbc.gridx = 1
                gbc.weightx = 0.6

                modulePreferencesPanel.layout = GridLayout(0, 2, 5, 5)

                val preferencesScrollPane = JBScrollPane(modulePreferencesPanel).apply {
                    border = JBUI.Borders.empty(0, 10, 0, 0)
                    preferredSize = java.awt.Dimension(PREFERENCES_PANEL_WIDTH, MODULE_TREE_HEIGHT)
                    minimumSize = java.awt.Dimension(MIN_PREFERENCES_PANEL_WIDTH, MIN_MODULE_TREE_HEIGHT)
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                }
                add(preferencesScrollPane, gbc)
            }

            add(moduleContent, BorderLayout.CENTER)
        }
    }

    /**
     * Handles user sign out operation.
     */
    private fun handleSignOut() {
        try {
            authState.clearUserData()
            Messages.showInfoMessage(
                "You have been signed out successfully.",
                "Sign Out Complete"
            )
            LOG.info("User signed out successfully")
        } catch (e: Exception) {
            LOG.error("Failed to sign out user", e)
            Messages.showErrorDialog(
                "An error occurred while signing out. Please try again.",
                "Sign Out Error"
            )
        }
    }
}