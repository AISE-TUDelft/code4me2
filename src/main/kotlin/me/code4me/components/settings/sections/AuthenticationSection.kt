package me.code4me.components.settings.sections

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.FormBuilder
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.infrastructure.ServerException
import me.code4me.api.generated.model.Provider
import me.code4me.components.settings.fields.CredentialField
import me.code4me.components.settings.fields.FieldInfo
import me.code4me.components.settings.fields.StateValueField
import me.code4me.components.settings.fields.TextField
import me.code4me.components.settings.fields.ToggleButtonField
import me.code4me.services.app.AppService
import me.code4me.services.config.getConfig
import me.code4me.services.state.AuthState
import me.code4me.utils.GoogleAuthUtils
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.border.EmptyBorder

class AuthenticationSection : SettingsSection {
    private enum class AuthMode { LOGIN, SIGNUP }

    private enum class AuthMethod { CREDENTIALS, GOOGLE }

    val requiresUIRefresh = AtomicBoolean(false)

    // Fields using the provided base classes
    private val emailField = JBTextField()
    private val emailFieldSVF =
        object : TextField(emailField) {
            override fun getStateValue(): String? {
                return emailField.text
            }

            override fun setStateValue(value: String) {
                emailField.text = value
            }
        }

    private val passwordField = JBPasswordField()
    private val passwordFieldSVF =
        object : CredentialField(passwordField) {
            override fun getStateValue(): String? {
                return String(passwordField.password)
            }

            override fun setStateValue(value: String) {
                passwordField.text = value
            }
        }

    private val fullNameField = JBTextField()
    private val fullNameFieldSVF =
        object : TextField(fullNameField) {
            override fun getStateValue(): String? {
                return fullNameField.text
            }

            override fun setStateValue(value: String) {
                fullNameField.text = value
            }
        }

    private val confirmPasswordField = JBPasswordField()
    private val confirmPasswordFieldSVF =
        object : CredentialField(confirmPasswordField) {
            override fun getStateValue(): String? {
                return String(confirmPasswordField.password)
            }

            override fun setStateValue(value: String) {
                confirmPasswordField.text = value
            }
        }

    private val authModeToggle = JToggleButton("Already have an account? Switch to Login Mode")
    private val authModeToggleSVF =
        object : ToggleButtonField(authModeToggle) {
            override fun getStateValue(): Boolean {
                return authModeToggle.isSelected
            }

            override fun setStateValue(value: Boolean) {
                authModeToggle.isSelected = value
                updateToggleText()
                println("authModeToggle.text updated to: ${authModeToggle.text}")
                requiresUIRefresh.set(true)
                updateFormVisibility()
            }
        }

    private fun updateToggleText() {
        val isLoginMode = authModeToggle.isSelected
        authModeToggle.text =
            if (isLoginMode) {
                "Don't have an account? witch to Sign Up Mode"
            } else {
                "Already have an account? Switch to Login Mode"
            }
    }

    private val googleAuthButton = JButton("Login with Google")

    private val authState = service<AuthState>()
    private val appService = service<AppService>()

    // Panel components
    private val fullNameLabel = JLabel("Full name:")
    private val confirmPasswordLabel = JLabel("Confirm password:")

