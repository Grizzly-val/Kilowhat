package com.kilowhat.backend.dto

import java.math.BigDecimal

data class CreateGroupRequest(
    val groupName: String? = null,
    val applianceRfids: List<String?>? = null,
)

data class GroupCreatedResponse(
    val groupId: Int,
    val groupName: String,
    val applianceCount: Int,
)

data class GroupSummaryItem(
    val groupId: Int?,
    val groupName: String,
    val totalWhConsumed: BigDecimal,
    val applianceCount: Int,
    val appliances: List<ApplianceSummaryItem>,
)

data class GroupsSummaryResponse(
    val totalWhConsumed: BigDecimal,
    val groups: List<GroupSummaryItem>,
)
