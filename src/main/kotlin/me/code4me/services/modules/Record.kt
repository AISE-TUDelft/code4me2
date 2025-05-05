package me.code4me.services.modules

/**
 * Represents a Record returned by context or telemetry modules.
 */
data class Record(
    val type: Type,
    private val expanded: MutableMap<EntryKey, Any> = mutableMapOf()
) {

    /**
     * High-level category indicating the purpose of the record.
     */
    enum class Type {
        CONTEXT,
        TELEMETRY
    }

    /**
     * Represents a unique key used to store and retrieve typed values from a `Record`.
     * Each key is defined by a name and a type, ensuring type safety when accessing values.
     */
    data class EntryKey(
        val name: String,
        val type: Class<*>
    ) {
        override fun toString(): String = "Key(name='$name', type=${type.simpleName})"
    }

    fun containsKey(key: EntryKey): Boolean = expanded.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: EntryKey): T? {
        return expanded[key] as? T
    }

    fun put(key: EntryKey, value: Any?): Any? {
        return if (value == null) {
            expanded.remove(key)
        } else {
            require(key.type.isAssignableFrom(value::class.java)) {
                "Key $key does not accept value of type ${value::class.java}"
            }
            expanded.put(key, value)
        }
    }

    override fun toString(): String = "Record(type=$type, expanded=$expanded)"
}