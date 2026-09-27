package me.code4me.components.settings.sections

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.components.settings.fields.CredentialField
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.fields.TextField
import me.code4me.components.settings.fields.ToggleButtonField
import me.code4me.services.app.AppService
import me.code4me.services.config.models.ServerConfig
import me.code4me.services.state.AuthState
import me.code4me.services.state.getPrefState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

private fun JComponent.addEnterKeyListener(action: () -> Unit) {
    addKeyListener(
        object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    action()
                }
            }
        },
    )
}

/**
 * Settings section responsible for user authentication (login and signup).
 *
 * This section provides:
 * - Credential-based authentication (email/password)
 * - Google OAuth authentication (future feature)
 * - Dynamic form switching between login and signup modes
 * - Password reset functionality
 * - Input validation and error handling
 * - Secure credential storage
 * - Reactive UI updates based on authentication state
 * - Integration with ChatPanel overlay updates
 *
 * The section automatically integrates with the authentication state service
 * and triggers UI refreshes when authentication status changes.
 *
 * @since 1.0.0
 */
class AuthenticationSection : SettingsSection {
    companion object {
        private val LOG = thisLogger()

        // UI Constants
        private const val FIELD_COLUMNS = 20
        private const val FORM_PADDING = 10
        private const val SECTION_SPACING = 15
        private const val MAX_FIELD_WIDTH = 250 // Max width for text fields
    }

    /**
     * Authentication modes supported by this section.
     */
    private enum class AuthMode {
        /** User login with existing credentials */
        LOGIN,

        /** New user registration */
        SIGNUP,

        /** Password reset mode */
        FORGOT_PASSWORD,
    }

    /**
     * Authentication methods available to users.
     */
    private enum class AuthMethod {
        /** Email and password authentication */
        CREDENTIALS,

        /** Google OAuth authentication (future) */
        GOOGLE,
    }

    /**
     * Flag indicating whether the UI needs to be refreshed due to authentication state changes.
     */
    val requiresUIRefresh = AtomicBoolean(false)

    /**
     * Current authentication mode
     */
    private var currentAuthMode = AuthMode.LOGIN

    // ================= UI FIELDS =================

