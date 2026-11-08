package com.kilowhat.app.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormatters {

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy • h:mm:ss a", Locale.US)

    private val timeOnlyFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)

    private val dateOnlyFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /**
     * Converts an ISO-8601 UTC string (e.g. `2026-11-07T13:57:16Z`) into a local-time range
     * string for the user's current timezone.
     */
    fun formatSessionRange(
        startIso: String,
        endIso: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val startInstant = parseInstantOrNull(startIso) ?: return "$startIso – $endIso"
        val endInstant = parseInstantOrNull(endIso) ?: return "$startIso – $endIso"

        val startLocal = startInstant.atZone(zoneId)
        val endLocal = endInstant.atZone(zoneId)

        val sameDay = startLocal.toLocalDate() == endLocal.toLocalDate()
        return if (sameDay) {
            "${dateOnlyFormatter.format(startLocal)} • ${timeOnlyFormatter.format(startLocal)} – ${timeOnlyFormatter.format(endLocal)}"
        } else {
            "${dateTimeFormatter.format(startLocal)} – ${dateTimeFormatter.format(endLocal)}"
        }
    }

    /**
     * Computes a human-readable duration between two ISO-8601 timestamps (e.g. `3s`, `4m 12s`, `1h 05m`).
     */
    fun formatDuration(startIso: String, endIso: String): String {
        val startInstant = parseInstantOrNull(startIso) ?: return "—"
        val endInstant = parseInstantOrNull(endIso) ?: return "—"
        val totalSeconds = Duration.between(startInstant, endInstant).seconds.coerceAtLeast(0)

        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds)
            minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            else -> String.format(Locale.US, "%ds", seconds)
        }
    }

    fun formatLastUsed(iso: String?, zoneId: ZoneId = ZoneId.systemDefault()): String? {
        if (iso.isNullOrBlank()) return null
        val instant = parseInstantOrNull(iso) ?: return null
        return dateTimeFormatter.format(instant.atZone(zoneId))
    }

    fun formatWh(wh: Double): String = String.format(Locale.US, "%.3f", wh)

    private fun parseInstantOrNull(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: Exception) {
        null
    }
}
