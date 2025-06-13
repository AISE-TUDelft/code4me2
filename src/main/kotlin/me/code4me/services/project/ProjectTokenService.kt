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
     * Gets the current state of the project token
     */
    fun isActivated(): Boolean {
        return state.isActivated
    }

    /**
     * Sets the activation state of the project token service.
     * This can be used to indicate whether the project is activated or not.
     */
    fun setActivated(isActivated: Boolean) {
        state.isActivated = isActivated
    }

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
    var isActivated by property(false)
}

/**
 * Helper function to get the ProjectTokenService for a specific project
 */
fun getProjectTokenService(project: Project): ProjectTokenService {
    return project.service<ProjectTokenService>()
}
