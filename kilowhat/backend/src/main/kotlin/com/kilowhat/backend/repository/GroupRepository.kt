package com.kilowhat.backend.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Statement

data class GroupRow(
    val groupId: Int,
    val groupName: String,
)

@Repository
class GroupRepository(private val jdbc: JdbcTemplate) {

    fun findAllOrdered(): List<GroupRow> = jdbc.query(
        "SELECT group_id, group_name FROM `groups` ORDER BY group_name, group_id",
    ) { rs, _ ->
        GroupRow(
            groupId = rs.getInt("group_id"),
            groupName = rs.getString("group_name"),
        )
    }

    fun findById(groupId: Int): GroupRow? = jdbc.query(
        "SELECT group_id, group_name FROM `groups` WHERE group_id = ?",
        { rs, _ ->
            GroupRow(
                groupId = rs.getInt("group_id"),
                groupName = rs.getString("group_name"),
            )
        },
        groupId,
    ).firstOrNull()

    fun insert(groupName: String): Int {
        val keyHolder = GeneratedKeyHolder()
        jdbc.update({ connection ->
            connection.prepareStatement(
                "INSERT INTO `groups` (group_name) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, groupName)
            }
        }, keyHolder)
        return keyHolder.keyList.firstOrNull()?.values?.firstOrNull()?.let { (it as Number).toInt() }
            ?: error("Database did not return a generated group_id")
    }
}
