package me.code4me.utils.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.code4me.services.state.PrefSettings

/**
 * Extension function to serialize PrefSettings to a JSON-compatible dictionary.
 *
 * @return Map<String, Any> representing the serialized preference state
 */
fun PrefSettings.toSerializableMap(): Map<String, Any> {
    return mapOf(
        "store_context" to this.storeContext,
        "store_behavioral_telemetry" to this.storeBehavioralTelemetry,
        "store_contextual_telemetry" to this.storeContextualTelemetry,
        "enabled_modules" to this.enabledModules.toList(),
        "module_values" to this.moduleValues.toMap(),
    )
}

/**
 * Extension function to serialize PrefSettings to a JSON string.
 *
 * @return JSON string representation of the preference state
 */
fun PrefSettings.toJsonString(): String {
    val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    val jsonMap =
        buildMap<String, JsonElement> {
            put("store_context", JsonPrimitive(storeContext))
            put("store_behavioral_telemetry", JsonPrimitive(storeBehavioralTelemetry))
            put("store_contextual_telemetry", JsonPrimitive(storeContextualTelemetry))
            put("enabled_modules", Json.encodeToJsonElement(enabledModules.toList()))
            put("module_values", Json.encodeToJsonElement(moduleValues.toMap()))
        }

    return json.encodeToString(JsonObject.serializer(), JsonObject(jsonMap))
}

/**
 * Extension function to deserialize a map into PrefSettings.
 *
 * @param data Map containing the serialized preference data
 * @return PrefSettings instance populated with the deserialized data
 */
fun PrefSettings.fromSerializableMap(data: Map<String, Any>): PrefSettings {
    this.isBeingUpdated = true

    // Update application-wide settings
    (data["store_context"] as? Boolean)?.let { this.storeContext = it }
    (data["store_behavioral_telemetry"] as? Boolean)?.let { this.storeBehavioralTelemetry = it }
    (data["store_contextual_telemetry"] as? Boolean)?.let { this.storeContextualTelemetry = it }

    // Update enabled modules
    (data["enabled_modules"] as? List<*>)?.let { modulesList ->
        this.enabledModules.clear()
        modulesList.filterIsInstance<String>().forEach { moduleId ->
            this.enabledModules.add(moduleId)
        }
    }

    // Update module values
    (data["module_values"] as? Map<*, *>)?.let { moduleValuesMap ->
        this.moduleValues.clear()
        moduleValuesMap.entries.forEach { (key, value) ->
            if (key is String && value is String) {
                this.moduleValues[key] = value
            }
        }
    }

    this.lastUpdatedTimeStamp = System.currentTimeMillis()
    this.isBeingUpdated = false


    return this
}

/**
 * Extension function to deserialize a JSON string into PrefSettings.
 *
 * @param jsonString JSON string containing the serialized preference data
 * @return PrefSettings instance populated with the deserialized data
 * @throws kotlinx.serialization.SerializationException if the JSON is malformed
 */
fun PrefSettings.fromJsonString(jsonString: String): PrefSettings {
    val json = Json { ignoreUnknownKeys = true }
    val jsonObject = json.parseToJsonElement(jsonString).jsonObject

    // Update application-wide settings
    jsonObject["store_context"]?.jsonPrimitive?.boolean?.let { this.storeContext = it }
    jsonObject["store_behavioral_telemetry"]?.jsonPrimitive?.boolean?.let { this.storeBehavioralTelemetry = it }
    jsonObject["store_contextual_telemetry"]?.jsonPrimitive?.boolean?.let { this.storeContextualTelemetry = it }

    // Update enabled modules
    jsonObject["enabled_modules"]?.jsonArray?.let { modulesArray ->
        this.enabledModules.clear()
        modulesArray.forEach { element ->
            element.jsonPrimitive.content.let { moduleId ->
                this.enabledModules.add(moduleId)
            }
        }
    }

    // Update module values
    jsonObject["module_values"]?.jsonObject?.let { moduleValuesObject ->
        this.moduleValues.clear()
        moduleValuesObject.entries.forEach { (key, value) ->
            this.moduleValues[key] = value.jsonPrimitive.content
        }
    }

    return this
}

fun PrefSettings.updateFromMap(data: Map<String, Any>): PrefSettings {
    return this.fromSerializableMap(data)
}

/**
 * Creates a new PrefSettings instance from a serializable map.
 *
 * @param data Map containing the serialized preference data
 * @return New PrefSettings instance populated with the deserialized data
 */
fun createPrefSettingsFromMap(data: Map<String, Any>): PrefSettings {
    return PrefSettings().fromSerializableMap(data)
}

/**
 * Creates a new PrefSettings instance from a JSON string.
 *
 * @param jsonString JSON string containing the serialized preference data
 * @return New PrefSettings instance populated with the deserialized data
 * @throws kotlinx.serialization.SerializationException if the JSON is malformed
 */
fun createPrefSettingsFromJson(jsonString: String): PrefSettings {
    return PrefSettings().fromJsonString(jsonString)
}
