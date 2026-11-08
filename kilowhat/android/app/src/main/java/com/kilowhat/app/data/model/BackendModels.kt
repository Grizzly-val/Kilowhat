package com.kilowhat.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApplianceSummaryDto(
    @SerialName("appliance_rfid") val applianceRfid: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("total_wh_consumed") val totalWhConsumed: Double = 0.0,
    @SerialName("session_count") val sessionCount: Long = 0,
    @SerialName("last_used") val lastUsed: String? = null
)

@Serializable
data class SessionItemDto(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("outlet_id") val outletId: String? = null,
    @SerialName("outlet_name") val outletName: String? = null,
    @SerialName("start_timestamp") val startTimestamp: String,
    @SerialName("end_timestamp") val endTimestamp: String,
    @SerialName("total_wh_consumed") val totalWhConsumed: Double = 0.0
)

@Serializable
data class ApplianceSessionsResponseDto(
    @SerialName("appliance_rfid") val applianceRfid: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("total_wh_consumed") val totalWhConsumed: Double = 0.0,
    @SerialName("sessions") val sessions: List<SessionItemDto> = emptyList()
)

@Serializable
data class GroupSummaryDto(
    @SerialName("group_id") val groupId: Int? = null,
    @SerialName("group_name") val groupName: String,
    @SerialName("total_wh_consumed") val totalWhConsumed: Double = 0.0,
    @SerialName("appliance_count") val applianceCount: Int = 0,
    @SerialName("appliances") val appliances: List<ApplianceSummaryDto> = emptyList()
)

@Serializable
data class GroupsSummaryResponseDto(
    @SerialName("total_wh_consumed") val totalWhConsumed: Double = 0.0,
    @SerialName("groups") val groups: List<GroupSummaryDto> = emptyList()
)

@Serializable
data class CreateGroupRequestDto(
    @SerialName("group_name") val groupName: String,
    @SerialName("appliance_rfids") val applianceRfids: List<String>
)

@Serializable
data class GroupCreatedResponseDto(
    @SerialName("group_id") val groupId: Int,
    @SerialName("group_name") val groupName: String,
    @SerialName("appliance_count") val applianceCount: Int = 0
)

@Serializable
data class RegisterApplianceRequestDto(
    @SerialName("appliance_rfid") val applianceRfid: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("group_id") val groupId: Int? = null
)

@Serializable
data class ApplianceNameResponseDto(
    @SerialName("appliance_rfid") val applianceRfid: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("group_id") val groupId: Int? = null,
    @SerialName("group_name") val groupName: String? = null
)

@Serializable
data class ApplianceLookupResponseDto(
    @SerialName("appliance_rfid") val applianceRfid: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("registered") val registered: Boolean = false
)

@Serializable
data class RegisterOutletRequestDto(
    @SerialName("outlet_id") val outletId: String,
    @SerialName("custom_name") val customName: String
)

@Serializable
data class OutletNameResponseDto(
    @SerialName("outlet_id") val outletId: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class OutletLookupResponseDto(
    @SerialName("outlet_id") val outletId: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("registered") val registered: Boolean = false
)

@Serializable
data class ApiErrorDto(
    @SerialName("error") val error: String? = null,
    @SerialName("message") val message: String? = null
)
