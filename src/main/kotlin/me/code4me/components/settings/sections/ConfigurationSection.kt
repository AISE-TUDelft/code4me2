package me.code4me.components.settings.sections
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import me.code4me.api.generated.model.UpdateUser
import me.code4me.components.settings.fields.FieldInfo
import me.code4me.components.settings.fields.ModuleBooleanPreferenceField
import me.code4me.components.settings.fields.ModuleFloatPreferenceField
import me.code4me.components.settings.fields.ModuleIntegerPreferenceField
import me.code4me.components.settings.fields.ModuleListPreferenceField
import me.code4me.components.settings.fields.ModuleStringPreferenceField
import me.code4me.components.settings.fields.ModuleTextPreferenceField
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.fields.ToggleButtonField
import me.code4me.services.app.AppService
import me.code4me.services.app.getAppService
import me.code4me.services.config.getConfig
import me.code4me.services.modules.PluginModule
import me.code4me.services.modules.manager.getModuleManager
import me.code4me.services.state.AuthState
import me.code4me.services.state.PrefState
import me.code4me.services.state.getPrefState
import me.code4me.settings.UserConfigurable
import me.code4me.utils.api.fromSerializableMap
import me.code4me.utils.api.toSerializableMap
import me.code4me.utils.configuration.Preference
import me.code4me.utils.configuration.PreferenceType
import me.code4me.utils.notification.showPreferenceSyncFailedNotification
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.PlainDocument
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

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
 * - Integration with ChatPanel overlay updates
 *
 * The section is only displayed when the user is authenticated and provides
 * a rich interface for customizing the plugin behavior.
 *
 * @since 1.0.0
 */
class ConfigurationSection() : SettingsSection {
    val manageProfileButton =
        JButton("Manage Profile").apply {
            toolTipText = "Open Code4Me → User settings"
            putClientProperty("JButton.buttonType", "link")
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            addActionListener {
                val dc = com.intellij.ide.DataManager.getInstance().getDataContext(this)
                val settings = dc.getData(com.intellij.openapi.options.ex.Settings.KEY)
                if (settings != null) {
                    val userCfg = settings.find(UserConfigurable::class.java)
                    if (userCfg != null) {
                        settings.select(userCfg)
                    } else {
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(null, UserConfigurable::class.java)
                    }
                } else {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(null, UserConfigurable::class.java)
                }
            }
        }

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

    // Services
    private val appService = service<AppService>()

    // ================= APPLICATION PREFERENCE FIELDS =================

    /**
     * Checkbox for controlling completion data storage.
     */
    private val storeContextField =
        JBCheckBox("Completions").apply {
            toolTipText = "Enable storage of code completion data for analytics and improvements"
        }

    private val storeContextFieldSVF =
        object : ToggleButtonField(storeContextField) {
            override fun getStateValue(): Boolean = getPrefState().storeContext

            override fun setStateValue(value: Boolean) {
                storeContextField.isSelected = value
                getPrefState().storeContext = value
            }
        }

    /**
     * Checkbox for controlling context data storage.
     */
    private val storeContextualTelemetryField =
        JBCheckBox("Contextual telemetry").apply {
            toolTipText = "Enable storage of contextual telemetry data for enhanced completions"
        }

    private val storeContextualTelemetryFieldSVF =
        object : ToggleButtonField(storeContextualTelemetryField) {
            override fun getStateValue(): Boolean = getPrefState().storeContextualTelemetry

            override fun setStateValue(value: Boolean) {
                storeContextualTelemetryField.isSelected = value
                getPrefState().storeContextualTelemetry = value
            }
        }

    /**
     * Checkbox for controlling context data storage.
     */
    private val storeBehavioralTelemetryField =
        JBCheckBox("Behavioral telemetry").apply {
            toolTipText = "Enable storage of code context data for enhanced completions"
        }

    private val storeBehavioralTelemetryFieldSVF =
        object : ToggleButtonField(storeBehavioralTelemetryField) {
            override fun getStateValue(): Boolean = getPrefState().storeBehavioralTelemetry

            override fun setStateValue(value: Boolean) {
                storeBehavioralTelemetryField.isSelected = value
                getPrefState().storeBehavioralTelemetry = value
            }
        }

    /**
     * Checkbox for controlling storage of agent content (prompts, responses, tool/editor text).
     *
     * The backend is the actual enforcement point — it nulls content columns when consent is
     * absent regardless of what the client sends — so this is how the user *expresses* the
     * preference. The plugin also honours it locally, suppressing content before transmission.
     */
    private val storeAgentContentField =
        JBCheckBox("Agent content telemetry").apply {
            toolTipText =
                "Enable storage of agent content (prompts, responses, tool and editor text). " +
                "When off, only non-sensitive metadata is stored."
        }

    private val storeAgentContentFieldSVF =
        object : ToggleButtonField(storeAgentContentField) {
            override fun getStateValue(): Boolean = getPrefState().storeAgentContent

            override fun setStateValue(value: Boolean) {
                storeAgentContentField.isSelected = value
                getPrefState().storeAgentContent = value
            }
        }

