package me.code4me.chatWindow.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.code4me.chatWindow.components.chatDisplayPanel.ChatDisplayPanel
import me.code4me.chatWindow.components.historyPanel.HistoryPanel
import me.code4me.chatWindow.components.inputPanel.InputPanel
import me.code4me.chatWindow.components.managers.ChatIOManager
import me.code4me.chatWindow.components.managers.ChatSessionManager
import me.code4me.chatWindow.components.managers.ChatViewManager
import me.code4me.chatWindow.components.persistence.ChatWindowStateService
import me.code4me.chatWindow.components.topBarPanel.TopBarPanel
import me.code4me.chatWindow.components.utils.TitleResponsePair
import me.code4me.services.config.getConfig
import me.code4me.services.project.getProjectChatService
import me.code4me.services.state.TOKEN_PROPERTY
import me.code4me.services.state.getAuthState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * Main chat panel component that orchestrates the entire chat interface.
 *
 * Manages chat sessions, handles user authentication, coordinates between input/output
 * components, and provides the primary interface for AI-powered conversations. Includes
 * authentication overlay, message editing, regeneration capabilities, and persistent
 * session management.
 *
 * @param boundProject The project this panel belongs to. The factory always supplies the
 *   tool window's project; the open-project fallback below applies only when no project was
 *   supplied (platform contexts that genuinely lack one), never as a preference over it.
 */
class ChatPanel(boundProject: Project? = null) : JBPanel<ChatPanel>(BorderLayout()) {
    companion object {
        private const val USER_NAME = "You"
        private const val AI_NAME = "Code4Me V2"
    }

    private val LOG = thisLogger()
    private val ioManager = ChatIOManager()
    private var sessionManager: ChatSessionManager? = null
    private val selectedFiles = mutableSetOf<VirtualFile>()
    private var welcomeShown = true
    private var useWeb = false
    private var project: Project? = boundProject ?: ProjectManager.getInstance().openProjects.firstOrNull()
    private var stateService: ChatWindowStateService? = null

    private lateinit var inputPanel: InputPanel
    private lateinit var topBarPanel: TopBarPanel
    private lateinit var chatDisplayPanel: ChatDisplayPanel
    private lateinit var historyPanel: HistoryPanel
    private val viewManager = ChatViewManager()
    private val uiScope = CoroutineScope(Dispatchers.Default)
    private val aiScope = CoroutineScope(Dispatchers.IO)

    // Multi-session job tracking
    private val activeJobs = mutableMapOf<String, Job>()
    private val loadingTimers = mutableMapOf<String, Timer>()

    // The "Generating…" indicator is display-only state, held outside session.messages. Writing it
    // into the session and then overwriting that entry with the real reply meant a cancelled or
    // failed request left the placeholder persisted as if it were an assistant message, and an
    // empty session had its first user message overwritten instead.
    private val loadingMessages = mutableMapOf<String, String>()

    private var editIndex: Int? = null
    private var isEditing = false

    // Authentication overlay components
    private val authOverlayPanel = createAuthOverlay()
    private val authState = getAuthState()
    private val authStateListener =
        PropertyChangeListener { event ->
            if (event.propertyName == TOKEN_PROPERTY) {
                ApplicationManager.getApplication().invokeLater {
                    updateAuthOverlayVisibility()
                }
            }
        }

    init {
        setupPanelLayout()
        setupAuthStateListener()
        restoreLastSession()
        loadModelsFromConfig()
        updateAuthOverlayVisibility()

        addHierarchyListener { e ->
            val SHOWING_CHANGED = java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()
            if ((e.changeFlags and SHOWING_CHANGED) != 0L && isShowing) {
                ApplicationManager.getApplication().invokeLater { updateAuthOverlayVisibility() }
            }
        }
    }

    /**
     * Creates the authentication overlay that blocks chat functionality when user is not signed in.
     *
     * Provides a semi-transparent overlay with sign-in instructions and a button to open
     * settings. Consumes all mouse events to prevent interaction with underlying components.
     *
     * @return Configured overlay panel with authentication UI
     */
    private fun createAuthOverlay(): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun contains(
                x: Int,
                y: Int,
            ): Boolean = true

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.color = Color(0, 0, 0, 150) // 60% opacity black
                g2.fillRect(0, 0, width, height)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            isVisible = false

