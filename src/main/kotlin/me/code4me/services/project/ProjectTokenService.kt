package me.code4me.services.project

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Project-specific configuration state that stores a project identifier token.
 * This service is scoped to individual projects and persists data in project-specific storage.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ProjectTokenState",
    storages = [Storage("code4me-project-token.xml")],
)
class ProjectTokenService : SimplePersistentStateComponent<ProjectTokenState>(ProjectTokenState()) {
    /**
     * Sets the project identifier token
     */
    fun setProjectToken(token: String) {
        state.projectToken = token
    }

    /**
     * Gets the project identifier token
     */
    fun getProjectToken(): String? {
        return state.projectToken
    }

    /**
     * Checks if a project token is configured
     */
    fun hasProjectToken(): Boolean {
        return !state.projectToken.isNullOrBlank()
    }

    /**
     * Clears the project token
     */
    fun clearProjectToken() {
        state.projectToken = null
    }
}

/**
 * State class for project token data
 */
class ProjectTokenState : BaseState() {
    var projectToken by string()
}

/**
 * Helper function to get the ProjectTokenService for a specific project
 */
fun getProjectTokenService(project: Project): ProjectTokenService {
    return project.service<ProjectTokenService>()
}
