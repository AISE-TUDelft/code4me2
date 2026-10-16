package me.code4me.research.session

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path

/**
 * Persisted research-runtime configuration for one project.
 *
 * The one-time capability file path is an operational setting, not study
 * policy: it never decides what is collected. When no capability path is
 * configured the manager writes the one-time capability to a
 * restricted-permission temp file instead.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ResearchRuntimeSettings",
    storages = [Storage("code4me-research-runtime.xml")],
)
class ResearchRuntimeSettings : SimplePersistentStateComponent<ResearchRuntimeState>(ResearchRuntimeState()) {
    /** The configured capability file path, or `null` to use a restricted temp file. */
    fun capabilityFilePath(): Path? =
        state.capabilityFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { Path.of(raw) }.getOrNull() }

    /**
     * DEV-ONLY: when true, a `self_contained: false` (source) runtime may be used
     * with [developmentInterpreter]. Defaults to `false`; a production build must
     * never enable this, and there is no host/PATH fallback either way.
     */
    fun allowDevelopmentRuntime(): Boolean = state.allowDevelopmentRuntime == true

    /** DEV-ONLY interpreter used for a source runtime (for example `/usr/bin/python3`). */
    fun developmentInterpreter(): String? = state.developmentInterpreter?.takeIf { it.isNotBlank() }

    /**
     * Participant-configured BYOA agent command or path (for example
     * `/usr/local/bin/goose`). It is only consulted when a study condition pins
     * `distribution_mode: BYOA_EXTERNAL`; a packaged runtime never uses it.
     */
    fun byoaAgentCommand(): String? = state.byoaAgentCommand?.takeIf { it.isNotBlank() }

    fun setCapabilityFilePath(value: Path?) {
        state.capabilityFilePath = value?.toString()
    }

    fun setAllowDevelopmentRuntime(value: Boolean) {
        state.allowDevelopmentRuntime = value
    }

    fun setDevelopmentInterpreter(value: String?) {
        state.developmentInterpreter = value?.takeIf { it.isNotBlank() }
    }

    fun setByoaAgentCommand(value: String?) {
        state.byoaAgentCommand = value?.takeIf { it.isNotBlank() }
    }
}

/** Persisted state for [ResearchRuntimeSettings]. */
class ResearchRuntimeState : BaseState() {
    var capabilityFilePath by string()

    var allowDevelopmentRuntime by property(false)

    var developmentInterpreter by string()

    var byoaAgentCommand by string()
}
