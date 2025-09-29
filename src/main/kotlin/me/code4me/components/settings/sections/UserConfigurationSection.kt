package me.code4me.components.settings.sections

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.infrastructure.ServerException
import me.code4me.api.generated.model.UpdateUser
import me.code4me.components.settings.fields.StateValueField
import me.code4me.services.app.AppService
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectChatService
import me.code4me.services.state.AuthState
import me.code4me.settings.ConfigurationConfigurable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.io.IOException
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.border.AbstractBorder

/**
 * Settings section for user profile management.
 *
 * This section provides comprehensive user account management including:
 * - User profile information display and modification
 * - Email verification status and controls
 * - Account deletion functionality
 * - Sign out functionality
 * - Integration with ChatPanel overlay updates
 *
 * The section is only displayed when the user is authenticated.
 *
 * @since 1.0.0
 */
class UserSection(
    private val onBackToConfiguration: (() -> Unit)? = null,
) : SettingsSection {
    companion object {
        private val LOG = thisLogger()

        // UI Constants
        private const val FORM_PADDING = 20
        private const val SECTION_SPACING = 16
        private const val CARD_PADDING = 16
        private const val BUTTON_HEIGHT = 32
    }

    private var userNameLabelRef: JLabel? = null
    private var userEmailLabelRef: JLabel? = null
    private var verificationCardRef: JPanel? = null

    // Button styling enum
    private enum class ButtonType { PRIMARY, SECONDARY, DANGER }

    private val configurationButton =
        JButton("Manage configuration").apply {
            toolTipText = "Go to module & preferences configuration"
            addActionListener { navigateToConfiguration() }
            putClientProperty("JButton.buttonType", "link")
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
        }

    // Services
    private val appService = service<AppService>()
    private val authState = service<AuthState>().state

    // ================= UI COMPONENTS =================

    /**
     * Title label for user information section.
     */
    private val userInfoTitleLabel =
        JBLabel("User Profile").apply {
            font = font.deriveFont(font.style or Font.BOLD, 16f)
            border = JBUI.Borders.emptyBottom(8)
        }

    /**
     * Sign out button for user authentication management.
     */
    private val signOutButton =
        createStyledButton("Sign Out", ButtonType.SECONDARY).apply {
            toolTipText = "Sign out of your Code4Me account"
            addActionListener { handleSignOut() }
        }

    init {
        if (!authState.isAuthenticated()) {
            LOG.warn("UserSection initialized without authentication")
            authState.clearUserData()
        } else {
            try {
                val currentUser = getAppService().getCurrentUser()
                authState.setUserName(currentUser.user.name)
                authState.setUserEmail(currentUser.user.email)
                authState.setVerified(currentUser.user.verified)
            } catch (e: Exception) {
                // remove user data if fetching fails
                authState.clearUserData()
            }
            LOG.debug("UserSection initialized")
        }
    }

    /**
     * Creates a custom rounded border for cards and components.
     */
    private class RoundedBorder(
        private val color: java.awt.Color,
        private val thickness: Int,
        private val radius: Int,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: Component,
            g: Graphics,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = java.awt.BasicStroke(thickness.toFloat())
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
            g2.dispose()
        }

        override fun getBorderInsets(c: Component): Insets {
            return Insets(thickness, thickness, thickness, thickness)
        }
    }

    /**
     * Creates a styled button with consistent appearance.
     */
    private fun createStyledButton(
        text: String,
        type: ButtonType = ButtonType.SECONDARY,
    ): JButton {
        return JButton(text).apply {
            when (type) {
                ButtonType.PRIMARY -> {
                    putClientProperty("JButton.buttonType", "default")
                }
                ButtonType.SECONDARY -> {
                    putClientProperty("JButton.buttonType", "borderless")
                }
                ButtonType.DANGER -> {
                    foreground = JBColor.RED
                    putClientProperty("JButton.buttonType", "borderless")
                }
            }

            preferredSize = Dimension(preferredSize.width, BUTTON_HEIGHT)
            font = font.deriveFont(Font.PLAIN, 13f)
        }
    }

    /**
     * Creates a card panel with rounded border and title.
     */
    private fun createCard(
        title: String,
        content: () -> JComponent,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    RoundedBorder(JBColor.border(), 1, 8),
                    JBUI.Borders.empty(CARD_PADDING),
                )
            background = JBColor.background()

            val titleLabel =
                JBLabel(title).apply {
                    font = font.deriveFont(Font.BOLD, 14f)
                    border = JBUI.Borders.emptyBottom(12)
                }

            add(titleLabel, BorderLayout.NORTH)
            add(content(), BorderLayout.CENTER)
        }
    }

    /**
     * Creates a status badge component.
     */
    private fun createStatusBadge(
        text: String,
        isVerified: Boolean,
    ): JComponent {
        return JPanel().apply {
            layout = FlowLayout(FlowLayout.LEFT, 0, 0)
            isOpaque = false

            val badge =
                JPanel(FlowLayout(FlowLayout.CENTER, 8, 4)).apply {
                    val label =
                        JLabel(text).apply {
                            foreground = JBColor.WHITE
                            font = font.deriveFont(Font.BOLD, 11f)
                        }

                    background = if (isVerified) JBColor.GREEN.darker() else JBColor.ORANGE
                    border =
                        RoundedBorder(
                            if (isVerified) JBColor.GREEN.darker() else JBColor.ORANGE,
                            0,
                            12,
                        )
                    add(label)
                }

            add(badge)
        }
    }

    /**
     * Navigates to the Configuration page instead of the parent landing page.
     */
    private fun navigateToConfiguration() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(100)
                if (dataContext != null) {
                    val settingsDialog = Settings.KEY.getData(dataContext)

                    if (settingsDialog != null) {
                        val configurable = settingsDialog.find(ConfigurationConfigurable::class.java)
                        if (configurable != null) {
                            settingsDialog.select(configurable)
                            LOG.debug("Successfully navigated to Configuration page")
                        } else {
                            LOG.warn("Could not find Configuration configurable in current settings dialog")
                            // Fallback: use the callback if available
                            onBackToConfiguration?.invoke()
                        }
                    } else {
                        LOG.warn("Could not find current settings dialog context")
                        // Fallback: use the callback if available
                        onBackToConfiguration?.invoke()
                    }
                } else {
                    LOG.warn("Could not get data context")
                    // Fallback: use the callback if available
                    onBackToConfiguration?.invoke()
                }
            } catch (e: Exception) {
                LOG.error("Failed to navigate to Configuration page", e)
                // Fallback: use the callback if available
                onBackToConfiguration?.invoke()
            }
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

    override fun applyTo(
        builder: FormBuilder,
        stateValueFields: MutableList<StateValueField<*>>,
    ) {
        val mainPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(FORM_PADDING)
                background = JBColor.background()
            }

        // Header with user info and back button
        val headerPanel =
            JPanel(BorderLayout()).apply {
                val leftPanel =
                    JPanel(BorderLayout()).apply {
                        add(Box.createHorizontalStrut(16), BorderLayout.CENTER)
                        add(createBasicUserInfo(), BorderLayout.EAST)
                    }

                add(leftPanel, BorderLayout.WEST)
                add(configurationButton, BorderLayout.EAST)
                border = JBUI.Borders.emptyBottom(24)
            }

        // Content sections with proper spacing
        val contentPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = JBColor.background()

                add(createVerificationSection())
                add(Box.createVerticalStrut(SECTION_SPACING))
                add(createAccountManagementPanel())
                add(Box.createVerticalStrut(SECTION_SPACING))
            }

        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(contentPanel, BorderLayout.CENTER)

        builder.addComponent(mainPanel)

        LOG.debug("User section applied to form builder")
    }

    /**
     * Creates basic user information display.
     */
    private fun createBasicUserInfo(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.background()
            alignmentX = LEFT_ALIGNMENT

            val nameLabel =
                JLabel(authState.getUserName() ?: "Unknown User").apply {
                    font = font.deriveFont(Font.BOLD, 16f)
                    alignmentX = LEFT_ALIGNMENT
                }

            val emailLabel =
                JLabel(authState.getUserEmail() ?: "Unknown Email").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(13f)
                    alignmentX = LEFT_ALIGNMENT
                }

            userNameLabelRef = nameLabel
            userEmailLabelRef = emailLabel

            add(nameLabel)
            add(Box.createVerticalStrut(4))
            add(emailLabel)
        }
    }

    /**
     * Creates the verification status section.
     */
    private fun createVerificationSection(): JPanel {
        val verificationCard =
            createCard("Verification Status") {
                JPanel(BorderLayout()).apply {
                    background = JBColor.background()
                }
            }

        verificationCardRef = verificationCard
        updateVerificationContent(verificationCard)
        return verificationCard
    }

    private fun refreshVerificationUI() {
        verificationCardRef?.let { updateVerificationContent(it) }
    }

    /**
     * Updates the verification section content based on current status.
     */
    private fun updateVerificationContent(verificationCard: JPanel) {
        val contentPanel =
            verificationCard.components
                .filterIsInstance<JPanel>()
                .firstOrNull { it.layout is BorderLayout }

        contentPanel?.let { panel ->
            panel.removeAll()

            val isVerified = authState.isVerified() ?: false

            if (isVerified) {
                panel.add(createStatusBadge("Verified", true), BorderLayout.WEST)
            } else {
                val leftPanel =
                    JPanel(BorderLayout()).apply {
                        background = JBColor.background()
                        add(createStatusBadge("Not Verified", false), BorderLayout.WEST)
                    }

                val rightPanel =
                    JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        background = JBColor.background()

                        val resendButton = createStyledButton("Resend Email", ButtonType.SECONDARY)
                        resendButton.toolTipText = "Resend verification email"
                        resendButton.addActionListener {
                            try {
                                appService.resendVerificationEmail()
                                Messages.showInfoMessage(
                                    "Verification email has been resent. Please check your inbox.",
                                    "Email Resent",
                                )
                                // disable the button for 5 minutes
                                resendButton.isEnabled = false
                                Timer(300000) { resendButton.isEnabled = true }.start()
                            } catch (e: Exception) {
                                LOG.error("Failed to resend verification email", e)
                                Messages.showErrorDialog(
                                    "Failed to resend verification email. Please try again later.",
                                    "Error",
                                )
                            }
                        }

                        val recheckButton = createStyledButton("Recheck Status", ButtonType.SECONDARY)
                        recheckButton.toolTipText = "Check if your account has been verified"
                        recheckButton.addActionListener {
                            val verified = appService.isUserVerified()
                            authState.setVerified(verified)
                            if (verified) {
                                // Update the UI to show verified status
                                updateVerificationContent(verificationCard)
                                Messages.showInfoMessage(
                                    "Your account is now verified.",
                                    "Verification Status",
                                )
                            } else {
                                Messages.showInfoMessage(
                                    "Your account is still not verified.",
                                    "Verification Status",
                                )
                            }
                        }

                        add(resendButton)
                        add(recheckButton)
                    }

                panel.add(leftPanel, BorderLayout.WEST)
                panel.add(rightPanel, BorderLayout.EAST)
            }

            panel.revalidate()
            panel.repaint()
        }
    }

    /**
     * Creates the account management panel with profile modification and deletion.
     */
    private fun createAccountManagementPanel(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = JBColor.background()
            }

        // Profile Actions Card
        val profileCard =
            createCard("Profile Management") {
                JPanel(GridLayout(1, 2, 12, 0)).apply {
                    background = JBColor.background()

                    val modifyButton =
                        createStyledButton("Edit Profile", ButtonType.PRIMARY).apply {
                            toolTipText = "Change your profile information"
                            addActionListener { showProfileEditDialog() }
                        }

                    val changePasswordButton =
                        createStyledButton("Change Password", ButtonType.SECONDARY).apply {
                            toolTipText = "Update your account password"
                            addActionListener { showPasswordChangeDialog() }
                        }

                    add(modifyButton)
                    add(changePasswordButton)
                }
            }

        val dangerCard =
            createCard("Exit options") {
                JPanel(GridLayout(1, 2, 12, 0)).apply {
                    background = JBColor.background()

                    // Sign out button (moved here)
                    val signOutButton = createStyledButton("Sign Out", ButtonType.SECONDARY)
                    signOutButton.toolTipText = "Sign out of your Code4Me account"
                    signOutButton.addActionListener { handleSignOut() }

                    val deleteButton = createStyledButton("Delete Account", ButtonType.DANGER)
                    deleteButton.toolTipText = "Permanently delete your account"
                    deleteButton.addActionListener {
                        showAccountDeletionDialog()
                    }

                    add(signOutButton)
                    add(deleteButton)
                }
            }

        panel.add(profileCard)
        panel.add(Box.createVerticalStrut(SECTION_SPACING))
        panel.add(dangerCard)

        return panel
    }

    private fun refreshUserInfoUI() {
        userNameLabelRef?.text = authState.getUserName() ?: "Unknown User"
        userEmailLabelRef?.text = authState.getUserEmail() ?: "Unknown Email"
        userNameLabelRef?.parent?.revalidate()
        userNameLabelRef?.parent?.repaint()
    }

    private inner class ProfileEditDialog(
        private val authState: me.code4me.services.state.AuthSettings,
    ) : DialogWrapper(true) {
        private val nameField = JBTextField(authState.getUserName() ?: "")
        private val emailField = JBTextField(authState.getUserEmail() ?: "")

        init {
            title = "Edit Profile"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.border = JBUI.Borders.empty(10)

            val namePanel =
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.emptyBottom(10)
                    add(JLabel("New Name:"), BorderLayout.WEST)
                    add(nameField, BorderLayout.CENTER)
                }

            val emailPanel =
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.emptyBottom(10)
                    add(JLabel("New Email:"), BorderLayout.WEST)
                    add(emailField, BorderLayout.CENTER)
                }

            panel.add(namePanel)
            panel.add(emailPanel)
            return panel
        }

        override fun doValidate(): ValidationInfo? {
            val nameChanged = nameField.text.trim() != (authState.getUserName() ?: "")
            val emailChanged = emailField.text.trim() != (authState.getUserEmail() ?: "")

            if (!nameChanged && !emailChanged) {
                return ValidationInfo("Please change at least one field to update your profile")
            }

            // Basic email sanity check (optional, server still validates)
            if (emailChanged && !emailField.text.contains("@")) {
                return ValidationInfo("Please enter a valid email address", emailField)
            }

            return null
        }

        fun getNewName(): String = nameField.text.trim()

        fun getNewEmail(): String = emailField.text.trim()
    }

    private inner class PasswordChangeDialog : DialogWrapper(true) {
        private val oldPasswordField = JBPasswordField()
        private val newPasswordField = JBPasswordField()
        private val confirmPasswordField = JBPasswordField()

        init {
            title = "Change Password"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val dialogPanel = JPanel()
            dialogPanel.layout = BoxLayout(dialogPanel, BoxLayout.Y_AXIS)
            dialogPanel.border = JBUI.Borders.empty(10)

            fun row(
                label: String,
                field: JComponent,
            ) = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(8)
                add(JLabel(label), BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
            }

            dialogPanel.add(row("Current Password:", oldPasswordField))
            dialogPanel.add(row("New Password:", newPasswordField))
            dialogPanel.add(row("Confirm Password:", confirmPasswordField))

            val note = JLabel("Password must be at least 8 characters, include upper, lower, and a digit.")
            note.foreground = JBColor.GRAY
            dialogPanel.add(note)

            return dialogPanel
        }

        override fun doValidate(): ValidationInfo? {
            val oldPwd = String(oldPasswordField.password)
            val newPwd = String(newPasswordField.password)
            val confirm = String(confirmPasswordField.password)

            if (oldPwd.isBlank()) return ValidationInfo("Current password is required", oldPasswordField)
            if (newPwd.isBlank()) return ValidationInfo("New password cannot be empty", newPasswordField)
            if (newPwd != confirm) return ValidationInfo("Passwords do not match", confirmPasswordField)

            // strong password check (note: use OR not AND)
            if (!(newPwd.length >= 8 && newPwd.matches(Regex("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)\\S{8,}$")))) {
                return ValidationInfo("Password must be 8+ chars with upper, lower, and a digit", newPasswordField)
            }

            return null
        }

        fun getOldPassword(): String = String(oldPasswordField.password)

        fun getNewPassword(): String = String(newPasswordField.password)
    }

    private fun showProfileEditDialog() {
        val dialog = ProfileEditDialog(authState)
        if (!dialog.showAndGet()) return

        val newName = dialog.getNewName()
        val newEmail = dialog.getNewEmail()

        try {
            val updateUser =
                UpdateUser(
                    name = if (newName.isNotBlank() && newName != authState.getUserName()) newName else null,
                    email = if (newEmail.isNotBlank() && newEmail != authState.getUserEmail()) newEmail else null,
                    previousPassword = null,
                    password = null,
                )

            val hasUpdates = listOf(updateUser.name, updateUser.email).any { it != null }
            if (!hasUpdates) {
                Messages.showInfoMessage("No changes were made to your profile.", "No Updates")
                return
            }

            appService.updateUser(updateUser)

            updateUser.name?.let { authState.setUserName(it) }
            updateUser.email?.let { authState.setUserEmail(it) }

            refreshUserInfoUI()
            refreshVerificationUI()

            Messages.showInfoMessage("Your profile has been updated successfully.", "Profile Updated")
        } catch (e: Exception) {
            LOG.error("Failed to update user profile", e)
            val errorMessage =
                when (e) {
                    is ClientException -> "Invalid input or authentication failed."
                    is ServerException -> "Server error occurred. Please try again later."
                    is IOException -> "Network error occurred. Please check your connection."
                    else -> "An unexpected error occurred: ${e.message}"
                }
            Messages.showErrorDialog(errorMessage, "Profile Update Error")
        }
    }

    private fun showPasswordChangeDialog() {
        val dialog = PasswordChangeDialog()
        if (!dialog.showAndGet()) return

        val oldPassword = dialog.getOldPassword()
        val newPassword = dialog.getNewPassword()

        try {
            val updateUser =
                UpdateUser(
                    name = null,
                    email = null,
                    previousPassword = oldPassword.ifBlank { null },
                    password = newPassword.ifBlank { null },
                )

            if (updateUser.password == null || updateUser.previousPassword == null) {
                Messages.showInfoMessage("No password change requested.", "No Updates")
                return
            }

            appService.updateUser(updateUser)
            Messages.showInfoMessage("Your password has been updated successfully.", "Password Updated")
        } catch (e: Exception) {
            LOG.error("Failed to update password", e)
            val errorMessage =
                when (e) {
                    is ClientException -> "Invalid password or authentication failed. Please check your current password."
                    is ServerException -> "Server error occurred. Please try again later."
                    is IOException -> "Network error occurred. Please check your connection."
                    else -> "An unexpected error occurred: ${e.message}"
                }
            Messages.showErrorDialog(errorMessage, "Password Update Error")
        }
    }

    /**
     * Shows a dialog specifically for account deletion.
     */
    private fun showAccountDeletionDialog() {
        val dialog = AccountDeletionDialog()

        if (dialog.showAndGet()) {
            val willDeleteData = dialog.shouldDeleteData()

            val confirmMessage =
                if (willDeleteData) {
                    "This will permanently delete your account AND all your data. This action cannot be undone."
                } else {
                    "This will permanently delete your account. This action cannot be undone."
                }

            val confirmResult =
                Messages.showYesNoDialog(
                    confirmMessage,
                    "Final Confirmation",
                    "Delete Account",
                    "Cancel",
                    Messages.getWarningIcon(),
                )

            if (confirmResult == Messages.YES) {
                try {
                    // Clear chat data explicitly for all projects before account deletion
                    ProjectManager.getInstance().openProjects.forEach { project ->
                        try {
                            val chatService = getProjectChatService(project)
                            chatService.clearAllChatsAndMemory()

                            // Force persistence of the cleared state
                            ApplicationManager.getApplication().invokeAndWait {
                                project.save()
                            }

                            LOG.info("Cleared all chat sessions for project: ${project.name}")
                        } catch (e: ProcessCanceledException) {
                            throw e // ProcessCanceledException cannot be caught
                        } catch (e: Exception) {
                            LOG.error("Failed to clear chat data for project: ${project.name}", e)
                        }
                    }

                    // Pass the checkbox state to determine if user data should be deleted
                    appService.deleteUser(willDeleteData)

                    // Clear local data immediately after account deletion
                    authState.clearUserData()

                    val successMessage =
                        if (willDeleteData) {
                            "Your account and all associated data have been deleted successfully."
                        } else {
                            "Your account has been deleted successfully."
                        }

                    Messages.showInfoMessage(
                        successMessage,
                        "Account Deleted",
                    )

                    // Update chat panel overlays immediately after account deletion
                    updateChatPanelOverlays()
                } catch (e: Exception) {
                    val errorMessage =
                        when (e) {
                            is ClientException -> "Failed to delete account. Please check your authentication."
                            is ServerException -> "Server error occurred during account deletion. Please try again later."
                            is IOException -> "Network error occurred. Please check your connection."
                            is ProcessCanceledException -> throw e // ProcessCanceledException cannot be caught
                            else -> "An error occurred while deleting your account: ${e.message}"
                        }

                    Messages.showErrorDialog(
                        errorMessage,
                        "Delete Account Error",
                    )
                }
            }
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

    /**
     * Dialog for account deletion confirmation with data deletion option.
     */
    private inner class AccountDeletionDialog : DialogWrapper(true) {
        private val deleteDataCheckbox = JBCheckBox("Also delete my data")

        init {
            title = "Delete Account"
            deleteDataCheckbox.toolTipText = "If checked, all your data will be permanently deleted"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(10)

            val messageLabel = JLabel("Are you sure you want to delete your account?")
            messageLabel.border = JBUI.Borders.emptyBottom(10)

            panel.add(messageLabel, BorderLayout.NORTH)
            panel.add(deleteDataCheckbox, BorderLayout.CENTER)

            return panel
        }

        fun shouldDeleteData(): Boolean = deleteDataCheckbox.isSelected
    }
}
