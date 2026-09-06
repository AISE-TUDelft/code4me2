package me.code4me.utils.api

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import me.code4me.api.generated.infrastructure.ClientException
import me.code4me.api.generated.model.ActivateProject
import me.code4me.api.generated.model.CreateProject
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectTokenService
import java.util.UUID

/**
 * Activates an existing project if a project token is available; otherwise, creates a new project
 * and activates it if a token becomes available after creation. Logs relevant information and warnings
 * during the activation or creation process.
 *
 * @param project The project instance to be activated or created.
 * @param logger The logger instance used for logging process details, warnings, or errors.
 */
public fun activateOrCreateProject(
    project: Project,
    logger: Logger,
) {
    val projectTokenService = getProjectTokenService(project)
    // if the project token service has a token, activate the project
    if (projectTokenService.hasProjectToken() && projectTokenService.getProjectToken() != null) {
        val projectToken = projectTokenService.getProjectToken()
        logger.info("Activating stored project")
        try {
            getAppService().activateProject(
                ActivateProject(
                    projectId = UUID.fromString(projectToken!!),
                ),
            )
            projectTokenService.setActivated(true)
            return
        } catch (e: ClientException) {
            // A stored token that the server no longer recognises (deleted project, different
            // environment, rotated credentials) used to be fatal. Discard it and fall through to
            // creating a fresh project instead.
            if (e.statusCode != 401 && e.statusCode != 404) {
                throw e
            }
            logger.warn("Stored project token is no longer valid, creating a new project.", e)
            projectTokenService.clearProjectToken()
            projectTokenService.setActivated(false)
        } catch (e: IllegalArgumentException) {
            logger.warn("Stored project token is malformed, creating a new project.", e)
            projectTokenService.clearProjectToken()
            projectTokenService.setActivated(false)
        }
    }

    if (!projectTokenService.hasProjectToken()) {
        logger.warn("No project token found, creating a new project.")
        val projectName = project.name.ifBlank { "Unnamed Project" }
        getAppService().createProjectWithStoredToken(
            CreateProject(
                projectName = projectName,
            ),
            project,
        )
    }

    // After creating the project, check if the project token is set and activate it.
    if (projectTokenService.getProjectToken() != null) {
        getAppService().activateProject(
            ActivateProject(
                projectId = UUID.fromString(projectTokenService.getProjectToken()!!),
            ),
        )
        projectTokenService.setActivated(true)
    } else {
        logger.warn("Project token is still null after creation, activation skipped.")
    }
    if (projectTokenService.hasProjectToken()) {
        logger.info("Project created and token acquired successfully.")
    } else {
        logger.warn("Failed to create project or acquire project token.")
    }
}
