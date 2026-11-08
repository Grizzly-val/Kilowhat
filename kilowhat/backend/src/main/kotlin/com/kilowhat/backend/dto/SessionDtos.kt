package com.kilowhat.backend.dto

import java.math.BigDecimal
import java.time.Instant

/**
 * Body POSTed by the hardware simulator at the end of each appliance session.
 * Every field is nullable so that missing fields produce a clear 400 instead of a parse error.
 */
data class SessionRequest(
    val outletId: String? = null,
    val applianceRfid: String? = null,
    val startTimestamp: String? = null,
    val endTimestamp: String? = null,
    val totalWhConsumed: BigDecimal? = null,
)

data class SessionSavedResponse(
    val sessionId: Long,
    val status: String = "saved",
)

data class SessionItem(
    val sessionId: Long,
    val outletId: String?,
    val outletName: String?,
    val startTimestamp: Instant,
    val endTimestamp: Instant,
    val totalWhConsumed: BigDecimal,
)

data class ApplianceSessionsResponse(
    val applianceRfid: String,
    val displayName: String,
    val totalWhConsumed: BigDecimal,
    val sessions: List<SessionItem>,
)
