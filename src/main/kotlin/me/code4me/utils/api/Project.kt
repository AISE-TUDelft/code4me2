package me.code4me.utils.api

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import me.code4me.api.generated.model.ActivateProject
import me.code4me.api.generated.model.CreateProject
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectTokenService
import java.util.UUID

fun activateOrCreateProject(
    project: Project,
    logger: Logger,
) {
    val projectTokenService = getProjectTokenService(project)

    var activated = false

    if (projectTokenService.hasProjectToken() && projectTokenService.getProjectToken() != null) {
        try {
            val projectToken = projectTokenService.getProjectToken()
            logger.info("Activating project with token: $projectToken")
            getAppService().activateProject(
                ActivateProject(
                    projectId = UUID.fromString(projectToken!!),
                ),
            )
            activated = true
        } catch (e: Exception) {
            logger.warn("Activation with existing token failed: ${e.message}")
        }
    }

    if (!activated) {
        logger.warn("Creating a new project and token.")
        val projectName = project.name.ifBlank { "Unnamed Project" }

        getAppService().createProjectWithStoredToken(
            CreateProject(
                projectName = projectName,
            ),
            project,
        )

        if (!activated) {
            logger.warn("Creating a new project and token.")
            val projectName = project.name.ifBlank { "Unnamed Project" }

            val response =
                getAppService().createProjectWithStoredToken(
                    CreateProject(
                        projectName = projectName,
                    ),
                    project,
                )

            val newToken = response?.projectToken
            if (!newToken.isNullOrBlank()) {
                logger.info("Activating project with new token from response: $newToken")
                try {
                    getAppService().activateProject(
                        ActivateProject(
                            projectId = UUID.fromString(newToken),
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to activate project with new token: ${e.message}", e)
                }
            } else {
                logger.error("No token returned from project creation response.")
            }
        }
    }
}
