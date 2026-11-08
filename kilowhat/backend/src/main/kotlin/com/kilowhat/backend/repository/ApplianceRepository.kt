package com.kilowhat.backend.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant

/** One row of the per-appliance aggregate, before display-name resolution. */
data class ApplianceSummaryRow(
    val applianceRfid: String,
    val customName: String?,
    val groupId: Int?,
    val groupName: String?,
    val totalWh: BigDecimal,
    val sessionCount: Long,
    val lastUsed: Instant?,
)

/** A row of a name-mapping table (`appliances` or `outlets`). */
data class NameMapping(val id: String, val customName: String?)

/** An `appliances` row joined with its optional `` `groups` `` row. */
data class ApplianceWithGroupRow(
    val applianceRfid: String,
    val customName: String?,
    val groupId: Int?,
    val groupName: String?,
)

@Repository
class ApplianceRepository(private val jdbc: JdbcTemplate) {

    fun summary(): List<ApplianceSummaryRow> = jdbc.query(
        """
        SELECT r.appliance_rfid,
               a.custom_name,
               g.group_id,
               g.group_name,
               COALESCE(SUM(s.total_wh_consumed), 0) AS total_wh_consumed,
               COUNT(s.session_id) AS session_count,
               MAX(s.end_timestamp) AS last_used
        FROM (SELECT appliance_rfid FROM sessions WHERE appliance_rfid IS NOT NULL
              UNION
              SELECT appliance_rfid FROM appliances) r
        LEFT JOIN appliances a ON a.appliance_rfid = r.appliance_rfid
        LEFT JOIN `groups` g ON g.group_id = a.group_id
        LEFT JOIN sessions s ON s.appliance_rfid = r.appliance_rfid
        GROUP BY r.appliance_rfid, a.custom_name, g.group_id, g.group_name
        ORDER BY total_wh_consumed DESC, r.appliance_rfid
        """.trimIndent(),
    ) { rs, _ ->
        ApplianceSummaryRow(
            applianceRfid = rs.getString("appliance_rfid"),
            customName = rs.getString("custom_name"),
            groupId = rs.nullableInt("group_id"),
            groupName = rs.getString("group_name"),
            totalWh = rs.getBigDecimal("total_wh_consumed"),
            sessionCount = rs.getLong("session_count"),
            lastUsed = rs.utcInstant("last_used"),
        )
    }

    fun find(applianceRfid: String): NameMapping? = jdbc.query(
        "SELECT appliance_rfid, custom_name FROM appliances WHERE appliance_rfid = ?",
        { rs, _ -> NameMapping(rs.getString("appliance_rfid"), rs.getString("custom_name")) },
        applianceRfid,
    ).firstOrNull()

    fun findWithGroup(applianceRfid: String): ApplianceWithGroupRow? = jdbc.query(
        """
        SELECT a.appliance_rfid, a.custom_name, g.group_id, g.group_name
        FROM appliances a
        LEFT JOIN `groups` g ON g.group_id = a.group_id
        WHERE a.appliance_rfid = ?
        """.trimIndent(),
        { rs, _ ->
            ApplianceWithGroupRow(
                applianceRfid = rs.getString("appliance_rfid"),
                customName = rs.getString("custom_name"),
                groupId = rs.nullableInt("group_id"),
                groupName = rs.getString("group_name"),
            )
        },
        applianceRfid,
    ).firstOrNull()

    fun upsertRegistration(applianceRfid: String, customName: String?, groupId: Int?) {
        jdbc.update(
            """
            INSERT INTO appliances (appliance_rfid, custom_name, group_id) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
                custom_name = COALESCE(VALUES(custom_name), custom_name),
                group_id = COALESCE(VALUES(group_id), group_id)
            """.trimIndent(),
            applianceRfid,
            customName,
            groupId,
        )
    }

    fun assignGroup(applianceRfid: String, groupId: Int) {
        jdbc.update(
            """
            INSERT INTO appliances (appliance_rfid, group_id) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE group_id = VALUES(group_id)
            """.trimIndent(),
            applianceRfid,
            groupId,
        )
    }
}

internal fun ResultSet.nullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}
