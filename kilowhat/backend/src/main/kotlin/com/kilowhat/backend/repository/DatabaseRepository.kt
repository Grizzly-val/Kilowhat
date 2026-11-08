package com.kilowhat.backend.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class DatabaseRepository(private val jdbc: JdbcTemplate) {

    fun ping() {
        jdbc.queryForObject("SELECT 1", Int::class.java)
    }

    /** Names of the tables that exist in the connected schema, restricted to [names]. */
    fun existingTables(names: Collection<String>): Set<String> {
        val placeholders = names.joinToString(",") { "?" }
        return jdbc.queryForList(
            "SELECT LOWER(table_name) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND LOWER(table_name) IN ($placeholders)",
            String::class.java,
            *names.map { it.lowercase() }.toTypedArray(),
        ).toSet()
    }
}
