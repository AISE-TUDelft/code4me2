package me.code4me.utils.record

/**
 * Represents a Record returned by context or telemetry modules.
 */
data class Record(
    val type: Type,
    internal val expanded: MutableMap<EntryKey, Any> = mutableMapOf(),
) {
    /**
     * High-level category indicating the purpose of the record.
     */
    enum class Type {
        CONTEXT,
        BEHAVIORALTELEMETRY,
        CONTEXTUALTELEMETRY,
    }

    /**
     * Represents a unique key used to store and retrieve typed values from a `Record`.
     * Each key is defined by a name and a type, ensuring type safety when accessing values.
     */
    data class EntryKey(
        val name: String,
        val type: Class<*>,
    ) {
        override fun toString(): String = "Key(name='$name', type=${type.simpleName})"
    }

    fun containsKey(key: EntryKey): Boolean = expanded.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: EntryKey): T? {
        return expanded[key] as? T
    }

    /**
     * Adds, updates or removes a value in the `Record` associated with the specified key.
     * If the value is `null`, the key is removed from the `Record`.
     *
     * @param key The `EntryKey` used to identify the value.
     * @param value The value to associate with the key. If `null`, the key is removed.
     * @return The previous value associated with the key, or `null` if there was no mapping.
     * @throws IllegalArgumentException If the value's type does not match the key's type.
     */
    fun put(
        key: EntryKey,
        value: Any?,
    ): Any? {
        return if (value == null) {
            expanded.remove(key)
        } else {
            require(key.type.isAssignableFrom(value::class.java)) {
                "Key $key does not accept value of type ${value::class.java}"
            }
            expanded.put(key, value)
        }
    }

    /**
     * Creates a strongly-typed `EntryKey` for use in a `Record`.
     *
     * @param name The name of the key.
     * @return An `EntryKey` instance with the specified name and type.
     * @param T The type of the value associated with the key.
     */
    companion object {
        inline fun <reified T : Any> key(name: String): EntryKey {
            return EntryKey(name, T::class.java)
        }
    }

    /**
     * Returns a string representation of the Record.
     * @return A string representation of the Record.
     */
    override fun toString(): String = "Record(type=$type, expanded=$expanded)"
}

/**
 * Converts the Record to a map where keys are the names of the EntryKeys.
 * This is useful for serialization or when you need a simple key-value representation.
 *
 * @return A map representation of the Record with string keys.
 */
fun Record.toMap(): Map<String, Any> {
    return expanded.mapKeys { it.key.name }
}

fun List<Record>.aggregateByType(): Map<Record.Type, Record> {
    return this.groupBy { it.type }
        .mapValues { (_, records) ->
            // Combine all records of the same type into a single record
            val combinedRecord = Record(records.first().type)
            records.forEach { record ->
                record.expanded.forEach { (key, value) ->
                    combinedRecord.put(key, value)
                }
            }
            combinedRecord
        }
}
