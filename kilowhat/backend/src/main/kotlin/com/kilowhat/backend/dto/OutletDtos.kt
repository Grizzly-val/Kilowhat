package com.kilowhat.backend.dto

data class RegisterOutletRequest(
    val outletId: String? = null,
    val customName: String? = null,
)

data class OutletNameResponse(
    val outletId: String,
    val customName: String?,
    val displayName: String,
)

data class OutletLookupResponse(
    val outletId: String,
    val customName: String?,
    val displayName: String,
    val registered: Boolean,
)

data class HealthResponse(
    val status: String,
    val database: String,
)
