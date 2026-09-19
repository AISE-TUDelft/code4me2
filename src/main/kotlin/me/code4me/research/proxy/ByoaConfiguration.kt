package me.code4me.research.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.code4me.research.bootstrap.AgentConfigBindingRef
import me.code4me.research.bootstrap.ManifestAgentProfile
import me.code4me.research.telemetry.canonicalJson

/** The environment/argv translation of a frozen profile for one BYOA release. */
data class ByoaConfiguration(
    val env: Map<String, String> = emptyMap(),
    val args: List<String> = emptyList(),
)

/**
 * Apply a release's declared configuration contract (ISSUE-03 Path A).
 *
 * The release owns the external agent's vocabulary; the frozen profile supplies
 * the values. Unknown fields/transports and absent values are skipped here;
 * [missingByoaBindings] is what the launch path uses to fail closed, so a
 * profile whose values would not govern the agent never launches silently.
 */
fun applyByoaConfiguration(
    bindings: List<AgentConfigBindingRef>,
    profile: ManifestAgentProfile,
): ByoaConfiguration {
    val env = sortedMapOf<String, String>()
    val args = mutableListOf<String>()
    for (binding in bindings) {
        val raw =
            when (binding.field) {
                "model" -> profile.model.takeIf { it.isNotBlank() }
                "temperature" -> profile.temperature?.toString()
                "max_steps" -> profile.maxSteps.toString()
                "approval_policy" -> profile.approvalPolicy.takeIf { it.isNotBlank() }
                "tools" ->
                    parseToolList(profile.toolsJson)
                        .takeIf { it.isNotEmpty() }
                        ?.let { renderTools(it, binding.format) }
                else -> null
            } ?: continue
        val value = binding.valueMap[raw] ?: raw
        when (binding.transport) {
            "env" -> env[binding.key] = value
            "arg" -> {
                args += binding.key
                args += value
            }
        }
    }
    return ByoaConfiguration(env = env.toMap(), args = args)
}

/**
 * The frozen profile fields the release cannot translate.
 *
 * A non-empty result means the study would run an agent whose declared
 * configuration does not govern it; the caller must refuse to launch.
 */
fun missingByoaBindings(
    bindings: List<AgentConfigBindingRef>,
    profile: ManifestAgentProfile,
): List<String> {
    // Only transports the launch path can actually apply count as mapped; an
    // unknown transport must fail closed rather than look covered.
    val mapped =
        bindings
            .filter { it.transport == "env" || it.transport == "arg" }
            .map { it.field }
            .toSet()
    val required =
        buildList {
            if (profile.model.isNotBlank()) add("model")
            if (profile.temperature != null) add("temperature")
            if (profile.maxSteps > 0) add("max_steps")
            if (parseToolList(profile.toolsJson).isNotEmpty()) add("tools")
            if (profile.approvalPolicy.isNotBlank()) add("approval_policy")
        }
    return required.filter { it !in mapped }
}

private fun parseToolList(toolsJson: String): List<String> =
    try {
        Json.parseToJsonElement(toolsJson)
            .jsonArray
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    } catch (_: Exception) {
        emptyList()
    }

private fun renderTools(tools: List<String>, format: String): String =
    if (format.equals("json", ignoreCase = true)) {
        canonicalJson(tools)
    } else {
        tools.joinToString(",")
    }
