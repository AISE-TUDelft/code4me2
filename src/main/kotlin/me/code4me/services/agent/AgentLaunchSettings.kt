package me.code4me.services.agent

import java.util.UUID

/**
 * The assigned agent profile's launch settings, returned by `POST /api/agent/task`
 * under `agent_launch`.
 *
 * The server enforces `model`, the tool allowlist and the context budget itself at
 * `/api/agent/inference`, so those are not represented here. These are the fields that
 * only the agent runtime can apply at launch: the approval gate, the turn cap, and
 * whether the arm runs tool-less.
 */
data class AgentLaunchSettings(
    val agentProfile: String? = null,
    val approvalPolicy: String? = null,
    val maxSteps: Int? = null,
    val maxContextTokens: Int? = null,
    val disableTools: Boolean = false,
)

/** Result of provisioning a task: the (server-confirmed) task id plus its launch settings. */
data class AgentTaskInfo(
    val taskId: UUID,
    val frameworkVersion: String?,
    val model: String?,
    val launch: AgentLaunchSettings?,
)