    /**
     * Button for applying limited data collection preferences.
     */
    private val limitedDataCollectionButton =
        JButton("Use Limited Data Collection").apply {
            toolTipText = "Set all module preferences to use limited data collection values"
            addActionListener {
                // Apply limited default values to all preferences
                PrefState.setAllPreferencesToLimitedDefaults()

                // Update UI to reflect changes
                updateModulePreferencesPanel()

                // force a rebuild of the module tree
                updateModuleTree()

                // update application-level checkboxes
                refreshApplicationPreferences()

                // Show modal dialog indicating success
                Messages.showInfoMessage(
                    "All module preferences have been set to use limited data collection values.",
                    "Limited Data Collection Applied",
                )
            }
        }

    /**
     * Refreshes the application-level preference checkboxes to reflect current state values.
     */
    private fun refreshApplicationPreferences() {
        storeContextFieldSVF.setFieldValue(storeContextFieldSVF.getStateValue())
        storeContextualTelemetryFieldSVF.setFieldValue(storeContextualTelemetryFieldSVF.getStateValue())
        storeBehavioralTelemetryFieldSVF.setFieldValue(storeBehavioralTelemetryFieldSVF.getStateValue())
        storeAgentContentFieldSVF.setFieldValue(storeAgentContentFieldSVF.getStateValue())
    }

    private val limitedDataCollectionFieldSVF =
        object : StateValueField<Boolean> {
            override fun getFieldValue(): Boolean {
                return false // This button does not have a field value
            }

            override fun setFieldValue(value: Boolean) {
                // No-op, handled by button action listener
            }

            override fun getStateValue(): Boolean = false // Stateless action button

            override fun setStateValue(value: Boolean) {
                // No-op, handled by button action listener
            }

            override fun getFieldInfo(): MutableList<FieldInfo> {
                return mutableListOf()
            }

            override fun getComponent(): JComponent {
                return limitedDataCollectionButton
            }
        }

    // ================= UI COMPONENTS =================

    /**
     * Title label for user information section.
     */
    private val userInfoTitleLabel =
        JBLabel("User Information").apply {
            font = font.deriveFont(font.style or Font.BOLD)
            border = JBUI.Borders.emptyBottom(5)
        }

    /**
     * Sign out button for user authentication management.
     */
    private val signOutButton =
        JButton("Sign Out").apply {
            toolTipText = "Sign out of your Code4Me account"
            addActionListener { handleSignOut() }
        }

    /**
     * Title label for module management section.
     */
    private val moduleTitleLabel =
        JBLabel("Module Management").apply {
            font = font.deriveFont(font.style or Font.BOLD)
            border = JBUI.Borders.emptyBottom(5)
        }

    // ================= MODULE MANAGEMENT =================

    /**
     * Tree model for hierarchical module display.
     */
    private val moduleTreeModel = DefaultTreeModel(DefaultMutableTreeNode("Modules"))

    /**
     * Tree component for displaying available modules.
     */
    private val moduleTree =
        Tree(moduleTreeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            border = BorderFactory.createEtchedBorder()
            toolTipText = "Click checkboxes to enable/disable modules, click names for preferences"

            // Custom renderer for displaying module information
            cellRenderer =
                object : DefaultTreeCellRenderer() {
                    private val checkbox = JBCheckBox()

                    override fun getTreeCellRendererComponent(
                        tree: JTree,
                        value: Any,
                        selected: Boolean,
                        expanded: Boolean,
                        leaf: Boolean,
                        row: Int,
                        hasFocus: Boolean,
                    ): Component {
                        val panel = JPanel(BorderLayout()).apply { isOpaque = false }

                        if (value is DefaultMutableTreeNode) {
                            val userObject = value.userObject
                            if (userObject is PluginModule) {
                                val moduleId = userObject.getPreferenceId()
                                val prefState = getPrefState()

                                val isTopLevelNode = value.parent?.parent == null

                                val label =
                                    JLabel(userObject.moduleName).apply {
                                        if (selected && !isTopLevelNode) {
                                            foreground = JBColor.WHITE
                                            background = JBColor(0x0078D7, 0x4B6EAF)
                                            isOpaque = true
                                        }
                                        toolTipText =
                                            if (isTopLevelNode) {
                                                "Click to expand or collapse" // More accurate tooltip
                                            } else {
                                                "Click checkbox to enable/disable, click name to configure ${userObject.moduleName}"
                                            }
                                    }

                                if (isTopLevelNode) {
                                    panel.add(label, BorderLayout.WEST)
                                } else {
                                    checkbox.isOpaque = false
                                    checkbox.isSelected = prefState.enabledModules.contains(moduleId)

                                    val (canBeDisabled, _) = checkModuleCanBeDisabled(userObject)
                                    checkbox.isEnabled = canBeDisabled

                                    panel.add(checkbox, BorderLayout.WEST)
                                    panel.add(label, BorderLayout.CENTER)
                                }

                                return panel
                            }
                        }

                        return super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                    }
                }
        }

