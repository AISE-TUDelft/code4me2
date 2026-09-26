package me.code4me.research.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.code4me.research.bootstrap.AgentConfigBindingRef
import me.code4me.research.bootstrap.ManifestAgentProfile
import me.code4me.research.telemetry.canonicalJson

/**
 * The environment/argv translation of a frozen profile for one BYOA release.
 *
 * [credentialEnvKey] is the environment variable the release binds the
 * research inference credential to. Only the key travels here: the credential
 * value is never rendered into [env] or [args] (both end up in the ACP entry),
 * it reaches the agent through the proxy's owner-only credential file.
 */
data class ByoaConfiguration(
    val env: Map<String, String> = emptyMap(),
    val args: List<String> = emptyList(),
    val credentialEnvKey: String? = null,
)

/**
 * The non-secret runtime values the plugin fills for a BYOA agent that calls
 * the research inference gateway (Goose): the server origin it bootstrapped
 * from, the manifest's relative gateway path, the server's provider-kind
 * vocabulary (translated by the release's `value_map`) and a plugin-owned
 * state directory that isolates the agent's own configuration.
 */
data class ByoaRuntimeValues(
    val host: String,
    val basePath: String,
    val providerKind: String,
    val stateDir: String,
)

/** Release binding fields the plugin fills at runtime (never profile fields). */
const val BYOA_FIELD_GATEWAY_HOST: String = "inference_gateway_host"
const val BYOA_FIELD_GATEWAY_BASE_PATH: String = "inference_gateway_base_path"
const val BYOA_FIELD_GATEWAY_CREDENTIAL: String = "inference_gateway_credential"
const val BYOA_FIELD_PROVIDER_KIND: String = "provider_kind"
const val BYOA_FIELD_STATE_DIR: String = "state_dir"

/** Every runtime field a gateway-bound release must translate. */
val BYOA_RUNTIME_FIELDS: List<String> =
    listOf(
        BYOA_FIELD_GATEWAY_HOST,
        BYOA_FIELD_GATEWAY_BASE_PATH,
        BYOA_FIELD_GATEWAY_CREDENTIAL,
        BYOA_FIELD_PROVIDER_KIND,
        BYOA_FIELD_STATE_DIR,
    )

/**
 * Apply a release's declared configuration contract (ISSUE-03 Path A).
 *
 * The release owns the external agent's vocabulary; the frozen [profile]
 * supplies the profile values and [runtime] the gateway values. Unknown
 * fields/transports and absent values are skipped here; [missingByoaBindings]
 * and [credentialBindingViolation] are what the launch path uses to fail
 * closed, so a profile whose values would not govern the agent never launches
 * silently. Two fields are special: the credential is never rendered (only its
 * env key is reported), and `provider_kind` is rendered only through the
 * release's `value_map` (the server vocabulary is never handed to the agent).
 */
fun applyByoaConfiguration(
    bindings: List<AgentConfigBindingRef>,
    profile: ManifestAgentProfile?,
    runtime: ByoaRuntimeValues? = null,
): ByoaConfiguration {
    val env = sortedMapOf<String, String>()
    val args = mutableListOf<String>()
    var credentialEnvKey: String? = null
    for (binding in bindings) {
        if (binding.field == BYOA_FIELD_GATEWAY_CREDENTIAL) {
            // The value is delivered through the owner-only credential file;
            // argv and the ACP entry (which persist on disk) never carry it.
            if (runtime != null && binding.transport == "env") credentialEnvKey = binding.key
            continue
        }
        val raw =
            when (binding.field) {
                "model" -> profile?.model?.takeIf { it.isNotBlank() }
                "temperature" -> profile?.temperature?.toString()
                "max_steps" -> profile?.maxSteps?.toString()
                "approval_policy" -> profile?.approvalPolicy?.takeIf { it.isNotBlank() }
                "tools" ->
                    profile
                        ?.let { parseToolList(it.toolsJson) }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { renderTools(it, binding.format) }
                BYOA_FIELD_GATEWAY_HOST -> runtime?.host
                BYOA_FIELD_GATEWAY_BASE_PATH -> runtime?.basePath
                BYOA_FIELD_PROVIDER_KIND -> runtime?.providerKind
                BYOA_FIELD_STATE_DIR -> runtime?.stateDir
                else -> null
            } ?: continue
        val value =
            if (binding.field == BYOA_FIELD_PROVIDER_KIND) {
                // Untranslated: skipped here and reported by missingByoaBindings.
                binding.valueMap[raw] ?: continue
            } else {
                binding.valueMap[raw] ?: raw
            }
        when (binding.transport) {
            "env" -> env[binding.key] = value
            "arg" -> {
                args += binding.key
                args += value
            }
        }
    }
    return ByoaConfiguration(env = env.toMap(), args = args, credentialEnvKey = credentialEnvKey)
}