    // Section title components
    private val credentialsTitleLabel =
        JBLabel("Credential-based Authentication").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = EmptyBorder(0, 0, 5, 0)
        }

    private val googleTitleLabel =
        JBLabel("Google-based Authentication").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = EmptyBorder(0, 0, 5, 0)
        }

    override fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>,
    ) {
        // Add fields to stateValueFields list for state management
        stateValueFields.addAll(
            listOf(
                emailFieldSVF,
                fullNameFieldSVF,
                passwordFieldSVF,
                confirmPasswordFieldSVF,
                authModeToggleSVF,
            ),
        )

        // Create a panel with two columns separated by a vertical line
        val mainPanel = JPanel(BorderLayout())

        // Top panel for credential-based authentication
        val topPanel = JPanel(BorderLayout())
        val topContentPanel =
            FormBuilder.createFormBuilder()
                .addComponent(credentialsTitleLabel)
                .addComponent(authModeToggle)
                .addLabeledComponent(fullNameLabel, fullNameField)
                .addLabeledComponent("Email:", emailField)
                .addLabeledComponent("Password:", passwordField)
                .addLabeledComponent(confirmPasswordLabel, confirmPasswordField)
                .panel

        val authButton =
            JButton("Authenticate").apply {
                addActionListener {
                    performAuthentication()
                }
            }

        val topButtonPanel = JPanel(BorderLayout())
        topButtonPanel.add(authButton, BorderLayout.NORTH)
        topButtonPanel.border = EmptyBorder(10, 0, 0, 0)

        topPanel.add(topContentPanel, BorderLayout.CENTER)
        topPanel.add(topButtonPanel, BorderLayout.SOUTH)
        topPanel.border = EmptyBorder(0, 0, 10, 0)

        // Bottom panel for Google-based authentication
        val bottomPanel = JPanel(BorderLayout())
        val bottomContentPanel =
            FormBuilder.createFormBuilder()
                .addComponent(googleTitleLabel)
                .panel

        val googleButtonPanel = JPanel(BorderLayout())
        googleButtonPanel.add(googleAuthButton, BorderLayout.NORTH)
        googleButtonPanel.border = EmptyBorder(10, 0, 0, 0)

        bottomPanel.add(bottomContentPanel, BorderLayout.CENTER)
        bottomPanel.add(googleButtonPanel, BorderLayout.SOUTH)
        bottomPanel.border = EmptyBorder(10, 0, 0, 0)

        // Create a horizontal separator
        val separator = JSeparator(JSeparator.HORIZONTAL)

        // Add components to the main panel (top-bottom layout)
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(separator, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        // Add the main panel to the builder
        builder.addComponent(mainPanel)

        // Add listeners
        authModeToggle.addActionListener {
            updateFormVisibility()
        }

        googleAuthButton.addActionListener {
            initiateGoogleAuth()
        }

        // Initialize visibility
        updateFormVisibility()
    }

    private fun updateFormVisibility() {
        val isSignup = !authModeToggle.isSelected

        fullNameField.isVisible = isSignup
        fullNameLabel.isVisible = isSignup
        confirmPasswordField.isVisible = isSignup
        confirmPasswordLabel.isVisible = isSignup

        // Update Google button text based on mode
        googleAuthButton.text = if (isSignup) "Sign up with Google" else "Login with Google"

        // Update toggle button text
        updateToggleText()

        if (isSignup) {
            fullNameFieldSVF.getFieldInfo().remove(FieldInfo.INACTIVE)
            fullNameFieldSVF.getFieldInfo().add(FieldInfo.ACTIVE)
            confirmPasswordFieldSVF.getFieldInfo().remove(FieldInfo.INACTIVE)
            confirmPasswordFieldSVF.getFieldInfo().add(FieldInfo.ACTIVE)
        } else {
            fullNameFieldSVF.getFieldInfo().remove(FieldInfo.ACTIVE)
            fullNameFieldSVF.getFieldInfo().add(FieldInfo.INACTIVE)
            confirmPasswordFieldSVF.getFieldInfo().remove(FieldInfo.ACTIVE)
            confirmPasswordFieldSVF.getFieldInfo().add(FieldInfo.INACTIVE)
        }
    }

    private fun performAuthentication() {
        handleCredentialsAuth()
    }

    private fun handleCredentialsAuth() {
        val isSignup = !authModeToggle.isSelected

        if (isSignup) {
            if (!String(passwordField.password).equals(String(confirmPasswordField.password))) {
                showError("Passwords do not match")
                return
            }
            if (fullNameField.text.isBlank() || fullNameField.text.isEmpty()) {
                showError("Full name is required")
                return
            }
        }

        if (emailField.text.isBlank() || passwordField.password.isEmpty()) {
            showError("Email and password are required")
            return
        }

        // TODO: verify that the e-mail is in proper format

        val token =
            if (isSignup) {
                performSignup(
                    fullName = fullNameField.text,
                    email = emailField.text,
                    password = String(passwordField.password),
                )
            } else {
                performLogin(
                    email = emailField.text,
                    password = String(passwordField.password),
                )
            }

        if (token != null) {
            // Authentication successful - the session is now managed via cookies
            // and the token is stored in AuthState by the AppService methods

            showSuccess("Authentication successful!")

            // Clear all fields for security reasons
            clearAllFields()

            // Refresh the UI to show the authenticated state
            requiresUIRefresh.set(true)
        }
    }

    private fun initiateGoogleAuth() {
        val isSignup = !authModeToggle.isSelected
        GoogleAuthDialog(this, isSignup).show()
    }

    /**
     * Handles Google login for an existing user.
     * Currently a stub as Google authentication is a future feature.
     *
     * @param email The user's email from Google.
     * @param token The OAuth token from Google.
     */
    fun handleGoogleLogin(
        email: String,
        token: String,
    ) {
        showSuccess("Google authentication is not yet available in this version. This feature will be implemented in a future release.")
    }

    /**
     * Handles Google signup for a new user.
     * Currently a stub as Google authentication is a future feature.
     *
     * @param email The user's email from Google.
     * @param token The OAuth token from Google.
     */
    fun handleGoogleSignup(
        email: String,
        token: String,
    ) {
        showSuccess("Google authentication is not yet available in this version. This feature will be implemented in a future release.")
    }

    private fun performLogin(
        email: String,
        password: String,
    ): String? {
        return try {
            // Use the AppService to authenticate the user
            // Authentication is now primarily handled via cookies
            val response = appService.authenticateUser(email, password)
            // Return the token from the response message as a fallback
            // The session token is already stored in cookies and AuthState
            response.message
        } catch (e: ClientException) {
            showError("Login failed: ${e.message}")
            null
        } catch (e: ServerException) {
            showError("Server error: ${e.message}")
            null
        } catch (e: Exception) {
            showError("Unexpected error: ${e.message}")
            null
        }
    }

    private fun performSignup(
        fullName: String,
        email: String,
        password: String,
    ): String? {
        return try {
            // First create the user
            val createResponse =
                appService.createUser(
                    email = email,
                    name = fullName,
                    password = password,
                )

            // Then authenticate the user
            // Authentication is now primarily handled via cookies
            val authResponse = appService.authenticateUser(email, password)

            // Return the token from the response message as a fallback
            // The session token is already stored in cookies and AuthState
            authResponse.message
        } catch (e: ClientException) {
            showError("Signup failed: ${e.message}")
            null
        } catch (e: ServerException) {
            showError("Server error: ${e.message}")
            null
        } catch (e: Exception) {
            showError("Unexpected error: ${e.message}")
            null
        }
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE)
    }

    private fun showSuccess(message: String) {
        JOptionPane.showMessageDialog(null, message, "Success", JOptionPane.INFORMATION_MESSAGE)
    }

    private fun clearAllFields() {
        // Clear all input fields for security reasons
        emailField.text = ""
        passwordField.text = ""
        fullNameField.text = ""
        confirmPasswordField.text = ""
    }
}

