package com.kilowhat.backend.dto

import java.math.BigDecimal
import java.time.Instant

data class ApplianceSummaryItem(
    val applianceRfid: String,
    val customName: String?,
    val displayName: String,
    val totalWhConsumed: BigDecimal,
    val sessionCount: Long,
    val lastUsed: Instant?,
)

data class RegisterApplianceRequest(
    val applianceRfid: String? = null,
    val customName: String? = null,
    val groupId: Int? = null,
)

data class ApplianceNameResponse(
    val applianceRfid: String,
    val customName: String?,
    val displayName: String,
    val groupId: Int? = null,
    val groupName: String? = null,
)

data class ApplianceLookupResponse(
    val applianceRfid: String,
    val customName: String?,
    val displayName: String,
    val registered: Boolean,
)
