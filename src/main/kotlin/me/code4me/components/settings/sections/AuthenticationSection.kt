package me.code4me.components.settings.sections

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.code4me.api.wrapper.CookieAwareApiClient
import me.code4me.components.settings.fields.CredentialField
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.fields.TextField
import me.code4me.components.settings.fields.ToggleButtonField
import me.code4me.services.app.AppService
import me.code4me.services.state.AuthState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.net.CookieManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

/**
 * Settings section responsible for user authentication (login and signup).
 *
 * This section provides:
 * - Credential-based authentication (email/password)
 * - Google OAuth authentication (future feature)
 * - Dynamic form switching between login and signup modes
 * - Input validation and error handling
 * - Secure credential storage
 * - Reactive UI updates based on authentication state
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
    }

    /**
     * Authentication modes supported by this section.
     */
    private enum class AuthMode {
        /** User login with existing credentials */
        LOGIN,

        /** New user registration */
        SIGNUP,
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

    // ================= UI FIELDS =================

    /**
     * Email input field with state management.
     */
    private val emailField =
        JBTextField().apply {
            columns = FIELD_COLUMNS
            toolTipText = "Enter your email address"
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        val text = text.trim()
                        if (text.isNotEmpty() && !isValidEmail(text)) {
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
            toolTipText = "Enter your password"
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        val pwd = String(password)
                        if (pwd.isNotEmpty() && pwd.length < 8) {
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
            toolTipText = "Confirm your password"
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        val pwd = String(password)
                        val mainPwd = String(passwordField.password)
                        if (pwd.isNotEmpty() && pwd != mainPwd) {
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
        JToggleButton("Login Mode").apply {
            toolTipText = "Toggle between login and signup modes"
            isSelected = false // Default to LOGIN mode
        }

    private val authModeToggleSVF =
        object : ToggleButtonField(authModeToggle) {
            override fun getStateValue(): Boolean = authModeToggle.isSelected

            override fun setStateValue(value: Boolean) {
                authModeToggle.isSelected = value
                updateToggleText()
                requiresUIRefresh.set(true)
                updateFormVisibility()
            }

            init {
                // Add item listener to update UI immediately when toggle changes
                authModeToggle.addItemListener { _ ->
                    updateToggleText()
                    updateFormVisibility()
                    requiresUIRefresh.set(true)
                }
            }

            init {
                // Add item listener to update UI immediately when toggle changes
                authModeToggle.addItemListener { _ ->
                    updateToggleText()
                    updateFormVisibility()
                    requiresUIRefresh.set(true)
                }
            }
        }

    /**
     * Authentication action button (Login/Sign Up).
     */
    private val authButton =
        JButton("Login").apply {
            toolTipText = "Click to authenticate"
            addActionListener { performAuthentication() }
        }

    /**
     * Google OAuth authentication button (future feature).
     */
    private val googleAuthButton =
        JButton("Login with Google").apply {
            toolTipText = "Authenticate using your Google account"
            addActionListener { initiateGoogleAuth() }
            isEnabled = false // TODO: Enable when Google OAuth is implemented
        }

    // ================= SERVICES =================

    private val authState = service<AuthState>()
    private val appService = service<AppService>()

    // ================= UI COMPONENTS =================

    private val fullNameLabel = JLabel("Full name:")
    private val confirmPasswordLabel = JLabel("Confirm password:")

    private val credentialsTitleLabel =
        JBLabel("Credential-based Authentication").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 5, 0)
        }

    private val googleTitleLabel =
        JBLabel("Google-based Authentication").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 5, 0)
        }

    /**
     * Help text label shown below the toggle button to guide users.
     */
    private val modeHelpLabel =
        JBLabel("Don't have an account? Switch to Sign Up Mode").apply {
            font = font.deriveFont(java.awt.Font.ITALIC)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(2, 0, 8, 0)
        }

    init {
        updateFormVisibility()
        LOG.debug("AuthenticationSection initialized")
    }

    /**
     * Updates the toggle button text based on current mode.
     */
    private fun updateToggleText() {
        val isSignupMode = authModeToggle.isSelected
        authModeToggle.text = if (isSignupMode) "Sign Up Mode" else "Login Mode"

        // Set toggle button appearance based on mode
        authModeToggle.background =
            if (isSignupMode) {
                JBUI.CurrentTheme.Validator.errorBackgroundColor()
            } else {
                JBUI.CurrentTheme.Validator.warningBackgroundColor()
            }

        modeHelpLabel.text =
            if (isSignupMode) {
                "Already have an account? Switch to Login Mode"
            } else {
                "Don't have an account? Switch to Sign Up Mode"
            }

        authButton.text = if (isSignupMode) "Sign Up" else "Login"
        googleAuthButton.text = if (isSignupMode) "Sign Up with Google" else "Login with Google"

        LOG.debug("Auth mode toggled to: ${if (isSignupMode) "SIGNUP" else "LOGIN"}")
    }

    /**
     * Updates form field visibility based on current authentication mode.
     */
    private fun updateFormVisibility() {
        val isSignupMode = authModeToggle.isSelected

        fullNameField.isVisible = isSignupMode
        confirmPasswordField.isVisible = isSignupMode
        fullNameLabel.isVisible = isSignupMode
        confirmPasswordLabel.isVisible = isSignupMode

        LOG.debug("Form visibility updated: Signup mode = $isSignupMode")

        // just to ensure the UI refreshes correctly
        requiresUIRefresh.set(true)
        updateToggleText()
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
            formPanel.add(authModeToggle, gbc)

            // Mode helper text
            gbc.gridy = 1
            formPanel.add(modeHelpLabel, gbc)

            // Logical ordering of fields in sign-up mode:
            // 1. Email
            // 2. Full name (signup only)
            // 3. Password
            // 4. Confirm password (signup only)

            // Email field
            gbc.gridx = 0
            gbc.gridy = 2
            gbc.gridwidth = 1
            formPanel.add(JLabel("Email:"), gbc)
            gbc.gridx = 1
            formPanel.add(emailField, gbc)

            // Full name field (signup only)
            gbc.gridx = 0
            gbc.gridy = 3
            formPanel.add(fullNameLabel, gbc)
            gbc.gridx = 1
            formPanel.add(fullNameField, gbc)

            // Password field
            gbc.gridx = 0
            gbc.gridy = 4
            formPanel.add(JLabel("Password:"), gbc)
            gbc.gridx = 1
            formPanel.add(passwordField, gbc)

            // Confirm password field (signup only)
            gbc.gridx = 0
            gbc.gridy = 5
            formPanel.add(confirmPasswordLabel, gbc)
            gbc.gridx = 1
            formPanel.add(confirmPasswordField, gbc)

            // Auth button
            gbc.gridx = 0
            gbc.gridy = 6
            gbc.gridwidth = 2
            formPanel.add(authButton, gbc)

            add(formPanel, BorderLayout.CENTER)
        }
    }

    /**
     * Creates the Google OAuth authentication panel.
     */
    private fun createGooglePanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(""),
                    JBUI.Borders.empty(5),
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
            handleCredentialsAuth()
        } catch (e: Exception) {
            LOG.error("Authentication process failed", e)
            showError("Authentication failed due to an unexpected error")
        }
    }

    /**
     * Validates user input based on current authentication mode.
     */
    private fun validateInput(): Boolean {
        val email = emailField.text.trim()
        val password = String(passwordField.password)
        val isSignupMode = authModeToggle.isSelected

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
     */
    private fun handleCredentialsAuth() {
        val email = emailField.text.trim()
        val password = String(passwordField.password)
        val isSignupMode = authModeToggle.isSelected

        try {
            val token =
                if (isSignupMode) {
                    val fullName = fullNameField.text.trim()
                    performSignup(fullName, email, password)
                } else {
                    performLogin(email, password)
                }

            if (token != null) {
                // Store authentication data
                authState.state.setToken(token)
                authState.state.setUserEmail(email)

                if (isSignupMode) {
                    authState.state.setUserName(fullNameField.text.trim())
                }

                showSuccess("Authentication successful!")
                clearAllFields()
                appService.acquireSessionWithStoredToken()
            } else {
                val errorMessage =
                    if (isSignupMode) {
                        "Sign up failed. Please check your information and try again."
                    } else {
                        "Login failed. Please check your credentials and try again."
                    }
                showError(errorMessage)
            }
        } catch (e: Exception) {
            LOG.error("Authentication request failed", e)
            showError("Authentication failed: ${e.message}")
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
            val createUser = appService.createUser(
                email = email,
                name = fullName,
                password = password,
            )
            val authenticatedUser = appService.authenticateUser(email, password)
            authState.state.setUserName(
                authenticatedUser.user.name.trim()
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

    /**
     * Handles Google login for existing users.
     * TODO: Implement when Google OAuth is available.
     */
    fun handleGoogleLogin(
        email: String,
        token: String,
    ) {
        LOG.info("Google login requested for email: $email (not yet implemented)")
        showError("Google authentication is not yet implemented")
    }

    /**
     * Handles Google signup for new users.
     * TODO: Implement when Google OAuth is available.
     */
    fun handleGoogleSignup(
        email: String,
        token: String,
    ) {
        LOG.info("Google signup requested for email: $email (not yet implemented)")
        showError("Google authentication is not yet implemented")
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
