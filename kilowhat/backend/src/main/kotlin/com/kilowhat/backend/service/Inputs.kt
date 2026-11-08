package com.kilowhat.backend.service

import com.kilowhat.backend.error.ValidationException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Input normalisation shared by the services. */
internal object Inputs {

    /** Timestamps without an offset are assumed to come from a device in the Philippines. */
    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Manila")

    /** Trims [value] and rejects it when missing, blank or longer than [maxLength]. */
    fun requiredText(value: String?, field: String, maxLength: Int): String {
        val trimmed = value?.trim() ?: throw ValidationException("'$field' is required.")
        if (trimmed.isEmpty()) throw ValidationException("'$field' must not be blank.")
        if (trimmed.length > maxLength) {
            throw ValidationException("'$field' must be at most $maxLength characters.")
        }
        return trimmed
    }

    /**
     * Parses an ISO-8601 timestamp into an [Instant].
     * Accepts `...+08:00`, `...Z` or no offset (treated as Asia/Manila).
     */
    fun parseInstant(value: String?, field: String): Instant {
        val raw = requiredText(value, field, maxLength = 64)
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeException) {
            try {
                LocalDateTime.parse(raw).atZone(DEFAULT_ZONE).toInstant()
            } catch (_: DateTimeException) {
                throw ValidationException("'$field' must be an ISO-8601 timestamp, e.g. 2026-11-07T21:57:16.968+08:00.")
            }
        }
    }

    /**
     * Converts an [Instant] to a UTC wall-clock value for a `DATETIME` column.
     * Fractional seconds are truncated because the schema's `DATETIME` columns have whole-second precision.
     */
    fun toUtcLocalDateTime(instant: Instant): LocalDateTime =
        LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC)

    /** `custom_name` when it is set, otherwise the raw hardware identifier. */
    fun displayName(customName: String?, rawId: String): String =
        customName?.takeIf { it.isNotBlank() } ?: rawId
}