            val contentPanel =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }

            // Main message
            val titleLabel =
                JLabel("Authentication Required", SwingConstants.CENTER).apply {
                    foreground = Color.WHITE
                    font = Font("SansSerif", Font.BOLD, 20)
                    alignmentX = CENTER_ALIGNMENT
                }

            val messageLabel =
                JLabel("Please sign in to access the chat functionality", SwingConstants.CENTER).apply {
                    foreground = Color.LIGHT_GRAY
                    font = Font("SansSerif", Font.PLAIN, 14)
                    alignmentX = CENTER_ALIGNMENT
                }

            // Action button - opens settings to the correct page
            val openSettingsButton =
                JButton("Open Settings").apply {
                    alignmentX = CENTER_ALIGNMENT
                    font = Font("SansSerif", Font.BOLD, 14)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                    addActionListener {
                        project?.let { currentProject: Project ->
                            try {
                                // Try to open the specific Code4Me settings page
                                val settings = com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                settings.showSettingsDialog(
                                    currentProject,
                                    me.code4me.settings.Code4MeConfigurable::class.java,
                                )
                            } catch (e: Exception) {
                                LOG.warn("Could not open settings", e)
                                // Show help message instead
                                com.intellij.openapi.ui.Messages.showInfoMessage(
                                    "Please open File → Settings → Tools → Code4Me to configure authentication",
                                    "Settings",
                                )
                            }
                        }
                    }
                }

            contentPanel.add(Box.createVerticalGlue())
            contentPanel.add(titleLabel)
            contentPanel.add(Box.createVerticalStrut(10))
            contentPanel.add(messageLabel)
            contentPanel.add(Box.createVerticalStrut(20))
            contentPanel.add(openSettingsButton)
            contentPanel.add(Box.createVerticalGlue())

            add(contentPanel, BorderLayout.CENTER)

            // Consume all mouse events to prevent interaction with underlying components
            addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent?) {}

                    override fun mouseClicked(e: MouseEvent?) {}

                    override fun mouseReleased(e: MouseEvent?) {}
                },
            )

            addMouseMotionListener(
                object : java.awt.event.MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent?) {}

                    override fun mouseDragged(e: MouseEvent?) {}
                },
            )
        }
    }

    private fun setupAuthStateListener() {
        authState.addPropertyChangeListener(TOKEN_PROPERTY, authStateListener)
    }

    /**
     * Updates the visibility and functionality of the authentication overlay.
     *
     * Shows overlay when user is not authenticated and clears all chat data for security.
     * Enables/disables chat components based on authentication state and forces UI refresh.
     */
    private fun updateAuthOverlayVisibility() = authState.withTokenLock {
        val authGeneration = authState.tokenGeneration()
        val isAuthenticated = authState.isAuthenticated()
        authOverlayPanel.isVisible = !isAuthenticated

        if (!isAuthenticated) {
            // IMMEDIATELY clear all chat content when not authenticated
            chatDisplayPanel.updateContent(emptyList())
            historyPanel.refresh()
            topBarPanel.updateTitle()

            // Cancel all active jobs and clear timers
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            loadingTimers.values.forEach { it.stop() }
            loadingTimers.clear()

            // Force clear any cached session data
            sessionManager?.let { manager ->
                val allSessions = manager.getAllSessions()
                allSessions.forEach { session ->
                    manager.deleteSession(session, false)
                }
            }

            // Create new empty session manager
            project?.let { proj ->
                val chatService = getProjectChatService(proj)
                chatService.clearAllChatsAndMemory()
                sessionManager = ChatSessionManager(chatService)
                topBarPanel.setSessionManager(sessionManager!!)
                historyPanel.setSessionManager(sessionManager!!)
                topBarPanel.updateTitle()
                historyPanel.refresh()
                refreshChatDisplay()
            }

            // Clear state service
            stateService?.setLastSessionId(null)

            // Force UI refresh with empty content
            ApplicationManager.getApplication().invokeLater {
                authState.runIfSignedOut(authGeneration) {
                    chatDisplayPanel.updateContent(emptyList())
                    chatDisplayPanel.forceRefresh()
                    historyPanel.refresh()
                    topBarPanel.updateTitle()
                }
            }

            // Disable all chat functionality when not authenticated
            inputPanel.isEnabled = false
            topBarPanel.isEnabled = false
            chatDisplayPanel.isEnabled = false
            historyPanel.isEnabled = false
        } else {
            // Re-enable chat functionality when authenticated
            inputPanel.isEnabled = true
            topBarPanel.isEnabled = true
            chatDisplayPanel.isEnabled = true
            historyPanel.isEnabled = true
        }
        val wrapper = authOverlayPanel.parent
        wrapper?.revalidate()
        wrapper?.repaint()
        this.revalidate()
        this.repaint()
        authOverlayPanel.revalidate()
        authOverlayPanel.repaint()
    }

    /**
     * Returns the project this panel is bound to (the factory-supplied project when present).
     */
    fun getBoundProject(): Project? = project

    /**
     * Initializes and arranges all major UI components with proper layout management.
     *
     * Sets up the project context, creates all panels (input, display, history, top bar),
     * configures the view manager, and creates the layered structure with authentication overlay.
     */
    private fun setupPanelLayout() {
        border = JBUI.Borders.empty()
        // The project is resolved at construction (factory-supplied first, open-project
        // fallback only when none was supplied). Bail out solely when the platform
        // genuinely has no project; the factory path always carries a non-null project,
        // so its panels are always initialized.
        if (project == null) return

        // Initialize the state service
        stateService = ChatWindowStateService.getInstance(project!!)

        // Initialize the session manager with the repository
        val chatRepository = getProjectChatService(project!!)
        sessionManager = ChatSessionManager(chatRepository)

        inputPanel = createInputPanel(project!!)
        inputPanel.setOnStop { cancelGeneration() }
        chatDisplayPanel = ChatDisplayPanel(project!!)
        chatDisplayPanel.onRestore = {
            refreshChatDisplay()
            chatDisplayPanel.forceRefresh()
        }
        chatDisplayPanel.onRegenerateFromIndex = { index -> regenerateFromIndex(index) }
        chatDisplayPanel.onEditUserMessage = { index, message ->
            enterEditMode(index, message)
        }
        topBarPanel = createTopBarPanel()
        historyPanel = createHistoryPanel()

        val mainChatArea = createMainChatArea()
        viewManager.setViews(mainChatArea, historyPanel)

        // Create layered wrapper for the entire chat area
        val layeredWrapper =
            JPanel().apply {
                layout = OverlayLayout(this)
                isOpaque = false
            }

        val chatContainer = viewManager.getContainer()

        // Set alignment for both components
        chatContainer.alignmentX = LEFT_ALIGNMENT
        chatContainer.alignmentY = TOP_ALIGNMENT
        authOverlayPanel.alignmentX = LEFT_ALIGNMENT
        authOverlayPanel.alignmentY = TOP_ALIGNMENT

        // Add components - overlay goes on top

        layeredWrapper.add(chatContainer)
        layeredWrapper.add(authOverlayPanel)
        layeredWrapper.setComponentZOrder(authOverlayPanel, 0)
        add(layeredWrapper, BorderLayout.CENTER)
    }

    override fun doLayout() {
        super.doLayout()
        // Ensure overlay covers the entire panel
        authOverlayPanel.setBounds(0, 0, width, height)
    }

    private fun createInputPanel(project: Project) =
        InputPanel(
            project = project,
            onSend = ::sendMessage,
            onWebToggle = { enabled -> useWeb = enabled },
            onFileClose = ::removeSelectedFile,
            onFileSelected = ::addSelectedFile,
        )

    private fun createTopBarPanel() =
        TopBarPanel(
            sessionManager!!,
            onSessionSwitched = {
                saveCurrentSession()
                refreshChatDisplay()
            },
            onNewChatCreated = {
                saveCurrentSession()
                resetToWelcome()
            },
            onHistoryClicked = {
                saveCurrentSession()
                viewManager.showHistoryPanel()
            },
            onTitleRenamed = { historyPanel.refresh() },
        )

    private fun createHistoryPanel(): HistoryPanel {
        return HistoryPanel(sessionManager!!) {
            ApplicationManager.getApplication().invokeLater {
                topBarPanel.updateTitle()
                saveCurrentSession()
                refreshChatDisplay()
                viewManager.showChatPanel()
            }
        }
    }

    private fun createMainChatArea() =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(topBarPanel, BorderLayout.NORTH)
            add(chatDisplayPanel, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

    private fun saveCurrentSession() {
        sessionManager?.currentSession?.let { session ->
            stateService?.setLastSessionId(session.id.toString())
            sessionManager?.chatRepository?.saveChat(session)
        }
    }

    private fun restoreLastSession() {
        val lastSessionId = stateService?.getLastSessionId()
        if (lastSessionId != null && sessionManager != null) {
            // Try to find and restore the last session
            val session = sessionManager!!.getAllSessions().find { it.id.toString() == lastSessionId }
            if (session != null) {
                sessionManager!!.switchToSession(session)
                welcomeShown = session.messages.isEmpty()
                // Force refresh to prevent the green background issue
                ApplicationManager.getApplication().invokeLater {
                    refreshChatDisplay()
                    chatDisplayPanel.forceRefresh()
                }
                return
            }
        }
        // If no valid last session, initialize with default
        initializeChatHistory()
    }

    private fun addSelectedFile(file: VirtualFile) {
        if (selectedFiles.add(file)) {
            inputPanel.addFileTab(file)
        }
    }

    private fun removeSelectedFile(file: VirtualFile) {
        if (selectedFiles.remove(file)) {
            inputPanel.removeFileTab(file)
        }
    }

    private fun resetToWelcome() {
        welcomeShown = true
        clearSelectedFiles()
    }

    private fun clearSelectedFiles() {
        val filesToRemove = selectedFiles.toList()
        selectedFiles.clear()
        filesToRemove.forEach { inputPanel.removeFileTab(it) }
    }

    private fun initializeChatHistory() {
        sessionManager?.currentSession?.messages?.clear()
        sessionManager?.let { manager ->
            manager.chatRepository.saveChat(manager.currentSession)
        }
        refreshChatDisplay()
    }

    /**
     * Processes user message submission and initiates AI response generation.
     *
     * Handles authentication checks, edit mode completion, message appending,
     * session management, and asynchronous AI response processing with loading indicators.
     */
    private fun sendMessage() {
        // Check authentication before allowing message sending
        if (!authState.isAuthenticated()) {
            return
        }

        val message = inputPanel.inputText.trim()
        if (message.isEmpty() || sessionManager == null) return

        val currentSessionId = sessionManager!!.currentSession.id

        if (isEditing) {
            val index = editIndex ?: return
            val messages = sessionManager!!.currentSession.messages
            if (editIndex != null && editIndex!! < messages.size) {
                val retained = messages.take(editIndex!!)
                messages.clear()
                messages.addAll(retained)
            }
            exitEditMode()
        }

        if (welcomeShown) {
            welcomeShown = false
            sessionManager!!.currentSession.messages.removeIf { (sender, _) -> sender.isEmpty() }
            sessionManager!!.chatRepository.saveChat(sessionManager!!.currentSession)
            refreshChatDisplay()
        }
        inputPanel.setGeneratingState(true)

        val selectedModel = inputPanel.getSelectedModel()
        appendMessage(USER_NAME, message)
        inputPanel.clearInput()
        chatDisplayPanel.scrollToBottomOnUserAction()

        // Save session after sending message
        saveCurrentSession()

        showLoadingMessage(currentSessionId, "Generating.")

        // Create a timer to update the loading indicator
        val loadingPatterns = arrayOf("Generating.", "Generating..", "Generating...")
        var patternIndex = 0

        loadingTimers[currentSessionId]?.stop() // Stop any previous timer for this session
        val timer =
            Timer(300) {
                val loadingText = loadingPatterns[patternIndex]
                // Only update if this is still the current session
                if (sessionManager?.currentSession?.id == currentSessionId) {
                    showLoadingMessage(currentSessionId, loadingText)
                }
                patternIndex = (patternIndex + 1) % loadingPatterns.size
            }
        loadingTimers[currentSessionId] = timer
        timer.start()

        // Process AI response in background
        val job =
            aiScope.launch {
                // A throw here used to escape the coroutine, leaving the indicator spinning and
                // the input box locked in "generating" state with no way to recover.
                val aiResponse =
                    try {
                        processAIResponse(message, selectedModel, currentSessionId)
                    } catch (e: Exception) {
                        LOG.warn("Failed to process chat response", e)
                        TitleResponsePair("", listOf("Error: ${e.message ?: "Failed to generate response"}"))
                    }

                if (!isActive) return@launch

                ApplicationManager.getApplication().invokeLater {
                    // Clean up this job
                    activeJobs.remove(currentSessionId)
                    loadingTimers[currentSessionId]?.stop()
                    loadingTimers.remove(currentSessionId)
                    loadingMessages.remove(currentSessionId)

                    // Only update input state if this is still the current session
                    if (sessionManager?.currentSession?.id == currentSessionId) {
                        inputPanel.setGeneratingState(false)
                    }

                    // Update the session (may not be current anymore)
                    val session = sessionManager?.getSessionById(currentSessionId)
                    if (session != null) {
                        if (aiResponse.title.isNotBlank() && session.title != aiResponse.title) {
                            session.title = aiResponse.title
                            // Update UI if this is current session
                            if (sessionManager?.currentSession?.id == currentSessionId) {
                                topBarPanel.updateTitle()
                            }
                        }

                        if (aiResponse.responses.isNotEmpty()) {
                            session.messages.add(AI_NAME to aiResponse.responses.first())

                            // Update display if this is the current session
                            if (sessionManager?.currentSession?.id == currentSessionId) {
                                refreshChatDisplay()
                            }

                            sessionManager?.chatRepository?.saveChat(session)
                        } else {
                            // Handle no response case
                            session.messages.add(AI_NAME to "No response received")

                            if (sessionManager?.currentSession?.id == currentSessionId) {
                                refreshChatDisplay()
                            }
                        }

                        // Always refresh history panel to show updated sessions
                        historyPanel.refresh()
                    }

                    saveCurrentSession()
                }
            }

        activeJobs[currentSessionId] = job
    }

    /**
     * Cancels generation for the current session only.
     */
    private fun cancelGeneration() {
        val currentSessionId = sessionManager?.currentSession?.id ?: return

        activeJobs[currentSessionId]?.cancel()
        activeJobs.remove(currentSessionId)
        loadingTimers[currentSessionId]?.stop()
        loadingTimers.remove(currentSessionId)
        loadingMessages.remove(currentSessionId)

        val cancelMsg = "This query request was cancelled by user."
        inputPanel.setGeneratingState(false)
        appendMessage(AI_NAME, cancelMsg)
        saveCurrentSession()
    }

    /**
     * Processes AI response using the IO manager with current context and settings.
     */
    private suspend fun processAIResponse(
        query: String,
        selectedModel: String?,
        sessionId: String,
    ) = ioManager.getAIResponse(
        useWeb,
        selectedFiles.map { it.path },
        selectedModel,
        sessionId,
        sessionManager?.getSessionById(sessionId)?.messages ?: emptyList(),
        project!!,
    )

    private fun appendMessage(
        sender: String,
        message: String,
    ) {
        sessionManager?.addMessageToCurrentSession(sender, message)
        refreshChatDisplay()
    }

    private fun refreshChatDisplay() {
        uiScope.launch {
            val messages = sessionManager?.currentSession?.messages ?: mutableListOf()
            ApplicationManager.getApplication().invokeLater {
                val sessionId = sessionManager?.currentSession?.id
                val loadingMessage = sessionId?.let { loadingMessages[it] }
                val displayMessages =
                    if (loadingMessage != null) {
                        messages + (AI_NAME to loadingMessage)
                    } else {
                        messages
                    }
                chatDisplayPanel.updateContent(displayMessages)
                historyPanel.refresh()
            }
        }
    }

    /**
     * Shows/updates the transient loading bubble for [sessionId] without touching its message
     * history. Only redraws the whole panel when the bubble first appears; subsequent animation
     * frames just retext the last bubble.
     */
    private fun showLoadingMessage(
        sessionId: String,
        message: String,
    ) {
        val session = sessionManager?.getSessionById(sessionId) ?: return
        val hadLoadingMessage = loadingMessages.containsKey(sessionId)
        loadingMessages[sessionId] = message
        if (sessionManager?.currentSession?.id != sessionId) return
        if (hadLoadingMessage) {
            chatDisplayPanel.updateLastBubbleText(message)
        } else {
            chatDisplayPanel.updateContent(session.messages + (AI_NAME to message))
            historyPanel.refresh()
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        // Save current session when panel is being disposed
        saveCurrentSession()
        // Cancel all active jobs
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        loadingTimers.values.forEach { it.stop() }
        loadingTimers.clear()
        loadingMessages.clear()
        // Clean up auth state listener
        authState.removePropertyChangeListener(TOKEN_PROPERTY, authStateListener)
    }

    fun updateModelList(models: Array<String>) {
        inputPanel.updateModelComboBox(models)
    }

    fun clearChat() {
        selectedFiles.clear()
        initializeChatHistory()
        resetToWelcome()
        saveCurrentSession()
    }

    private fun loadModelsFromConfig() {
        try {
            val models =
                getConfig()
                    .getModelsConfiguration()
                    ?.getAvailableChatModels()
                    ?.map { it.name }?.toTypedArray()
                    ?: emptyArray<String>()
            updateModelList(models)
        } catch (e: Exception) {
            println("Error loading models from config: ${e.message}")
            updateModelList(arrayOf("No models available"))
        }
    }

    fun reloadModels() {
        loadModelsFromConfig()
    }

    /**
     * Regenerates AI response from a specific message index.
     */
    private fun regenerateFromIndex(index: Int) {
        // Check authentication before allowing regeneration
        if (!authState.isAuthenticated()) {
            return
        }

        val messages = sessionManager?.currentSession?.messages ?: return
        if (index < 0 || index >= messages.size) return

        val currentSessionId = sessionManager!!.currentSession.id

        // Keep only messages up to the specified index (excluding the message at that index)
        val retainedMessages = messages.subList(0, index).toList()

        // Clear current messages and add retained ones
        sessionManager?.currentSession?.messages?.clear()
        sessionManager?.currentSession?.messages?.addAll(retainedMessages)

        saveCurrentSession()
        refreshChatDisplay()

        // Find last user message to regenerate from
        val lastUserMsg = retainedMessages.lastOrNull { it.first == USER_NAME }?.second ?: return
        val selectedModel = inputPanel.getSelectedModel()

        showLoadingMessage(currentSessionId, "Generating.")
        inputPanel.setGeneratingState(true)

        // Create a timer to update the loading indicator (same as sendMessage)
        val loadingPatterns = arrayOf("Generating.", "Generating..", "Generating...")
        var patternIndex = 0
        loadingTimers[currentSessionId]?.stop() // stop any previous
        val timer =
            Timer(300) {
                val loadingText = loadingPatterns[patternIndex]
                if (sessionManager?.currentSession?.id == currentSessionId) {
                    showLoadingMessage(currentSessionId, loadingText)
                }
                patternIndex = (patternIndex + 1) % loadingPatterns.size
            }
        loadingTimers[currentSessionId] = timer
        timer.start()

        // Launch AI coroutine
        val job =
            aiScope.launch {
                val aiResponse =
                    try {
                        processAIResponse(lastUserMsg, selectedModel, currentSessionId)
                    } catch (e: Exception) {
                        LOG.warn("Failed to regenerate chat response", e)
                        TitleResponsePair("", listOf("Error: ${e.message ?: "Failed to generate response"}"))
                    }

                if (!isActive) return@launch // Cancelled

                ApplicationManager.getApplication().invokeLater {
                    activeJobs.remove(currentSessionId)
                    loadingTimers[currentSessionId]?.stop()
                    loadingTimers.remove(currentSessionId)
                    loadingMessages.remove(currentSessionId)

                    if (sessionManager?.currentSession?.id == currentSessionId) {
                        inputPanel.setGeneratingState(false)
                    }

                    val session = sessionManager?.getSessionById(currentSessionId)
                    if (session != null) {
                        if (aiResponse.title.isNotBlank() && session.title != aiResponse.title) {
                            session.title = aiResponse.title
                            if (sessionManager?.currentSession?.id == currentSessionId) {
                                topBarPanel.updateTitle()
                            }
                        }

                        if (aiResponse.responses.isNotEmpty()) {
                            session.messages.add(AI_NAME to aiResponse.responses.first())

                            if (sessionManager?.currentSession?.id == currentSessionId) {
                                refreshChatDisplay()
                            }
                            sessionManager?.chatRepository?.saveChat(session)
                        } else {
                            session.messages.add(AI_NAME to "No response received")

                            if (sessionManager?.currentSession?.id == currentSessionId) {
                                refreshChatDisplay()
                            }
                        }

                        historyPanel.refresh()
                    }

                    saveCurrentSession()
                }
            }

        activeJobs[currentSessionId] = job
    }

    /**
     * Enters edit mode for modifying a previously sent user message.
     *
     * Sets up the input field with the original message text, shows edit controls,
     * and displays overlay to indicate edit mode is active.
     *
     * @param index The message index being edited
     * @param originalText The original message text to edit
     */
    private fun enterEditMode(
        index: Int,
        originalText: String,
    ) {
        editIndex = index
        isEditing = true
        inputPanel.setInputText(originalText)
        inputPanel.focusInputField()
        inputPanel.showCancelEditButton {
            exitEditMode()
        }
        chatDisplayPanel.showEditOverlay()
    }

    /**
     * Exits edit mode and returns to normal chat operation.
     */
    private fun exitEditMode() {
        editIndex = null
        isEditing = false
        inputPanel.clearInput()
        inputPanel.hideCancelEditButton()
        chatDisplayPanel.hideEditOverlay()
    }

    /**
     * Performs complete chat data cleanup after user logout for security.
     *
     * Immediately clears all UI content, deletes all sessions, resets state service,
     * creates new session manager, and forces multiple UI refreshes to ensure
     * no chat data remains visible after logout.
     */
    fun resetAllChatsAfterLogout(expectedGeneration: Long = authState.tokenGeneration()) {
        authState.runIfSignedOut(expectedGeneration) {
            project?.let { proj: Project ->
                LOG.info("Starting complete chat reset for project: ${proj.name}")

                // Cancel all active jobs and clear timers
                activeJobs.values.forEach { it.cancel() }
                activeJobs.clear()
                loadingTimers.values.forEach { it.stop() }
                loadingTimers.clear()

                // IMMEDIATELY clear UI content first
                chatDisplayPanel.updateContent(emptyList())
                historyPanel.refresh()
                topBarPanel.updateTitle()
                resetToWelcome()

                // Clear state service immediately
                stateService?.setLastSessionId(null)

                // Use the same logic as the "Delete All" button in HistoryPanel
                sessionManager?.let { manager ->
                    // Get all sessions and delete them one by one (this clears both memory and repository)
                    val allSessions = manager.getAllSessions()
                    LOG.info("Found ${allSessions.size} sessions to delete")

                    allSessions.forEach { session ->
                        manager.deleteSession(session, false) // Don't delete from server during logout
                    }
                }

                // Clear the repository completely
                val chatService = getProjectChatService(proj)
                chatService.clearAllChatsAndMemory()

                // Create a brand new session manager
                sessionManager = ChatSessionManager(chatService)
                topBarPanel.setSessionManager(sessionManager!!)
                historyPanel.setSessionManager(sessionManager!!)
                topBarPanel.updateTitle()
                historyPanel.refresh()
                refreshChatDisplay()

                // Force immediate UI clearing multiple times
                chatDisplayPanel.updateContent(emptyList())
                historyPanel.refresh()
                topBarPanel.updateTitle()

                // Force a complete UI refresh
                ApplicationManager.getApplication().invokeLater {
                    authState.runIfSignedOut(expectedGeneration) {
                        chatDisplayPanel.updateContent(emptyList())
                        chatDisplayPanel.forceRefresh()
                        historyPanel.refresh()
                        topBarPanel.updateTitle()

                        // Verify that we have an empty state
                        val sessions = sessionManager?.getAllSessions() ?: emptyList()
                        LOG.info("After reset, session count: ${sessions.size}")
                        if (sessions.isNotEmpty()) {
                            LOG.warn("WARNING: Sessions still exist after reset: ${sessions.map { "${it.id}: ${it.title}" }}")
                            // Force clear again if sessions still exist
                            sessions.forEach { session ->
                                sessionManager?.deleteSession(session, false)
                            }
                            chatDisplayPanel.updateContent(emptyList())
                            historyPanel.refresh()
                        } else {
                            LOG.info("SUCCESS: All sessions cleared")
                        }
                    }
                }

                // Update overlay visibility (this will also clear UI again)
                updateAuthOverlayVisibility()

                LOG.info("Completed chat reset for project: ${proj.name}")
            }
        }
    }

    /**
     * Called when user successfully logs in to update overlay visibility immediately
     */
    fun onUserAuthenticated() {
        ApplicationManager.getApplication().invokeLater {
            updateAuthOverlayVisibility()
        }
    }

    /**
     * Called when user logs out to update overlay visibility immediately
     */
    fun onUserLoggedOut() {
        val logoutGeneration = authState.tokenGeneration()
        ApplicationManager.getApplication().invokeLater {
            resetAllChatsAfterLogout(logoutGeneration)
            updateAuthOverlayVisibility()
        }
    }
}
