package me.code4me.research.actions

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import me.code4me.research.session.ResearchActivationResult

/**
 * Project-scoped persistence for the participant's chosen enrollment id.
 *
 * It exists so the Join action can remember a working enrollment and the
 * startup activity can lazily re-activate it on the next project open. Only a
 * successfully activated enrollment is stored (see [shouldPersistEnrollment]),
 * so a mistyped or unusable code is never replayed.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ResearchEnrollmentSettings",
    storages = [Storage("code4me-research-enrollment.xml")],
)
class ResearchEnrollmentSettings :
    SimplePersistentStateComponent<ResearchEnrollmentState>(ResearchEnrollmentState()) {
    /** The stored enrollment id, or `null` when this project has not joined. */
    fun enrollmentId(): String? = state.enrollmentId

    /** Store [enrollmentId] (or clear it with `null`). */
    fun setEnrollmentId(enrollmentId: String?) {
        state.enrollmentId = enrollmentId
    }

    /** Forget the stored enrollment id. */
    fun clear() {
        state.enrollmentId = null
    }
}

/** Persisted state for [ResearchEnrollmentSettings]. */
class ResearchEnrollmentState : BaseState() {
    var enrollmentId by string()
}

/** Convenience accessor mirroring the other project services. */
fun getResearchEnrollmentSettings(project: Project): ResearchEnrollmentSettings = project.service()

/**
 * Whether an enrollment id should be persisted after [result].
 *
 * Only a fully activated enrollment is remembered: retryable/failed activations
 * are transient, and a blocked result means the code is not usable (wrong,
 * revoked, expired, or incompatible), so storing it would replay a bad code on
 * every project open.
 */
internal fun shouldPersistEnrollment(result: ResearchActivationResult): Boolean =
    result is ResearchActivationResult.Activated
