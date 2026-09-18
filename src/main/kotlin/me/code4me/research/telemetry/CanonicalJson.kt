package me.code4me.research.telemetry

import java.security.MessageDigest

/**
 * Deterministic canonical JSON plus SHA-256 helpers (Issue 06).
 *
 * The output is byte-for-byte compatible with the research backend's
 * `research.canonical.canonical_json`: keys are sorted by Unicode code point,
 * separators are compact (`,` / `:`), non-ASCII text is kept as UTF-8
 * (`ensure_ascii=False`), and control characters use the same escaping Python's
 * `json.dumps` produces.
 *
 * Equivalence is defined over the *parsed* structure, so two maps with the same
 * entries hash identically regardless of insertion order. This module is pure
 * JVM: it never imports the IntelliJ platform and never touches the network.
 */

/** Canonical JSON text for [value]. Sorted keys, compact separators, UTF-8 escaping. */
fun canonicalJson(value: Any?): String = CanonicalJsonWriter.write(value)

/** UTF-8 encoded [canonicalJson]. */
fun canonicalJsonBytes(value: Any?): ByteArray = canonicalJson(value).toByteArray(Charsets.UTF_8)

/** Lowercase hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val builder = StringBuilder(digest.size * 2)
    for (byte in digest) {
        builder.append(HEX[(byte.toInt() ushr 4) and 0x0F])
        builder.append(HEX[byte.toInt() and 0x0F])
    }
    return builder.toString()
}

/** Lowercase hex SHA-256 of the UTF-8 bytes of [text]. */
fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

private val HEX = "0123456789abcdef".toCharArray()

/**
 * Ascending Unicode code-point order, matching Python's string sort. Kotlin's
 * natural [String] order compares UTF-16 code units, which can disagree for
 * supplementary characters; canonical hashing must not depend on that.
 */
internal val CODE_POINT_ORDER: Comparator<String> =
    Comparator { left, right ->
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCodePoint = left.codePointAt(leftIndex)
            val rightCodePoint = right.codePointAt(rightIndex)
            if (leftCodePoint != rightCodePoint) {
                return@Comparator leftCodePoint - rightCodePoint
            }
            leftIndex += Character.charCount(leftCodePoint)
            rightIndex += Character.charCount(rightCodePoint)
        }
        (left.length - leftIndex) - (right.length - rightIndex)
    }

private object CanonicalJsonWriter {
    fun write(value: Any?): String {
        val builder = StringBuilder()
        append(builder, value)
        return builder.toString()
    }

    private fun append(
        builder: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> builder.append("null")
            is Boolean -> builder.append(if (value) "true" else "false")
            is String -> appendString(builder, value)
            is Char -> appendString(builder, value.toString())
            is Double -> appendFloatingPoint(builder, value)
            is Float -> appendFloatingPoint(builder, value.toDouble())
            is Number -> builder.append(value.toString())
            is Enum<*> -> appendString(builder, value.name)
            is Map<*, *> -> appendObject(builder, value)
            is Iterable<*> -> appendArray(builder, value)
            is Array<*> -> appendArray(builder, value.asIterable())
            is BooleanArray -> appendArray(builder, value.asIterable())
            is IntArray -> appendArray(builder, value.asIterable())
            is LongArray -> appendArray(builder, value.asIterable())
            else -> throw IllegalArgumentException(
                "Object of type ${value::class.java.name} is not canonical-JSON serializable",
            )
        }
    }

    private fun appendFloatingPoint(
        builder: StringBuilder,
        value: Double,
    ) {
        require(value.isFinite()) { "Non-finite numbers are not canonical-JSON serializable" }
        if (value == value.toLong().toDouble() && value <= Long.MAX_VALUE && value >= Long.MIN_VALUE) {
            // Python renders integral floats as "1.0"; keep that distinction.
            builder.append(value.toLong()).append(".0")
        } else {
            builder.append(value.toString())
        }
    }

    private fun appendObject(
        builder: StringBuilder,
        value: Map<*, *>,
    ) {
        val entries = value.entries.toList()
        val keys = entries.map { it.key?.toString() ?: "null" }
        val order =
            keys.withIndex().sortedWith { left, right ->
                val byKey = CODE_POINT_ORDER.compare(left.value, right.value)
                if (byKey != 0) byKey else left.index - right.index
            }
        builder.append('{')
        order.forEachIndexed { position, ordered ->
            if (position > 0) builder.append(',')
            appendString(builder, ordered.value)
            builder.append(':')
            append(builder, entries[ordered.index].value)
        }
        builder.append('}')
    }

    private fun appendArray(
        builder: StringBuilder,
        value: Iterable<*>,
    ) {
        builder.append('[')
        var position = 0
        for (item in value) {
            if (position > 0) builder.append(',')
            append(builder, item)
            position++
        }
        builder.append(']')
    }

    private fun appendString(
        builder: StringBuilder,
        value: String,
    ) {
        builder.append('"')
        for (char in value) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else ->
                    if (char < ' ') {
                        builder.append("\\u")
                        val code = char.code
                        builder.append(HEX[(code ushr 12) and 0x0F])
                        builder.append(HEX[(code ushr 8) and 0x0F])
                        builder.append(HEX[(code ushr 4) and 0x0F])
                        builder.append(HEX[code and 0x0F])
                    } else {
                        builder.append(char)
                    }
            }
        }
        builder.append('"')
    }
}

