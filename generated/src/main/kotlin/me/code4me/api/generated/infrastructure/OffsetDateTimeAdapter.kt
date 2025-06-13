package me.code4me.api.generated.infrastructure

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class OffsetDateTimeAdapter {
    @ToJson
    fun toJson(value: OffsetDateTime): String {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value)
    }

    @FromJson
    fun fromJson(value: String): OffsetDateTime {
        return try {
            // First, try to parse as ISO_OFFSET_DATE_TIME (with timezone)
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: DateTimeParseException) {
            try {
                // If that fails, try to parse as ISO_LOCAL_DATE_TIME and assume UTC
                val localDateTime = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                localDateTime.atOffset(ZoneOffset.UTC)
            } catch (e2: DateTimeParseException) {
                // If both fail, throw the original exception with more context
                throw DateTimeParseException(
                    "Unable to parse datetime string '$value'. Expected format with timezone offset (e.g., '2023-01-01T12:00:00+00:00') or local datetime (e.g., '2023-01-01T12:00:00')",
                    value,
                    0,
                    e
                )
            }
        }
    }
}