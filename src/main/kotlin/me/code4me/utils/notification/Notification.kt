package me.code4me.utils.notification

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * Configuration constants for authentication notifications.
 */
object AuthNotificationConfig {
    const val NOTIFICATION_GROUP_ID = "Code4Me V2"
    const val SETTINGS_CONFIGURABLE_ID = "me.code4me.settings" // Replace with your actual settings configurable ID
}

/**
 * Shows a notification when no authentication token is found,
 * prompting the user to open settings to login.
 *
 * @param title Optional custom title for the notification
 * @param message Optional custom message for the notification
 * @param settingsId Optional custom settings configurable ID
 */
fun Project.showLoginRequiredNotification(
    title: String = "Authentication Required",
    message: String = "Please login to access Code4Me features. Click 'Open Settings' to configure your authentication.",
    settingsId: String = AuthNotificationConfig.SETTINGS_CONFIGURABLE_ID,
) {
    val notificationGroup =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(AuthNotificationConfig.NOTIFICATION_GROUP_ID)

    val notification =
        notificationGroup.createNotification(
            title,
            message,
            NotificationType.WARNING,
        )

    // Add action to open settings
    notification.addAction(
        object : NotificationAction("Open Settings") {
            override fun actionPerformed(
                e: AnActionEvent,
                notification: com.intellij.notification.Notification,
            ) {
                ShowSettingsUtil.getInstance().showSettingsDialog(this@showLoginRequiredNotification, settingsId)
                notification.expire()
            }
        },
    )

    // Add dismiss action
    notification.addAction(
        object : NotificationAction("Dismiss") {
            override fun actionPerformed(
                e: AnActionEvent,
                notification: com.intellij.notification.Notification,
            ) {
                notification.expire()
            }
        },
    )

    notification.notify(this)
}

/**
 * Shows a notification when the authentication token has been invalidated,
 * informing the user that they need to log back in.
 *
 * @param title Optional custom title for the notification
 * @param message Optional custom message for the notification
 * @param settingsId Optional custom settings configurable ID
 */
fun Project.showTokenInvalidationNotification(
    title: String = "Authentication Token Expired",
    message: String = "Your authentication token has been invalidated. You have been signed out and need to log back in.",
    settingsId: String = AuthNotificationConfig.SETTINGS_CONFIGURABLE_ID,
) {
    val notificationGroup =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(AuthNotificationConfig.NOTIFICATION_GROUP_ID)

    val notification =
        notificationGroup.createNotification(
            title,
            message,
            NotificationType.ERROR,
        )

    // Add action to open settings
    notification.addAction(
        object : NotificationAction("Open Settings") {
            override fun actionPerformed(
                e: AnActionEvent,
                notification: com.intellij.notification.Notification,
            ) {
                ShowSettingsUtil.getInstance().showSettingsDialog(this@showTokenInvalidationNotification, settingsId)
                notification.expire()
            }
        },
    )

    // Add dismiss action
    notification.addAction(
        object : NotificationAction("Dismiss") {
            override fun actionPerformed(
                e: AnActionEvent,
                notification: com.intellij.notification.Notification,
            ) {
                notification.expire()
            }
        },
    )

    notification.notify(this)
}

/**
 * Generic notification extension for creating custom authentication-related notifications.
 *
 * @param title The notification title
 * @param message The notification message
 * @param type The notification type (INFO, WARNING, ERROR)
 * @param actions Optional list of custom actions to add to the notification
 * @param includeSettingsAction Whether to automatically include an "Open Settings" action
 * @param includeDismissAction Whether to automatically include a "Dismiss" action
 * @param settingsId The settings configurable ID to open when "Open Settings" is clicked
 */
fun Project.showAuthNotification(
    title: String,
    message: String,
    type: NotificationType = NotificationType.INFORMATION,
    actions: List<NotificationAction> = emptyList(),
    includeSettingsAction: Boolean = true,
    includeDismissAction: Boolean = true,
    settingsId: String = AuthNotificationConfig.SETTINGS_CONFIGURABLE_ID,
) {
    val notificationGroup =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(AuthNotificationConfig.NOTIFICATION_GROUP_ID)

    val notification = notificationGroup.createNotification(title, message, type)

    // Add custom actions first
    actions.forEach { notification.addAction(it) }

    // Add settings action if requested
    if (includeSettingsAction) {
        notification.addAction(
            object : NotificationAction("Open Settings") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(this@showAuthNotification, settingsId)
                    notification.expire()
                }
            },
        )
    }

    // Add dismiss action if requested
    if (includeDismissAction) {
        notification.addAction(
            object : NotificationAction("Dismiss") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    notification.expire()
                }
            },
        )
    }

    notification.notify(this)
}

/**
 * Shows a success notification for authentication-related events.
 *
 * @param title The notification title
 * @param message The notification message
 * @param includeDismissAction Whether to include a dismiss action
 */
fun Project.showAuthSuccessNotification(
    title: String,
    message: String,
    includeDismissAction: Boolean = true,
) {
    showAuthNotification(
        title = title,
        message = message,
        type = NotificationType.INFORMATION,
        includeSettingsAction = false,
        includeDismissAction = includeDismissAction,
    )
}

/**
 * Shows a notification when preference synchronization fails.
 *
 * @param title Optional custom title for the notification
 * @param message Optional custom message for the notification
 */
fun Project.showPreferenceSyncFailedNotification(
    title: String = "Preference Sync Failed",
    message: String =
        "Failed to sync preferences to server. " +
            "Your changes are saved locally and will be synced when connection is restored.",
) {
    showAuthNotification(
        title = title,
        message = message,
        type = NotificationType.WARNING,
        includeSettingsAction = false,
        includeDismissAction = true,
    )
}

fun Project.showErrorNotification(
    title: String,
    message: String,
    includeSettingsAction: Boolean = false,
    includeDismissAction: Boolean = true,
) {
    showAuthNotification(
        title = title,
        message = message,
        type = NotificationType.ERROR,
        includeSettingsAction = includeSettingsAction,
        includeDismissAction = includeDismissAction,
    )
}