    /**
     * Email input field with state management.
     */
    private val emailField =
        JBTextField().apply {
            columns = FIELD_COLUMNS
            toolTipText = "Enter your email address"
            preferredSize = Dimension(MAX_FIELD_WIDTH, preferredSize.height)
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        SwingUtilities.invokeLater {
                            val text = text.trim()
                            if (text.isNotEmpty() && !isValidEmail(text)) {
                                background = JBUI.CurrentTheme.Validator.errorBackgroundColor()
                                putClientProperty("JComponent.outline", "error")
                            } else {
                                background = null
                                putClientProperty("JComponent.outline", null)
                            }
                        }
                    }
                },
            )
        }

    private val emailFieldSVF =
        object : TextField(emailField) {
            override fun getStateValue(): String? = emailField.text.takeIf { it.isNotBlank() }

            override fun setStateValue(value: String) {
                emailField.text = value
            }
        }

    /**
     * Password input field with state management.
     */
    private val passwordField =
        JBPasswordField().apply {
            columns = FIELD_COLUMNS
            toolTipText = "Enter your password (8+ chars, uppercase, lowercase, digit, no spaces)"
            preferredSize = Dimension(MAX_FIELD_WIDTH, preferredSize.height)
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        val pwd = String(password)
                        val isSignupMode = authModeToggle.isSelected

                        if (pwd.isNotEmpty()) {
                            val isValid =
                                if (isSignupMode) {
                                    pwd.length >= 8 && pwd.matches(Regex("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)\\S{8,}$"))
                                } else {
                                    pwd.length >= 8
                                }

                            if (!isValid) {
                                background = JBUI.CurrentTheme.Validator.errorBackgroundColor()
                                putClientProperty("JComponent.outline", "error")
                            } else {
                                background = null
                                putClientProperty("JComponent.outline", null)
                            }
                        } else {
                            background = null
                            putClientProperty("JComponent.outline", null)
                        }
                    }
                },
            )
        }

    private val passwordFieldSVF =
        object : CredentialField(passwordField) {
            override fun getStateValue(): String? = String(passwordField.password).takeIf { it.isNotBlank() }

            override fun setStateValue(value: String) {
                passwordField.text = value
            }
        }

    /**
     * Full name input field (signup only) with state management.
     */
    private val fullNameField =
        JBTextField().apply {
            columns = FIELD_COLUMNS
            toolTipText = "Enter your full name"
            preferredSize = Dimension(MAX_FIELD_WIDTH, preferredSize.height)
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        val text = text.trim()
                        if (text.isNotEmpty() && text.length < 3) {
                            background = JBUI.CurrentTheme.Validator.errorBackgroundColor()
                            putClientProperty("JComponent.outline", "error")
                        } else {
                            background = null
                            putClientProperty("JComponent.outline", null)
                        }
                    }
                },
            )
        }

    private val fullNameFieldSVF =
        object : TextField(fullNameField) {
            override fun getStateValue(): String? = fullNameField.text.takeIf { it.isNotBlank() }

            override fun setStateValue(value: String) {
                fullNameField.text = value
            }
        }

    /**
     * Password confirmation field (signup only) with state management.
     */
    private val confirmPasswordField =
        JBPasswordField().apply {
            columns = FIELD_COLUMNS
            toolTipText = "Confirm your password (must match requirements)"
            preferredSize = Dimension(MAX_FIELD_WIDTH, preferredSize.height)
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        val pwd = String(password)
                        val mainPwd = String(passwordField.password)
                        if (pwd.isNotEmpty()) {
                            val passwordsMatch = pwd == mainPwd
                            val meetsRequirements = pwd.matches(Regex("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)\\S{8,}$"))

                            if (!passwordsMatch || !meetsRequirements) {
                                background = JBUI.CurrentTheme.Validator.errorBackgroundColor()
                                putClientProperty("JComponent.outline", "error")
                            } else {
                                background = null
                                putClientProperty("JComponent.outline", null)
                            }
                        } else {
                            background = null
                            putClientProperty("JComponent.outline", null)
                        }
                    }
                },
            )
        }

    private val confirmPasswordFieldSVF =
        object : CredentialField(confirmPasswordField) {
            override fun getStateValue(): String? = String(confirmPasswordField.password).takeIf { it.isNotBlank() }

            override fun setStateValue(value: String) {
                confirmPasswordField.text = value
            }
        }

    /**
     * Mode toggle button for switching between login and signup.
     */
    private val authModeToggle =
        JToggleButton("Sign Up Mode").apply {
            toolTipText = "Toggle between login and signup modes"
            isSelected = false // Default to LOGIN mode
            putClientProperty("JButton.buttonType", "link")
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            horizontalAlignment = SwingConstants.CENTER
        }

    private val authModeToggleSVF =
        object : ToggleButtonField(authModeToggle) {
            override fun getStateValue(): Boolean = authModeToggle.isSelected

            override fun setStateValue(value: Boolean) {
                authModeToggle.isSelected = value
                updateToggleText()
//                requiresUIRefresh.set(true)
                updateFormVisibility()
            }

            init {
                // Add item listener to update UI immediately when toggle changes
                authModeToggle.addItemListener { _ ->
                    updateToggleText()
                    updateFormVisibility()
//                    requiresUIRefresh.set(true)
                }
            }
        }

    /**
     * Authentication action button (Login/Sign Up/Reset Password).
     */
    private val authButton =
        JButton("Login").apply {
            toolTipText = "Click to authenticate"
            addActionListener { performAuthentication() }
            putClientProperty("JButton.preferredWidth", MAX_FIELD_WIDTH) // Make button width consistent
        }

    /**
     * Forgot password link button.
     */
    private val forgotPasswordButton =
        JButton("Forgot Password?").apply {
            toolTipText = "Reset your password via email"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            addActionListener { switchToForgotPasswordMode() }
            horizontalAlignment = SwingConstants.CENTER
        }

    /**
     * Back to login button (shown in forgot password mode).
     */
    private val backToLoginButton =
        JButton("Back to Login").apply {
            toolTipText = "Return to login form"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            addActionListener { switchToLoginMode() }
            horizontalAlignment = SwingConstants.CENTER
        }

    /**
     * Google OAuth authentication button (future feature).
     */
    private val googleAuthButton =
        JButton("Login with Google").apply {
            toolTipText = "Authenticate using your Google account"
            addActionListener { initiateGoogleAuth() }
            isEnabled = false // TODO: Enable when Google OAuth is implemented
            putClientProperty("JButton.preferredWidth", MAX_FIELD_WIDTH)
        }

    /**
     * Advanced button to configure server selection (host/port/context-path).
     */
    private val advancedServerButton =
        JButton("Advanced Server Options").apply {
            toolTipText = "Configure server connection (host, port, context path)"
            addActionListener { showServerSelectionDialog() }
        }

    // ================= SERVICES =================

    private val authState = service<AuthState>().state
    private val appService = service<AppService>()

    // ================= UI COMPONENTS =================

    private val fullNameLabel = JLabel("Full name:")
    private val confirmPasswordLabel = JLabel("Confirm password:")
    private val passwordLabel = JLabel("Password:")

    private val credentialsTitleLabel =
        JBLabel("Credential-based Authentication").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 5, 0)
            horizontalAlignment = SwingConstants.CENTER
        }

    private val googleTitleLabel =
        JBLabel("Google-based Authentication").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 5, 0)
            horizontalAlignment = SwingConstants.CENTER
        }

    init {
        updateFormVisibility()

        // Add Enter key listeners to input fields
        emailField.addEnterKeyListener { performAuthentication() }
        passwordField.addEnterKeyListener { performAuthentication() }
        fullNameField.addEnterKeyListener { performAuthentication() }
        confirmPasswordField.addEnterKeyListener { performAuthentication() }

        LOG.debug("AuthenticationSection initialized")
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
     * Updates the toggle button text based on current mode.
     */
    private fun updateToggleText() {
        val isSignupMode = authModeToggle.isSelected
        authModeToggle.text = if (isSignupMode) "Already have an account? Login" else "Don't have an account? Sign Up"

        authButton.text = if (isSignupMode) "Sign Up" else "Login"
        googleAuthButton.text = if (isSignupMode) "Sign Up with Google" else "Login with Google"

        LOG.debug("Auth mode toggled to: ${if (isSignupMode) "SIGNUP" else "LOGIN"}")
    }

    /**
     * Updates form field visibility based on current authentication mode.
     */
    private fun updateFormVisibility() {
        when (currentAuthMode) {
            AuthMode.LOGIN -> {
                val isSignupMode = authModeToggle.isSelected

                // Show/hide toggle and related elements
                authModeToggle.isVisible = true

                // Show/hide fields based on signup mode
                fullNameField.isVisible = isSignupMode
                confirmPasswordField.isVisible = isSignupMode
                fullNameLabel.isVisible = isSignupMode
                confirmPasswordLabel.isVisible = isSignupMode

                // Show password field and forgot password link
                passwordField.isVisible = true
                passwordLabel.isVisible = true
                forgotPasswordButton.isVisible = !isSignupMode
                backToLoginButton.isVisible = false

                // Update button text
                authButton.text = if (isSignupMode) "Sign Up" else "Login"
            }

            AuthMode.SIGNUP -> {
                // Same as LOGIN mode when toggle is selected
                passwordLabel.isVisible = true
                updateFormVisibility()
            }

            AuthMode.FORGOT_PASSWORD -> {
                // Hide toggle and signup-specific elements
                authModeToggle.isVisible = false
                fullNameField.isVisible = false
                confirmPasswordField.isVisible = false
                fullNameLabel.isVisible = false
                confirmPasswordLabel.isVisible = false

                // Hide password field, label and forgot password link
                passwordField.isVisible = false
                passwordLabel.isVisible = false

                forgotPasswordButton.isVisible = false
                backToLoginButton.isVisible = true

                // Update button text
                authButton.text = "Send Reset Email"
            }
        }

        LOG.debug("Form visibility updated: Current mode = $currentAuthMode")

        // just to ensure the UI refreshes correctly
//        requiresUIRefresh.set(true)
        if (currentAuthMode != AuthMode.FORGOT_PASSWORD) {
            updateToggleText()
        }
    }

    /**
     * Switches to forgot password mode.
     */
    private fun switchToForgotPasswordMode() {
        currentAuthMode = AuthMode.FORGOT_PASSWORD
        updateFormVisibility()
        LOG.debug("Switched to forgot password mode")
    }

    /**
     * Switches back to login mode.
     */
    private fun switchToLoginMode() {
        currentAuthMode = AuthMode.LOGIN
        authModeToggle.isSelected = false // Reset to login mode
        updateFormVisibility()
        LOG.debug("Switched back to login mode")
    }

    override fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>,
    ) {
        // Register state value fields
        stateValueFields.addAll(
            listOf(
                emailFieldSVF,
                passwordFieldSVF,
                fullNameFieldSVF,
                confirmPasswordFieldSVF,
                authModeToggleSVF,
            ),
        )

        // Create main authentication panel
        val mainPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(FORM_PADDING)
            }

        // Credentials section
        val credentialsPanel = createCredentialsPanel()

        // Google authentication section
        val googlePanel = createGooglePanel()

        // Combine sections
        val sectionsPanel =
            JPanel(BorderLayout()).apply {
                add(credentialsPanel, BorderLayout.NORTH)
                add(googlePanel, BorderLayout.SOUTH)
            }

        mainPanel.add(sectionsPanel, BorderLayout.NORTH)
        builder.addComponent(mainPanel)

        LOG.debug("Authentication section applied to form builder")
    }

    /**
     * Creates the credentials-based authentication panel.
     */
    private fun createCredentialsPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, SECTION_SPACING, 0)

            add(credentialsTitleLabel, BorderLayout.NORTH)

            val formPanel =
                JPanel(GridBagLayout()).apply {
                    border = JBUI.Borders.empty(5, 0, 0, 0)
                }

            val gbc =
                GridBagConstraints().apply {
                    insets = Insets(5, 5, 5, 5)
                    anchor = GridBagConstraints.WEST
                }

            // Mode toggle at the top
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.CENTER
            formPanel.add(authModeToggle, gbc)

            val labelGbc =
                GridBagConstraints().apply {
                    gridx = 0
                    insets = Insets(5, 5, 5, 5)
                    anchor = GridBagConstraints.WEST
                }

            val fieldGbc =
                GridBagConstraints().apply {
                    gridx = 1
                    insets = Insets(5, 5, 5, 5)
                    anchor = GridBagConstraints.WEST
                    weightx = 1.0 // Allow this column to grow horizontally
                    fill = GridBagConstraints.HORIZONTAL // Make components fill the column's width
                }

            // Row 1: Email field
            labelGbc.gridy = 1
            formPanel.add(JLabel("Email:"), labelGbc)
            fieldGbc.gridy = 1
            formPanel.add(emailField, fieldGbc)

            // Row 2: Full name field (for signup)
            labelGbc.gridy = 2
            formPanel.add(fullNameLabel, labelGbc)
            fieldGbc.gridy = 2
            formPanel.add(fullNameField, fieldGbc)

            // Row 3: Password field
            labelGbc.gridy = 3
            formPanel.add(passwordLabel, labelGbc)
            fieldGbc.gridy = 3
            formPanel.add(passwordField, fieldGbc)

            // Row 4: Confirm password field (for signup)
            labelGbc.gridy = 4
            formPanel.add(confirmPasswordLabel, labelGbc)
            fieldGbc.gridy = 4
            formPanel.add(confirmPasswordField, fieldGbc)

            // Row 5: Forgot password / Back to login buttons
            val rightAlignedGbc =
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = 5
                    insets = Insets(5, 5, 5, 5)
                    anchor = GridBagConstraints.EAST // Align to the right
                }
            formPanel.add(forgotPasswordButton, rightAlignedGbc)
            formPanel.add(backToLoginButton, rightAlignedGbc)

            // Row 6: Auth button - centered below fields
            val centerGbc =
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 6
                    gridwidth = 2 // Span both columns
                    insets = Insets(10, 5, 5, 5) // More top margin
                    anchor = GridBagConstraints.CENTER
                }
            formPanel.add(authButton, centerGbc)

            // Row 7: Advanced server options button - centered below auth button
            val advancedGbc =
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = 7
                    gridwidth = 2
                    insets = Insets(5, 5, 10, 5)
                    anchor = GridBagConstraints.CENTER
                }
            formPanel.add(advancedServerButton, advancedGbc)

            add(JPanel().apply { add(formPanel) }, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the Google OAuth authentication panel.
     */
    private fun createGooglePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(
                        JBUI.scale(1),
                        0,
                        0,
                        0,
                        JBUI.CurrentTheme.Popup.separatorColor(), // theme-aware
                    ),
                    JBUI.Borders.empty(SECTION_SPACING, 0, 0, 0),
                )

            add(googleTitleLabel, BorderLayout.NORTH)

            val buttonPanel =
                JPanel().apply {
                    add(googleAuthButton)
                }

            add(buttonPanel, BorderLayout.CENTER)
        }
    }

    /**
     * Performs authentication based on current mode and input validation.
     */
    private fun performAuthentication() {
        if (!validateInput()) {
            return
        }

        // Clear any previous error states
        clearFieldErrors()

        try {
            when (currentAuthMode) {
                AuthMode.FORGOT_PASSWORD -> handleForgotPassword()
                else -> handleCredentialsAuth()
            }
        } catch (e: Exception) {
            LOG.error("Authentication process failed", e)
            showError("Authentication failed due to an unexpected error")
        }
    }

    /**
     * Handles forgot password request.
     *
     * The actual request runs on a pooled thread: it's a blocking network call, and IntelliJ's
     * threading rules forbid slow I/O on the EDT (this button click starts on the EDT). Only the
     * UI follow-up hops back via `invokeLater`.
     */
    private fun handleForgotPassword() {
        val email = emailField.text.trim()

        authButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val success = appService.requestPasswordReset(email)

                ApplicationManager.getApplication().invokeLater {
                    authButton.isEnabled = true
                    if (!success) {
                        showError("Failed to send password reset email. Please check your email address and try again.")
                    } else {
                        Messages.showInfoMessage(
                            "A password reset email has been sent to $email. " +
                                "Please check your inbox and follow the instructions to reset your password.",
                            "Password Reset Email Sent",
                        )
                        switchToLoginMode()
                        LOG.info("Password reset email requested for: $email")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to send password reset email", e)
                ApplicationManager.getApplication().invokeLater {
                    authButton.isEnabled = true
                    showError("Failed to send password reset email. Please try again later.")
                }
            }
        }
    }

    /**
     * Validates user input based on current authentication mode.
     */
    private fun validateInput(): Boolean {
        val email = emailField.text.trim()

        when {
            email.isBlank() -> {
                showError("Email address is required")
                emailField.requestFocus()
                return false
            }
            !isValidEmail(email) -> {
                showError("Please enter a valid email address")
                emailField.requestFocus()
                return false
            }
        }

        // For forgot password mode, only email validation is needed
        if (currentAuthMode == AuthMode.FORGOT_PASSWORD) {
            return true
        }

        // For login/signup modes, validate password and other fields
        val password = String(passwordField.password)
        val isSignupMode = authModeToggle.isSelected

        when {
            password.isBlank() -> {
                showError("Password is required")
                passwordField.requestFocus()
                return false
            }
            password.length < 8 -> {
                showError("Password must be at least 8 characters long")
                passwordField.requestFocus()
                return false
            }
            // Enhanced password validation for signup mode
            isSignupMode && !password.matches(Regex("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)\\S{8,}$")) -> {
                showError(
                    "Password must contain at least one uppercase letter, one lowercase letter, one digit," +
                        " and be at least 8 characters long with no spaces",
                )
                passwordField.requestFocus()
                return false
            }
        }

        if (isSignupMode) {
            val fullName = fullNameField.text.trim()
            val confirmPassword = String(confirmPasswordField.password)

            when {
                fullName.isBlank() -> {
                    showError("Full name is required for signup")
                    fullNameField.requestFocus()
                    return false
                }
                confirmPassword != password -> {
                    showError("Passwords do not match")
                    confirmPasswordField.requestFocus()
                    return false
                }
                // Also validate confirm password with same rules
                !confirmPassword.matches(Regex("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)\\S{8,}$")) -> {
                    showError("Confirm password must match the password requirements")
                    confirmPasswordField.requestFocus()
                    return false
                }
            }
        }

        return true
    }

    /**
     * Validates email format using a simple regex.
     */
    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
    }

    /**
     * Clears any error styling from input fields.
     */
    private fun clearFieldErrors() {
        // Reset field backgrounds and outlines to default
        emailField.background = null
        emailField.putClientProperty("JComponent.outline", null)

        passwordField.background = null
        passwordField.putClientProperty("JComponent.outline", null)

        fullNameField.background = null
        fullNameField.putClientProperty("JComponent.outline", null)

        confirmPasswordField.background = null
        confirmPasswordField.putClientProperty("JComponent.outline", null)
    }

    /**
     * Handles credential-based authentication.
     *
     * The button click (and, for signup, the confirmation dialog) run on the EDT, but the actual
     * login/signup call, the credential-store writes it triggers (`AuthState.setToken`/etc. write
     * through to the OS keychain), and the follow-up session acquisition are all blocking I/O —
     * IntelliJ's threading rules forbid that on the EDT. That work is dispatched to a pooled
     * thread; only the final UI update hops back via `invokeLater`.
     */
    private fun handleCredentialsAuth() {
        val email = emailField.text.trim()
        val password = String(passwordField.password)
        val isSignupMode = authModeToggle.isSelected
        val fullName = fullNameField.text.trim()

        if (isSignupMode) {
            val confirmResult =
                Messages.showYesNoDialog(
                    """
                    By signing up, you agree to the following:

                    • Your email and name will be stored securely
                    • Your coding activity will be processed to provide suggestions
                    • You can delete your account and data at any time
                    • We will never share your personal information with third parties

                    For more details, please refer to our Privacy Policy here: https://code4me.me/privacy-policy

                    Do you want to continue with registration?
                    """.trimIndent(),
                    "Confirm Registration",
                    "Continue",
                    "Cancel",
                    Messages.getQuestionIcon(),
                )
            if (confirmResult != Messages.YES) return
        }

        authButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            runCredentialsAuthRequest(isSignupMode, email, password, fullName)
        }
    }

    /**
     * The blocking half of [handleCredentialsAuth], run off the EDT. See that method's doc for why.
     */
    private fun runCredentialsAuthRequest(
        isSignupMode: Boolean,
        email: String,
        password: String,
        fullName: String,
    ) {
        try {
            val token = if (isSignupMode) performSignup(fullName, email, password) else performLogin(email, password)

            if (token != null) {
                // Store authentication data
                authState.setToken(token)
                authState.setUserEmail(email)

                if (isSignupMode) {
                    authState.setUserName(fullName)
                }

                appService.acquireSessionWithStoredToken()

                ApplicationManager.getApplication().invokeLater {
                    authButton.isEnabled = true
                    showSuccess("Authentication successful!")
                    clearAllFields()
                    // Update chat panel overlays immediately after successful auth
                    updateChatPanelOverlays()
                }
            } else {
                val errorMessage =
                    if (isSignupMode) {
                        "Sign up failed. Please check your information and try again."
                    } else {
                        "Login failed. Please check your credentials and try again."
                    }
                ApplicationManager.getApplication().invokeLater {
                    authButton.isEnabled = true
                    showError(errorMessage)
                }
            }
        } catch (e: Exception) {
            LOG.error("Authentication request failed", e)
            ApplicationManager.getApplication().invokeLater {
                authButton.isEnabled = true
                showError("Authentication failed: ${e.message}")
            }
        }
    }

    /**
     * Performs user login with email and password.
     */
    private fun performLogin(
        email: String,
        password: String,
    ): String? {
        return try {
            val response = appService.authenticateUser(email, password)
            authState.setUserName(response.user.name.trim())
            authState.setUserEmail(email)
            authState.setVerified(response.user.verified)
            return CookieAwareApiClient.cookieManager.cookieStore.cookies.firstOrNull {
                it.name == "auth_token"
            }?.value
        } catch (e: Exception) {
            LOG.warn("Login request failed for email: $email", e)
            null
        }
    }

    /**
     * Performs user signup with full name, email, and password.
     */
    private fun performSignup(
        fullName: String,
        email: String,
        password: String,
    ): String? {
        return try {
            val createUser =
                appService.createUser(
                    email = email,
                    name = fullName,
                    password = password,
                )
            val authenticatedUser = appService.authenticateUser(email, password)
            authState.setUserName(
                authenticatedUser.user.name.trim(),
            )
            return CookieAwareApiClient.cookieManager.cookieStore.cookies.firstOrNull {
                it.name == "auth_token"
            }?.value
        } catch (e: Exception) {
            LOG.warn("Signup request failed for email: $email", e)
            null
        }
    }

    /**
     * Shows the Advanced Server Selection dialog and applies changes.
     */
    private fun showServerSelectionDialog() {
        val prefs = getPrefState()
        val dialog =
            ServerSelectionDialog(
                initialHost = prefs.lastServerHost ?: "",
                initialPort = prefs.lastServerPort,
                initialContextPath = prefs.lastServerContextPath ?: "",
            )
        if (dialog.showAndGet()) {
            val host = dialog.getHost().trim()
            val port = dialog.getPort()
            val contextPath = dialog.getContextPath().trim()

            // Persist selection
            prefs.lastServerHost = host
            prefs.lastServerPort = port
            prefs.lastServerContextPath = contextPath

            // Apply to AppService immediately
            val timeout = 30
            service<AppService>().setServerConfig(
                ServerConfig(
                    host = host,
                    port = port,
                    contextPath = contextPath,
                    timeout = timeout,
                ),
            )

            val portSegment = if (port > 0) ":$port" else ""
            Messages.showInfoMessage(
                "Server switched to $host$portSegment$contextPath",
                "Server Updated",
            )
        }
    }

    /**
     * Initiates Google OAuth authentication flow.
     */
    private fun initiateGoogleAuth() {
        // TODO: Implement Google OAuth flow
        showError("Google authentication is not yet implemented")
        LOG.info("Google authentication requested (not yet implemented)")
    }

    /**
     * Shows an error message to the user.
     */
    private fun showError(message: String) {
        Messages.showErrorDialog(message, "Authentication Error")
        LOG.debug("Showing error message: $message")
    }

    /**
     * Shows a success message to the user.
     */
    private fun showSuccess(message: String) {
        Messages.showInfoMessage(message, "Authentication Success")
        LOG.debug("Showing success message: $message")
    }

    /**
     * Clears all input fields.
     */
    private fun clearAllFields() {
        emailField.text = ""
        passwordField.text = ""
        fullNameField.text = ""
        confirmPasswordField.text = ""
        LOG.debug("All authentication fields cleared")
    }
}

