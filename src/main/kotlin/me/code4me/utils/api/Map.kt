package me.code4me.utils.api

import me.code4me.api.generated.infrastructure.Serializer.moshi

public fun <T> Map<String, Any>.mapsTo(clazz: Class<T>): T {
    // Convert map to JSON and then to target class using Moshi
    val adapter = moshi.adapter(clazz)
    val json = moshi.adapter(Map::class.java).toJson(this)
    return adapter.fromJson(json) ?: throw IllegalArgumentException("Failed to map to ${clazz.simpleName}")
}