/**
 * The frozen profile fields and gateway runtime fields the release cannot
 * translate.
 *
 * A non-empty result means the study would run an agent whose declared
 * configuration does not govern it; the caller must refuse to launch. The
 * runtime fields are required only when [runtime] is present (the manifest
 * carries an inference gateway). `provider_kind` counts as translated only
 * when the binding's `value_map` covers the server's value, and the credential
 * only through the `env` transport (argv is world-readable).
 */
fun missingByoaBindings(
    bindings: List<AgentConfigBindingRef>,
    profile: ManifestAgentProfile?,
    runtime: ByoaRuntimeValues? = null,
): List<String> {
    val required =
        buildList {
            if (profile != null) {
                if (profile.model.isNotBlank()) add("model")
                if (profile.temperature != null) add("temperature")
                if (profile.maxSteps > 0) add("max_steps")
                if (parseToolList(profile.toolsJson).isNotEmpty()) add("tools")
                if (profile.approvalPolicy.isNotBlank()) add("approval_policy")
            }
            if (runtime != null) addAll(BYOA_RUNTIME_FIELDS)
        }
    return required.filter { field -> !isMappedByoaField(field, bindings, runtime) }
}

/**
 * Why the release's inference-credential binding cannot be honoured, or
 * `null` when it can. Both directions fail closed: a manifest that carries a
 * gateway needs an `env` credential binding whose key no other binding sets,
 * and a release that binds a credential must not launch without a gateway
 * (the agent would fall back to the participant's own provider settings).
 */
fun credentialBindingViolation(
    bindings: List<AgentConfigBindingRef>,
    gatewayPresent: Boolean,
): String? {
    val credential = bindings.firstOrNull { it.field == BYOA_FIELD_GATEWAY_CREDENTIAL }
    if (!gatewayPresent) {
        return credential?.let {
            "the release binds the study AI credential to '${it.key}' but the study manifest carries no " +
                "inference gateway; refusing to launch an agent without its provider credential"
        }
    }
    if (credential == null) {
        return "the release declares no $BYOA_FIELD_GATEWAY_CREDENTIAL binding, so the study AI credential " +
            "cannot reach the agent; refusing to launch"
    }
    if (credential.transport != "env") {
        return "the $BYOA_FIELD_GATEWAY_CREDENTIAL binding must use the env transport (transport " +
            "'${credential.transport}' would expose the credential on the command line); refusing to launch"
    }
    val collision =
        bindings.any { it !== credential && it.transport == "env" && it.key == credential.key }
    if (collision) {
        return "the $BYOA_FIELD_GATEWAY_CREDENTIAL env key '${credential.key}' is also set by another binding; " +
            "refusing to launch"
    }
    return null
}

/**
 * Whether [field] is covered by a binding the launch path can actually apply.
 * Only the `env`/`arg` transports count as mapped; an unknown transport must
 * fail closed rather than look covered.
 */
private fun isMappedByoaField(
    field: String,
    bindings: List<AgentConfigBindingRef>,
    runtime: ByoaRuntimeValues?,
): Boolean =
    bindings.any { binding ->
        binding.field == field &&
            when (field) {
                BYOA_FIELD_GATEWAY_CREDENTIAL -> binding.transport == "env"
                BYOA_FIELD_PROVIDER_KIND ->
                    (binding.transport == "env" || binding.transport == "arg") &&
                        runtime != null &&
                        !binding.valueMap[runtime.providerKind].isNullOrBlank()
                else -> binding.transport == "env" || binding.transport == "arg"
            }
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
