package me.code4me.research.telemetry

/**
 * Canonical, vendor-independent event concepts (Issue 06).
 *
 * These are canonical concepts, not ACP method names: an ACP notification is
 * *normalized into* one of these, and an unknown construct becomes
 * [UNKNOWN_SOURCE_EVENT] rather than a new vendor-named type. The values mirror
 * the server `research.telemetry.enums.CanonicalEventType` exactly, so adding a
 * concept requires a schema proposal, a privacy classification, and a fixture.
 */
object CanonicalEventTypes {
    const val INTERACTION_STARTED = "interaction.started"
    const val INTERACTION_COMPLETED = "interaction.completed"

    const val AGENT_MESSAGE_STARTED = "agent.message.started"
    const val AGENT_MESSAGE_COMPLETED = "agent.message.completed"

    const val TOOL_CREATED = "tool.created"
    const val TOOL_STARTED = "tool.started"
    const val TOOL_COMPLETED = "tool.completed"
    const val TOOL_FAILED = "tool.failed"

    const val PERMISSION_REQUESTED = "permission.requested"
    const val PERMISSION_DECIDED = "permission.decided"

    const val PLAN_UPDATED = "plan.updated"
    const val USAGE_UPDATED = "usage.updated"

    const val IDE_DOCUMENT_CHANGED = "ide.document.changed"
    const val IDE_FILE_OPENED = "ide.file.opened"
    const val IDE_FILE_SAVED = "ide.file.saved"
    const val IDE_FILE_CLOSED = "ide.file.closed"
    const val IDE_RUN_EXECUTED = "ide.run.executed"

    const val SYSTEM_AGENT_CRASHED = "system.agent.crashed"
    const val SYSTEM_PROXY_ERROR = "system.proxy.error"

    const val AGENT_ERROR = "agent.error"

    /** Preserved, un-reclassified input that did not match a documented concept. */
    const val UNKNOWN_SOURCE_EVENT = "unknown_source_event"

    /** The full canonical vocabulary, useful for validation and documentation tests. */
    val all: Set<String> =
        setOf(
            INTERACTION_STARTED,
            INTERACTION_COMPLETED,
            AGENT_MESSAGE_STARTED,
            AGENT_MESSAGE_COMPLETED,
            TOOL_CREATED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_FAILED,
            PERMISSION_REQUESTED,
            PERMISSION_DECIDED,
            PLAN_UPDATED,
            USAGE_UPDATED,
            IDE_DOCUMENT_CHANGED,
            IDE_FILE_OPENED,
            IDE_FILE_SAVED,
            IDE_FILE_CLOSED,
            IDE_RUN_EXECUTED,
            SYSTEM_AGENT_CRASHED,
            SYSTEM_PROXY_ERROR,
            AGENT_ERROR,
            UNKNOWN_SOURCE_EVENT,
        )
}