/**
 * Dialog for collecting additional information during Google signup.
 * TODO: Complete implementation when Google OAuth is available.
 */
private class PasswordCreationDialog(private val email: String) : DialogWrapper(true) {
    private val nameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val confirmPasswordField = JBPasswordField()

    init {
        title = "Complete Your Registration"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc =
            GridBagConstraints().apply {
                insets = Insets(5, 5, 5, 5)
                anchor = GridBagConstraints.WEST
            }

        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel("Email:"), gbc)
        gbc.gridx = 1
        panel.add(JLabel(email), gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        panel.add(JLabel("Full Name:"), gbc)
        gbc.gridx = 1
        panel.add(nameField, gbc)

        gbc.gridx = 0
        gbc.gridy = 2
        panel.add(JLabel("Password:"), gbc)
        gbc.gridx = 1
        panel.add(passwordField, gbc)

        gbc.gridx = 0
        gbc.gridy = 3
        panel.add(JLabel("Confirm Password:"), gbc)
        gbc.gridx = 1
        panel.add(confirmPasswordField, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        return when {
            nameField.text.isBlank() -> ValidationInfo("Name is required", nameField)
            String(passwordField.password).length < 8 ->
                ValidationInfo("Password must be at least 8 characters", passwordField)
            String(passwordField.password) != String(confirmPasswordField.password) ->
                ValidationInfo("Passwords do not match", confirmPasswordField)
            else -> null
        }
    }

    fun getPassword(): String = String(passwordField.password)

    fun getName(): String = nameField.text.trim()
}

private class ServerSelectionDialog(
    private val initialHost: String,
    private val initialPort: Int,
    private val initialContextPath: String,
) : DialogWrapper(true) {
    private val hostField = JBTextField()
    private val portField = JBTextField()
    private val contextPathField = JBTextField()

    init {
        title = "Advanced Server Options"
        hostField.text = initialHost
        portField.text = if (initialPort > 0) initialPort.toString() else ""
        contextPathField.text = initialContextPath
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc =
            GridBagConstraints().apply {
                insets = Insets(5, 5, 5, 5)
                anchor = GridBagConstraints.WEST
            }

        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel("Host (with scheme):"), gbc)
        gbc.gridx = 1
        panel.add(hostField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        panel.add(JLabel("Port (optional):"), gbc)
        gbc.gridx = 1
        panel.add(portField, gbc)

        gbc.gridx = 0
        gbc.gridy = 2
        panel.add(JLabel("Extra path prefix (optional):"), gbc)
        gbc.gridx = 1
        contextPathField.toolTipText =
            "Only needed if the backend is mounted under an extra path, e.g. a reverse proxy " +
            "serving it at /my-proxy rather than at the host root. Every API route already " +
            "starts with /api on its own, so leave this blank for a plain host such as " +
            "http://localhost:8008 — entering /api here would double it to /api/api/...."
        panel.add(contextPathField, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val host = hostField.text.trim()
        if (host.isBlank()) return ValidationInfo("Host is required", hostField)
        val portText = portField.text.trim()
        if (portText.isNotBlank()) {
            val port = portText.toIntOrNull()
            if (port == null || port <= 0 || port > 65535) return ValidationInfo("Port must be a number between 1 and 65535", portField)
        }
        return null
    }

    fun getHost(): String = hostField.text.trim()

    fun getPort(): Int = portField.text.trim().toIntOrNull() ?: 0

    fun getContextPath(): String = contextPathField.text.trim()
}