    /**
     * Panel for displaying module-specific preferences.
     */
    private val modulePreferencesPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(8)
        }

    /**
     * Map storing state value fields for module preferences.
     */
    private val modulePreferenceFields = mutableMapOf<String, StateValueField<*>>()

    // ================= SERVICES =================

    private val authState = service<AuthState>().state

    init {
        if (!authState.isAuthenticated()) {
            LOG.warn("ConfigurationSection initialized without authentication")
            authState.clearUserData()
        } else {
            try {
                val currentUser = getAppService().getCurrentUser()
                authState.setUserName(currentUser.user.name)
                authState.setUserEmail(currentUser.user.email)
                authState.setVerified(currentUser.user.verified)
                // if there is a preference and it was updated more than 1 minute ago, load it
                if (currentUser.user.preference != null &&
                    (System.currentTimeMillis() - getPrefState().lastUpdatedTimeStamp) > 60_000
                ) {
                    getPrefState().fromSerializableMap(currentUser.user.preference!!)
                }
            } catch (e: Exception) {
                // remove user data if fetching fails
                authState.clearUserData()
                // rebuild the ui
            }

            initializeFields()
            setupModuleTree()
            LOG.debug("ConfigurationSection initialized")
        }
    }

    /**
     * Disables a module and its dependents.
     */
    private fun disableModuleWithDependents(module: PluginModule) {
        val moduleId = module.getPreferenceId()

        try {
            // Disable the module itself using PrefState API to ensure proper state updates
            PrefState.disableModule(moduleId)

            // Disable any modules that have a hard dependency on this module
            val availableModules = getConfig().getAvailableModules()
            availableModules.forEach { mod ->
                mod.dependencies.forEach { dep ->
                    if (dep.isHard && dep.moduleId == moduleId) {
                        PrefState.disableModule(mod.className)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to disable module or dependents for module $moduleId", e)
        }
    }

    /**
     * Enables a module and its hard dependencies.
     */
    private fun enableModuleWithDependencies(module: PluginModule) {
        val moduleId = module.getPreferenceId()

        try {
            // Enable the module itself using PrefState API
            PrefState.enableModule(moduleId)

            // Ensure all hard dependencies are enabled as well
            val availableModules = getConfig().getAvailableModules()
            val moduleConfig = availableModules.find { it.className == moduleId }

            moduleConfig?.dependencies?.forEach { dep ->
                if (dep.isHard) PrefState.enableModule(dep.moduleId)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to enable dependencies for module $moduleId", e)
        }
    }

    /**
     * Expand a tree node to show its dependencies
     */
    private fun expandNodeToShowDependencies(node: DefaultMutableTreeNode) {
        val treePath = TreePath(node.path)
        moduleTree.expandPath(treePath)

        // Also expand child nodes to show nested dependencies
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val childPath = TreePath(child.path)
            moduleTree.expandPath(childPath)
        }
    }

    /**
     * Updates ChatPanel overlay visibility across all open projects
     */
    private fun updateChatPanelOverlays() {
        ApplicationManager.getApplication().invokeLater {
            ProjectManager.getInstance().openProjects.forEach { project ->
                val toolWindow =
                    com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                        .getToolWindow("Code4Me")

                toolWindow?.contentManager?.contents?.forEach { content ->
                    val component = content.component
                    if (component is me.code4me.chatWindow.components.ChatPanel) {
                        if (authState.isAuthenticated()) {
                            component.onUserAuthenticated()
                        } else {
                            component.onUserLoggedOut()
                        }
                    }
                }
            }
        }
    }

    /**
     * Push current preferences to the backend asynchronously. Called after module enable/disable.
     */
    private fun syncPreferencesToServerAsync() {
        if (!authState.isAuthenticated()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val prefs = getPrefState().toSerializableMap()
                val updateUser = UpdateUser(preference = prefs)
                appService.updateUser(updateUser)
                LOG.info("Synced preferences with server after module toggle")
            } catch (e: Exception) {
                LOG.error("Failed to sync preferences with server after module toggle", e)
                try {
                    ProjectManager.getInstance().openProjects.firstOrNull()?.showPreferenceSyncFailedNotification()
                } catch (_: Exception) {
                    // ignore notification errors
                }
            }
        }
    }

    /**
     * Initializes form fields with current preference values.
     */
    private fun initializeFields() {
        val prefState = getPrefState()
        storeContextField.isSelected = prefState.storeContext
        storeContextualTelemetryField.isSelected = prefState.storeContextualTelemetry
        storeBehavioralTelemetryField.isSelected = prefState.storeBehavioralTelemetry
        storeAgentContentField.isSelected = prefState.storeAgentContent
    }

    /**
     * Sets up the module tree with data and listeners.
     */
    private fun setupModuleTree() {
        updateModuleTree()

        // Add selection listener for dynamic preference panel updates
        moduleTree.addTreeSelectionListener {
            val selectedNode = moduleTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            if (selectedNode != null) {
                updateModulePreferencesPanel()
            } else {
                updateModulePreferencesPanel()
            }
        }

        moduleTree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val path = moduleTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return

                    if (node.userObject !is PluginModule) return

                    val isTopLevelNode = node.parent?.parent == null

                    if (isTopLevelNode) {
                        moduleTree.selectionPath = path

                        return
                    }

                    val rowBounds = moduleTree.getPathBounds(path) ?: return
                    val checkboxWidth = 30 // Approximate checkbox width
                    if (e.x - rowBounds.x <= checkboxWidth) {
                        handleModuleToggle(node)
                    } else {
                        moduleTree.selectionPath = path
                    }
                }
            },
        )
        expandAllTreeNodes()
        updateModulePreferencesPanel()
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
        addModuleHeader(module)
        addModuleDescription(module)
        addModuleStatus(module)

        val preferences = PrefState.getModulePreferences(module.getPreferenceId())
        if (preferences.isNotEmpty()) {
            addModulePreferences(module)
        }

        modulePreferencesPanel.add(Box.createRigidArea(Dimension(0, 8)))
    }

    /**
     * Adds module description to the preferences panel.
     */
    private fun addModuleDescription(module: PluginModule) {
        val descriptionText =
            try {
                val cfg = getConfig()
                val top = cfg.getAvailableModules()
                val all = top + top.flatMap { it.submodules }

                val runtimeId = module.getPreferenceId()
                val runtimeClass = module.javaClass.name
                val runtimeName = module.moduleName

                val mc =
                    all.firstOrNull {
                        it.id == runtimeId || it.className == runtimeClass || it.name == runtimeName
                    }
                mc?.description ?: ""
            } catch (_: Exception) {
                ""
            }

        if (descriptionText.isBlank()) return

        val descriptionArea =
            JBTextArea(descriptionText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = modulePreferencesPanel.background
                font = JLabel().font
                border = JBUI.Borders.empty(0, 0, 6, 0)
                rows =
                    when {
                        descriptionText.length > 200 -> 4
                        descriptionText.length > 100 -> 3
                        else -> 2
                    }
            }
        modulePreferencesPanel.add(descriptionArea)
        modulePreferencesPanel.add(JLabel())
    }

    /**
     * Adds module-specific preference controls to the panel.
     */
    private fun addModulePreferences(module: PluginModule) {
        val preferences = PrefState.getModulePreferences(module.getPreferenceId())

        if (preferences.isNotEmpty()) {
            val prefsHeaderLabel =
                JLabel("Module Preferences").apply {
                    font = font.deriveFont(Font.BOLD)
                }
            modulePreferencesPanel.add(prefsHeaderLabel)
            modulePreferencesPanel.add(JLabel())
        }

        preferences.forEach { preference ->
            addPreferenceField(module, preference)
        }

        modulePreferencesPanel.add(Box.createRigidArea(Dimension(0, 8)))
    }

    /**
     * Shows a message when no valid module is selected.
     */
    private fun showNoSelectionMessage() {
        modulePreferencesPanel.add(
            JLabel("Select a module to manage preferences").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC)
            },
        )
    }

    private fun handleModuleToggle(node: DefaultMutableTreeNode) {
        val module = node.userObject as? PluginModule ?: return
        val moduleId = module.getPreferenceId()
        val prefState = getPrefState()
        val currentlyEnabled = prefState.enabledModules.contains(moduleId)

        try {
            if (!currentlyEnabled) {
                // Enabling module
                enableModuleWithDependencies(module)
                expandNodeToShowDependencies(node)
                LOG.debug("Module $moduleId enabled with dependencies")
            } else {
                // Check if module can be disabled
                val (canBeDisabled, reason) = checkModuleCanBeDisabled(module)
                if (!canBeDisabled) {
                    LOG.debug("Cannot disable module $moduleId: $reason")
                    return
                }

                // Disabling module
                val treePath = TreePath(node.path)
                moduleTree.expandPath(treePath)
                disableModuleWithDependents(module)
                LOG.debug("Module $moduleId disabled with dependents")
            }

            // Update UI consistently
            moduleTree.repaint()
            updateModulePreferencesPanel()
            // Immediately sync preference changes with backend
            syncPreferencesToServerAsync()
        } catch (e: Exception) {
            LOG.error("Failed to toggle module $moduleId", e)
        }
    }

    /**
     * Expands all nodes in the module tree.
     */
    private fun expandAllTreeNodes() {
        val root = moduleTreeModel.root as DefaultMutableTreeNode
        expandAllNodesRecursively(root)
    }

    private fun expandAllNodesRecursively(node: DefaultMutableTreeNode) {
        if (node.childCount > 0) {
            val path = TreePath(node.path)
            moduleTree.expandPath(path)

            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as DefaultMutableTreeNode
                expandAllNodesRecursively(child)
            }
        }
    }

    /**
     * Updates the module tree with current available modules.
     */
    private fun updateModuleTree() {
        val rootNode = DefaultMutableTreeNode("Modules")

        try {
            val modules = getModuleManager().getAvailableModules()
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
        } catch (_: Exception) {
            LOG.debug("Module ${module.getPreferenceId()} has no submodules or failed to retrieve them")
        }
    }

    /**
     * Checks if a module can be disabled based on dependency constraints.
     */
    private fun checkModuleCanBeDisabled(module: PluginModule): Pair<Boolean, String?> {
        return try {
            val moduleNode = findModuleNodeById(module.getPreferenceId())
            val isTopLevelNode = moduleNode?.parent?.parent == null

            if (isTopLevelNode) {
                return false to "Top-level modules cannot be disabled"
            }

            val moduleId = module.getPreferenceId()
            val dependants = getConfig().getTransitiveHardDependants(moduleId)

            if (dependants.isNotEmpty()) {
                false to "Required by: ${dependants.joinToString()}"
            } else {
                true to null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to check module dependencies for ${module.getPreferenceId()}", e)
            true to null
        }
    }

    /**
     * Finds a module node by its ID in the tree.
     */
    private fun findModuleNodeById(
        moduleId: String,
        root: DefaultMutableTreeNode = moduleTreeModel.root as DefaultMutableTreeNode,
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
     * Adds module header information to the preferences panel.
     */
    private fun addModuleHeader(module: PluginModule) {
        val moduleNameLabel =
            JBLabel(module.moduleName).apply {
                font = font.deriveFont(Font.BOLD)
            }
        modulePreferencesPanel.add(moduleNameLabel)
        modulePreferencesPanel.add(JLabel())
    }

    private fun addModuleStatus(module: PluginModule) {
        val node = findModuleNodeById(module.getPreferenceId())
        val isTopLevelNode = node?.parent?.parent == null
        if (isTopLevelNode) return // no status for top-level nodes
        val prefState = getPrefState()
        val isEnabled = prefState.enabledModules.contains(module.getPreferenceId())
        val statusLabel =
            JLabel("Status: ${if (isEnabled) "Enabled" else "Disabled"}").apply {
                foreground = if (isEnabled) JBColor.GREEN else JBColor.RED
                border = JBUI.Borders.empty(6, 0, 6, 0)
            }
        modulePreferencesPanel.add(statusLabel)
        modulePreferencesPanel.add(JLabel())
    }

    /**
     * Adds a single preference field to the panel.
     */
    private fun addPreferenceField(
        module: PluginModule,
        preference: Preference,
    ) {
        val field = createPreferenceField(module, preference)

        // For boolean preferences, no separate label is needed as checkbox contains the text
        if (preference.type == PreferenceType.BOOLEAN) {
            // Add tooltip to field
            if (preference.description.isNotEmpty()) {
                field.toolTipText = preference.description
            }
            modulePreferencesPanel.add(field)
        } else {
            // Create label with tooltip indicator for non-boolean types
            val labelText =
                if (preference.description.isNotEmpty()) {
                    "${preference.displayName} ⓘ"
                } else {
                    preference.displayName
                }

            val label =
                JBLabel("$labelText:").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = preferredSize
                    if (preference.description.isNotEmpty()) {
                        toolTipText = preference.description
                    }
                    border = JBUI.Borders.empty(0)
                }

            modulePreferencesPanel.add(label)

            // Add tooltip to field as well
            if (preference.description.isNotEmpty()) {
                field.toolTipText = preference.description
            }

            modulePreferencesPanel.add(field)
        }
    }

    /**
     * Creates appropriate UI component for a preference based on its type.
     */
    private fun createPreferenceField(
        module: PluginModule,
        preference: Preference,
    ): JComponent {
        val moduleId = module.getPreferenceId()

        return when (preference.type) {
            PreferenceType.BOOLEAN -> createBooleanField(moduleId, preference)
            PreferenceType.STRING -> createStringField(moduleId, preference)
            PreferenceType.INT -> createIntegerField(moduleId, preference)
            PreferenceType.FLOAT -> createFloatField(moduleId, preference)
            PreferenceType.LIST -> createListField(moduleId, preference)
            PreferenceType.TEXT -> createTextBoxField(moduleId, preference)
            PreferenceType.LONG -> createIntegerField(moduleId, preference) // Treat as integer for now
            PreferenceType.DOUBLE -> createFloatField(moduleId, preference) // Treat as float for now
            else -> {
                LOG.warn("Unsupported preference type: ${preference.type}")
                JBLabel("Unsupported type: ${preference.type}")
            }
        }
    }

    /**
     * creates a list preference field with a ComboBox.
     */
    private fun createListField(
        moduleId: String,
        preference: Preference,
    ): JComponent {
        // Parse the comma-separated values from defaultValue
        val options = preference.defaultValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val selectedValue = options.firstOrNull() ?: ""

        // Create ComboBox with options
        val comboBox =
            ComboBox(options.toTypedArray()).apply {
                selectedItem = selectedValue
                toolTipText = preference.description
            }

        // Create the specialized list preference field
        val stateValueField = ModuleListPreferenceField(moduleId, preference, comboBox, options)

        // Store the field for later use
        val fieldKey = "$moduleId.${preference.key}"
        modulePreferenceFields[fieldKey] = stateValueField

        // Initialize with current state value
        stateValueField.getStateValue().let { currentValue ->
            stateValueField.setFieldValue(
                if (currentValue.isNotEmpty()) {
                    currentValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    options
                }.joinToString(","),
            )
        }

        // Add action listener to update state value on selection change
        comboBox.addActionListener {
            val selected = comboBox.selectedItem?.toString() ?: ""
            val otherValues = options.filter { it != selected }

            stateValueField.setStateValue(
                if (selected.isNotEmpty()) {
                    listOf(selected) + otherValues
                } else {
                    options
                }.joinToString(","),
            )
        }

        // Create container panel
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(comboBox)
                border = JBUI.Borders.emptyBottom(8)
            }

        return panel
    }

    /**
     * Creates a text box preference field with a scrollable text area.
     */
    private fun createTextBoxField(
        moduleId: String,
        preference: Preference,
    ): JComponent {
        val textArea =
            JBTextArea().apply {
                text = preference.defaultValue
                toolTipText = preference.description
                lineWrap = true
                wrapStyleWord = true
                rows = 5
                columns = 30
                maximumSize = Dimension(PREFERENCES_PANEL_WIDTH - 60, 120)
            }

        val scrollPane =
            JBScrollPane(textArea).apply {
                preferredSize = Dimension(PREFERENCES_PANEL_WIDTH - 60, 100)
                minimumSize = Dimension(MIN_PREFERENCES_PANEL_WIDTH - 60, 80)
                maximumSize = Dimension(PREFERENCES_PANEL_WIDTH - 60, 120)
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }

        // Create the text preference field
        val stateValueField = ModuleTextPreferenceField(moduleId, preference, textArea)

        val fieldKey = "$moduleId.${preference.key}"
        modulePreferenceFields[fieldKey] = stateValueField

        // Initialize with current state value
        stateValueField.getStateValue().let { currentValue ->
            stateValueField.setFieldValue(currentValue)
        }

        // set up document listener to update state value on text change
        var debounceTimer: Timer? = null

        textArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = scheduleUpdate()

                override fun removeUpdate(e: DocumentEvent?) = scheduleUpdate()

                override fun changedUpdate(e: DocumentEvent?) = scheduleUpdate()

                private fun scheduleUpdate() {
                    debounceTimer?.stop()
                    debounceTimer =
                        Timer(300) {
                            stateValueField.setStateValue(textArea.text)
                        }.apply {
                            isRepeats = false
                            start()
                        }
                }
            },
        )

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            scrollPane.alignmentX = Component.LEFT_ALIGNMENT
            add(scrollPane)
        }
    }

    /**
     * Creates a boolean preference field (checkbox).
     */
    private fun createBooleanField(
        moduleId: String,
        preference: Preference,
    ): JComponent {
        val checkbox =
            JBCheckBox(preference.displayName).apply {
                toolTipText = preference.description
                // Initialize with current value
                val currentValue = PrefState.getPreferenceValue(moduleId, preference.key)
                isSelected = currentValue?.toBoolean() ?: preference.defaultValue.toBoolean()
            }

        // Create and register the state value field
        val stateField = ModuleBooleanPreferenceField(moduleId, preference, checkbox)
        val fullKey = "$moduleId.${preference.key}"
        modulePreferenceFields[fullKey] = stateField

        // Add change listener to update state immediately
        checkbox.addActionListener {
            stateField.setStateValue(checkbox.isSelected)
        }

        return checkbox
    }

    /**
     * Creates a string preference field.
     */
    private fun createStringField(
        moduleId: String,
        preference: Preference,
    ): JComponent {
        val textField =
            JBTextField().apply {
                toolTipText = preference.description
                // Initialize with current value
                val currentValue = PrefState.getPreferenceValue(moduleId, preference.key)
                text = currentValue ?: preference.defaultValue
            }

        // Create and register the state value field
        val stateField = ModuleStringPreferenceField(moduleId, preference, textField)
        val fullKey = "$moduleId.${preference.key}"
        modulePreferenceFields[fullKey] = stateField

        // Add document listener to update state when text changes
        textField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = updateValue()

                override fun removeUpdate(e: DocumentEvent?) = updateValue()

                override fun changedUpdate(e: DocumentEvent?) = updateValue()

                private fun updateValue() {
                    stateField.setStateValue(textField.text)
                }
            },
        )

        val panel =
            JPanel(BorderLayout()).apply {
                add(Box.createHorizontalStrut(10), BorderLayout.CENTER)
                add(textField, BorderLayout.WEST)
            }

        return panel
    }

    /**
     * Creates an integer preference field with input filtering and user-friendly validation.
     */
    private fun createIntegerField(
        moduleId: String,
        preference: Preference,
    ): JComponent {
        val textField =
            JBTextField().apply {
                toolTipText = preference.description
                // Initialize with current value
                val currentValue = PrefState.getPreferenceValue(moduleId, preference.key)
                text = currentValue ?: preference.defaultValue
            }

        val warningLabel =
            JBLabel().apply {
                foreground = JBColor.RED
                isVisible = false
            }

        // Filter input to allow only digits and a leading negative sign
        val document = textField.document as PlainDocument
        document.documentFilter =
            object : DocumentFilter() {
                override fun insertString(
                    fb: FilterBypass,
                    offset: Int,
                    string: String?,
                    attr: AttributeSet?,
                ) {
                    if (string == null) return
                    val newText = StringBuilder(textField.text).insert(offset, string).toString()
                    if (newText.matches(Regex("-?\\d*"))) {
                        super.insertString(fb, offset, string, attr)
                    }
                }

                override fun replace(
                    fb: FilterBypass,
                    offset: Int,
                    length: Int,
                    text: String?,
                    attrs: AttributeSet?,
                ) {
                    if (text == null) return
                    val oldText = textField.text
                    val newText = oldText.substring(0, offset) + text + oldText.substring(offset + length)
                    if (newText.matches(Regex("-?\\d*"))) {
                        super.replace(fb, offset, length, text, attrs)
                    }
                }
            }

        // Create and register the state value field
        val stateField = ModuleIntegerPreferenceField(moduleId, preference, textField)
        val fullKey = "$moduleId.${preference.key}"
        modulePreferenceFields[fullKey] = stateField

        // Show error only on focus lost, not while typing
        textField.addFocusListener(
            object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    val text = textField.text
                    if (text.isEmpty() || text == "-") {
                        warningLabel.text = "Please enter a value"
                        warningLabel.isVisible = true
                    } else if (text.toIntOrNull() == null) {
                        warningLabel.text = "Please enter a valid integer"
                        warningLabel.isVisible = true
                    } else {
                        warningLabel.isVisible = false
                        stateField.setStateValue(text.toInt())
                    }
                }

                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    warningLabel.isVisible = false
                }
            },
        )

        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                val inputPanel =
                    JPanel(BorderLayout()).apply {
                        add(textField, BorderLayout.WEST)
                    }

                add(inputPanel)
                add(warningLabel)
            }

        return panel
    }

    /**
     * Creates a floating-point preference field with validation.
     */
    private fun createFloatField(
        moduleId: String,
        preference: Preference,
    ): JComponent {
        val textField =
            JBTextField().apply {
                toolTipText = preference.description
                // Initialize with current value
                val currentValue = PrefState.getPreferenceValue(moduleId, preference.key)
                text = currentValue ?: preference.defaultValue
            }

        val warningLabel =
            JBLabel().apply {
                foreground = JBColor.RED
                isVisible = false
            }

        // Filter input to allow only valid float characters
        val document = textField.document as PlainDocument
        document.documentFilter =
            object : DocumentFilter() {
                override fun insertString(
                    fb: FilterBypass,
                    offset: Int,
                    string: String?,
                    attr: AttributeSet?,
                ) {
                    if (string == null) return
                    val newText = StringBuilder(textField.text).insert(offset, string).toString()
                    if (newText.matches(Regex("-?\\d*(\\.\\d*)?"))) {
                        super.insertString(fb, offset, string, attr)
                    }
                }

                override fun replace(
                    fb: FilterBypass,
                    offset: Int,
                    length: Int,
                    text: String?,
                    attrs: AttributeSet?,
                ) {
                    if (text == null) return
                    val oldText = textField.text
                    val newText = oldText.substring(0, offset) + text + oldText.substring(offset + length)
                    if (newText.matches(Regex("-?\\d*(\\.\\d*)?"))) {
                        super.replace(fb, offset, length, text, attrs)
                    }
                }
            }

        // Create and register the state value field
        val stateField = ModuleFloatPreferenceField(moduleId, preference, textField)
        val fullKey = "$moduleId.${preference.key}"
        modulePreferenceFields[fullKey] = stateField

        // Show error only on focus lost, not while typing
        textField.addFocusListener(
            object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    val text = textField.text
                    if (text.isEmpty() || text == "-" || text == ".") {
                        warningLabel.text = "Please enter a value"
                        warningLabel.isVisible = true
                    } else if (text.toFloatOrNull() == null) {
                        warningLabel.text = "Please enter a valid number"
                        warningLabel.isVisible = true
                    } else {
                        warningLabel.isVisible = false
                        stateField.setStateValue(text.toFloat())
                    }
                }

                override fun focusGained(e: java.awt.event.FocusEvent?) {
                    warningLabel.isVisible = false
                }
            },
        )

        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                val inputPanel =
                    JPanel(BorderLayout()).apply {
                        add(textField, BorderLayout.WEST)
                    }

                add(inputPanel)
                add(warningLabel)
            }

        return panel
    }

    override fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>,
    ) {
        // Register application preference fields
        stateValueFields.addAll(
            listOf(
                storeContextFieldSVF,
                storeContextualTelemetryFieldSVF,
                storeBehavioralTelemetryFieldSVF,
                storeAgentContentFieldSVF,
                limitedDataCollectionFieldSVF,
            ),
        )

        // Register module preference fields
        stateValueFields.addAll(modulePreferenceFields.values)

        // Create main configuration panel
        val mainPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(FORM_PADDING)
            }

        // Create content panel
        val contentPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty()
            }

        // Add user information section
        contentPanel.add(createNavigationPanel(), BorderLayout.NORTH)

        // Add configuration sections
        contentPanel.add(createConfigurationPanel(), BorderLayout.CENTER)

        mainPanel.add(contentPanel, BorderLayout.CENTER)
        builder.addComponent(mainPanel)

        LOG.debug("Configuration section applied to form builder")
    }

    /**
     * Creates a simple navigation panel with the manage profile button.
     */
    private fun createNavigationPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(SECTION_SPACING)

            val titlePanel =
                JPanel(BorderLayout()).apply {
                    val titleLabel =
                        JBLabel("Configuration Settings").apply {
                            font = font.deriveFont(font.style or Font.BOLD)
                        }
                    add(titleLabel, BorderLayout.WEST)
                    add(manageProfileButton, BorderLayout.EAST)
                }

            add(titlePanel, BorderLayout.CENTER)
        }
    }

    /**
     * Refreshes the user information panel with the latest data from auth state.
     */
    private fun refreshUserInfoPanel() {
        userInfoTitleLabel.text = "User Information"
        userInfoTitleLabel.foreground = JBColor.foreground()

        // Update user info labels
        val userName = authState.getUserName() ?: "Unknown User"
        val userEmail = authState.getUserEmail() ?: "Unknown Email"

        // Update labels in the user info panel
        val userInfoPanel = modulePreferencesPanel.components.firstOrNull { it is JPanel } as? JPanel
        userInfoPanel?.let {
            it.components.forEach { component ->
                if (component is JLabel) {
                    when (component.text.startsWith("Name:")) {
                        true -> component.text = "Name: $userName"
                        false -> component.text = "Email: $userEmail"
                    }
                }
            }
        }
    }

    /**
     * Creates the main configuration panel with preferences and modules.
     */
    private fun createConfigurationPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(SECTION_SPACING)

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
            val configTitle =
                JBLabel("Application Preferences").apply {
                    font = font.deriveFont(font.style or Font.BOLD)
                    border = JBUI.Borders.emptyBottom(5)
                }

            val storeSubtitle =
                JBLabel("Store: ").apply {
                    font = font.deriveFont(font.style or Font.ITALIC)
                    border = JBUI.Borders.emptyBottom(5)
                }

            val optionsPanel =
                JPanel(GridLayout(1, 4, 5, 5)).apply {
                    add(storeContextField)
                    add(storeContextualTelemetryField)
                    add(storeBehavioralTelemetryField)
                    add(storeAgentContentField)
                }

            add(configTitle, BorderLayout.NORTH)
            add(storeSubtitle, BorderLayout.NORTH)
            add(optionsPanel, BorderLayout.CENTER)

            add(limitedDataCollectionButton, BorderLayout.SOUTH)
        }
    }

    /**
     * Creates the module management panel with tree and preferences.
     */
    private fun createModuleManagementPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(SECTION_SPACING)

            add(moduleTitleLabel, BorderLayout.NORTH)

            // Module tree with scroll pane
            val treeScrollPane =
                JBScrollPane(moduleTree).apply {
                    preferredSize = Dimension(MODULE_TREE_WIDTH, MODULE_TREE_HEIGHT)
                    minimumSize = Dimension(MIN_MODULE_TREE_WIDTH, MIN_MODULE_TREE_HEIGHT)
                    border = BorderFactory.createEtchedBorder()
                }

            // Module preferences panel
            modulePreferencesPanel.layout = GridLayout(0, 2, 5, 5)

            val preferencesScrollPane =
                JBScrollPane(modulePreferencesPanel).apply {
                    border = JBUI.Borders.emptyLeft(10)
                    preferredSize = Dimension(PREFERENCES_PANEL_WIDTH, MODULE_TREE_HEIGHT)
                    minimumSize = Dimension(MIN_PREFERENCES_PANEL_WIDTH, MIN_MODULE_TREE_HEIGHT)
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                }

            // resizable divider
            val splitPane =
                JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScrollPane, preferencesScrollPane).apply {
                    isOneTouchExpandable = false
                    dividerLocation = (MODULE_TREE_WIDTH + PREFERENCES_PANEL_WIDTH) / 2 // Initial position
                    resizeWeight = 0.4
                    border = JBUI.Borders.emptyTop(5)

                    // Make it theme-appropriate
                    background = JBColor.PanelBackground
                    dividerSize = 6 // Make divider slightly thicker for better visibility

                    // Set divider color to match theme
                    ui.apply {
                        if (this is javax.swing.plaf.basic.BasicSplitPaneUI) {
                            divider.background = JBColor.border()
                            divider.border = JBUI.Borders.empty()
                        }
                    }
                }

            add(splitPane, BorderLayout.CENTER)
        }
    }

    /**
     * Handles user sign out operation with immediate chat panel updates.
     */
    private fun handleSignOut() {
        try {
            // First clear user data synchronously to ensure it completes
            authState.clearUserData()
            LOG.info("User data cleared successfully during sign out")

            // Then deactivate session
            appService.deactivateSession()

            // Update chat panel overlays immediately after sign out
            updateChatPanelOverlays()

            Messages.showInfoMessage(
                "You have been signed out successfully.",
                "Sign Out Complete",
            )
            LOG.info("User signed out successfully")
        } catch (e: Exception) {
            LOG.error("Failed to sign out user", e)
            Messages.showErrorDialog(
                "An error occurred while signing out. Please try again.",
                "Sign Out Error",
            )
        }
    }
}
