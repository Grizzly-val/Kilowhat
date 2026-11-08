package com.kilowhat.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairResponse(
    @SerialName("status") val status: String = "",
    @SerialName("outlet_id") val outletId: String = ""
)

@Serializable
data class UnpairResponse(
    @SerialName("status") val status: String = "",
    @SerialName("outlet_id") val outletId: String = ""
)

@Serializable
data class BleStreamReading(
    @SerialName("outlet_id") val outletId: String = "",
    @SerialName("active_appliance_rfid") val activeApplianceRfid: String = "NONE",
    @SerialName("watts") val watts: Double = 0.0,
    @SerialName("voltage") val voltage: Double = 0.0,
    @SerialName("current") val current: Double = 0.0
)