/**
 * Minimal, dependency-free JSON parser used to rehydrate canonical events from
 * the durable spool. Objects become [LinkedHashMap], arrays become [ArrayList],
 * integral numbers become [Long], and everything else maps to String/Boolean/null.
 */
object CanonicalJsonParser {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Unexpected trailing characters in JSON document" }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        require(value is Map<*, *>) { "Expected a JSON object but found ${value?.let { it::class.simpleName }}" }
        return value as Map<String, Any?>
    }

    private class Parser(private val text: String) {
        private var index: Int = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "Unexpected end of JSON document" }
            return when (val current = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(current == '-' || current.isDigit()) {
                        "Unexpected character '$current' at position $index"
                    }
                    readNumber()
                }
            }
        }

        private fun readLiteral(
            literal: String,
            value: Any?,
        ): Any? {
            require(text.startsWith(literal, index)) { "Invalid literal at position $index" }
            index += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            index++ // consume '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                require(index < text.length && text[index] == '"') {
                    "Expected object key at position $index"
                }
                val key = readString()
                skipWhitespace()
                require(index < text.length && text[index] == ':') { "Expected ':' at position $index" }
                index++
                result[key] = readValue()
                skipWhitespace()
                require(index < text.length) { "Unterminated object" }
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at position $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // consume '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                require(index < text.length) { "Unterminated array" }
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at position $index")
                }
            }
        }

        private fun readString(): String {
            require(text[index] == '"') { "Expected string at position $index" }
            index++
            val builder = StringBuilder()
            while (index < text.length) {
                val char = text[index]
                when {
                    char == '"' -> {
                        index++
                        return builder.toString()
                    }
                    char == '\\' -> {
                        index++
                        require(index < text.length) { "Unterminated escape sequence" }
                        when (val escaped = text[index]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(index + 4 < text.length) { "Truncated unicode escape" }
                                val hex = text.substring(index + 1, index + 5)
                                builder.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$escaped' at position $index")
                        }
                        index++
                    }
                    else -> {
                        builder.append(char)
                        index++
                    }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var isFloatingPoint = false
            if (index < text.length && text[index] == '.') {
                isFloatingPoint = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                isFloatingPoint = true
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val token = text.substring(start, index)
            if (!isFloatingPoint) {
                token.toLongOrNull()?.let { return it }
            }
            return token.toDouble()
        }
    }
}

/** Convenience alias so call sites read as a single canonical-JSON boundary. */
fun parseCanonicalJson(text: String): Any? = CanonicalJsonParser.parse(text)

/** Convenience alias for objects specifically. */
fun parseCanonicalJsonObject(text: String): Map<String, Any?> = CanonicalJsonParser.parseObject(text)
