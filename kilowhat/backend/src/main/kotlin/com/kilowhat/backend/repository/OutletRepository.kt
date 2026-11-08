package com.kilowhat.backend.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class OutletRepository(private val jdbc: JdbcTemplate) {

    /** Makes sure the outlet row exists so the `sessions.outlet_id` foreign key is satisfied. Never touches names. */
    fun ensureExists(outletId: String) {
        jdbc.update("INSERT IGNORE INTO outlets (outlet_id) VALUES (?)", outletId)
    }

    fun find(outletId: String): NameMapping? = jdbc.query(
        "SELECT outlet_id, custom_name FROM outlets WHERE outlet_id = ?",
        { rs, _ -> NameMapping(rs.getString("outlet_id"), rs.getString("custom_name")) },
        outletId,
    ).firstOrNull()

    fun upsert(outletId: String, customName: String) {
        jdbc.update(
            """
            INSERT INTO outlets (outlet_id, custom_name) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE custom_name = VALUES(custom_name)
            """.trimIndent(),
            outletId,
            customName,
        )
    }
}
