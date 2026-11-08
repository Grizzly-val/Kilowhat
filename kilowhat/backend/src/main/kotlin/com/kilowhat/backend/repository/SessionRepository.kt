package com.kilowhat.backend.repository

import com.kilowhat.backend.dto.SessionItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Statement
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A session ready to be stored: timestamps are already UTC wall-clock values, energy already rounded. */
data class NewSession(
    val outletId: String,
    val applianceRfid: String,
    val startUtc: LocalDateTime,
    val endUtc: LocalDateTime,
    val totalWh: BigDecimal,
)

@Repository
class SessionRepository(private val jdbc: JdbcTemplate) {

    fun insert(session: NewSession): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbc.update({ connection ->
            connection.prepareStatement(
                """
                INSERT INTO sessions (outlet_id, appliance_rfid, start_timestamp, end_timestamp, total_wh_consumed)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, session.outletId)
                setString(2, session.applianceRfid)
                setObject(3, session.startUtc)
                setObject(4, session.endUtc)
                setBigDecimal(5, session.totalWh)
            }
        }, keyHolder)
        return keyHolder.keyList.firstOrNull()?.values?.firstOrNull()?.let { (it as Number).toLong() }
            ?: error("Database did not return a generated session_id")
    }

    fun findByAppliance(applianceRfid: String): List<SessionItem> = jdbc.query(
        """
        SELECT s.session_id, s.outlet_id, o.custom_name AS outlet_name,
               s.start_timestamp, s.end_timestamp, s.total_wh_consumed
        FROM sessions s
        LEFT JOIN outlets o ON o.outlet_id = s.outlet_id
        WHERE s.appliance_rfid = ?
        ORDER BY s.start_timestamp DESC, s.session_id DESC
        """.trimIndent(),
        { rs, _ ->
            SessionItem(
                sessionId = rs.getLong("session_id"),
                outletId = rs.getString("outlet_id"),
                outletName = rs.getString("outlet_name")?.takeIf { it.isNotBlank() },
                startTimestamp = rs.utcInstant("start_timestamp")!!,
                endTimestamp = rs.utcInstant("end_timestamp")!!,
                totalWhConsumed = rs.getBigDecimal("total_wh_consumed"),
            )
        },
        applianceRfid,
    )
}

/** DATETIME columns hold UTC wall-clock values; read them back as instants. */
internal fun ResultSet.utcInstant(column: String) =
    getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