/**
 * Dialog for collecting a password during Google signup.
 */
private class PasswordCreationDialog(private val email: String) : DialogWrapper(true) {
    private val nameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val confirmPasswordField = JBPasswordField()

    init {
        title = "Create Password"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("Please create a password for your account")
            }
            row {
                label("Email:")
                label(email)
            }
            row {
                label("Full Name:")
                cell(nameField)
                    .resizableColumn()
                    .focused()
            }
            row {
                label("Password:")
                cell(passwordField)
                    .resizableColumn()
            }
            row {
                label("Confirm Password:")
                cell(confirmPasswordField)
                    .resizableColumn()
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) {
            return ValidationInfo("Full name is required", nameField)
        }

        if (passwordField.password.isEmpty()) {
            return ValidationInfo("Password is required", passwordField)
        }

        if (!String(passwordField.password).equals(String(confirmPasswordField.password))) {
            return ValidationInfo("Passwords do not match", confirmPasswordField)
        }

        return null
    }

    fun getPassword(): String {
        return String(passwordField.password)
    }

    fun getName(): String {
        return nameField.text
    }
}

private class GoogleAuthDialog(
    private val authSection: AuthenticationSection,
    private val isSignup: Boolean,
) : DialogWrapper(true) {
    init {
        title = if (isSignup) "Google Sign Up" else "Google Login"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("Google Authentication - Coming Soon!")
            }
            row {
                label("Google authentication is not yet available in this version.")
            }
            row {
                label("This feature will be implemented in a future release.")
            }
            row {
                button("OK") {
                    close(OK_EXIT_CODE)
                }
            }
        }
    }
}
