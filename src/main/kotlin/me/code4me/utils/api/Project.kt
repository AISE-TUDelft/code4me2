package me.code4me.utils.api

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import me.code4me.api.generated.model.ActivateProject
import me.code4me.api.generated.model.CreateProject
import me.code4me.services.app.getAppService
import me.code4me.services.project.getProjectTokenService
import me.code4me.startup.PluginStartupActivity


public fun activateOrCreateProject(project: Project, logger: Logger) {
    val projectTokenService = getProjectTokenService(project)
    // if the project token service has a token, activate the project
    if (projectTokenService.hasProjectToken() && projectTokenService.getProjectToken() != null) {
        val projectToken = projectTokenService.getProjectToken()
        logger.info("Activating project with token: $projectToken")
        getAppService().activateProject(
            ActivateProject(
                projectToken = projectToken!!,
            )
        )
    } else {
        logger.warn("No project token found, creating a new project.")
        logger.warn("Project token is null, creating new project.")
        // project name
        val projectName = project.name.ifBlank { "Unnamed Project" }
        getAppService().createProjectWithStoredToken(
            CreateProject(
                projectName = projectName,
            ),
            project
        )
        if (projectTokenService.hasProjectToken()) {
            logger.info("Project created and token acquired successfully.")
        } else {
            logger.warn("Failed to create project or acquire project token.")
        }
    }
}