package com.kilowhat.app.data.repository

import com.kilowhat.app.data.model.ApplianceLookupResponseDto
import com.kilowhat.app.data.model.ApplianceNameResponseDto
import com.kilowhat.app.data.model.ApplianceSessionsResponseDto
import com.kilowhat.app.data.model.ApplianceSummaryDto
import com.kilowhat.app.data.model.BleStreamReading
import com.kilowhat.app.data.model.CreateGroupRequestDto
import com.kilowhat.app.data.model.GroupCreatedResponseDto
import com.kilowhat.app.data.model.GroupsSummaryResponseDto
import com.kilowhat.app.data.model.OutletLookupResponseDto
import com.kilowhat.app.data.model.OutletNameResponseDto
import com.kilowhat.app.data.model.PairResponse
import com.kilowhat.app.data.model.RegisterApplianceRequestDto
import com.kilowhat.app.data.model.RegisterOutletRequestDto
import com.kilowhat.app.data.model.UnpairResponse
import com.kilowhat.app.data.network.ApiResult
import com.kilowhat.app.data.network.BackendApi
import com.kilowhat.app.data.network.NetworkModule
import com.kilowhat.app.data.network.SimulatorApi
import com.kilowhat.app.data.network.safeApiCall

class KilowhatRepository(
    private val simulatorApi: SimulatorApi = NetworkModule.simulatorApi,
    private val backendApi: BackendApi = NetworkModule.backendApi
) {
    private val json = NetworkModule.json

    // ---- Simulator calls (Port 8001) ----

    suspend fun pairSimulator(): ApiResult<PairResponse> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the simulator. Is the Python script running?"
    ) {
        simulatorApi.pair()
    }

    suspend fun unpairSimulator(): ApiResult<UnpairResponse> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the simulator to unpair."
    ) {
        simulatorApi.unpair()
    }

    suspend fun pollBleStream(): ApiResult<BleStreamReading> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Lost connection to the outlet."
    ) {
        simulatorApi.getBleStream()
    }

    // ---- Backend calls (Port 8080) ----

    suspend fun getGroupsSummary(): ApiResult<GroupsSummaryResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the Kilowhat backend. Is the server running on port 8080?"
    ) {
        backendApi.getGroupsSummary()
    }

    suspend fun createGroup(
        groupName: String,
        applianceRfids: List<String>
    ): ApiResult<GroupCreatedResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the backend to create the group."
    ) {
        backendApi.createGroup(
            CreateGroupRequestDto(
                groupName = groupName.trim(),
                applianceRfids = applianceRfids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            )
        )
    }

    suspend fun getAppliancesSummary(): ApiResult<List<ApplianceSummaryDto>> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the Kilowhat backend. Is the server running on port 8080?"
    ) {
        backendApi.getAppliancesSummary()
    }

    suspend fun getApplianceSessions(rfid: String): ApiResult<ApplianceSessionsResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not load session history. Check that the backend is running."
    ) {
        backendApi.getApplianceSessions(rfid)
    }

    suspend fun registerAppliance(rfid: String, customName: String): ApiResult<ApplianceNameResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the backend to save the appliance name."
    ) {
        backendApi.registerAppliance(
            RegisterApplianceRequestDto(
                applianceRfid = rfid.trim(),
                customName = customName.trim()
            )
        )
    }

    suspend fun registerOutlet(outletId: String, customName: String): ApiResult<OutletNameResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not reach the backend to save the outlet name."
    ) {
        backendApi.registerOutlet(
            RegisterOutletRequestDto(
                outletId = outletId.trim(),
                customName = customName.trim()
            )
        )
    }

    suspend fun lookupAppliance(rfid: String): ApiResult<ApplianceLookupResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not resolve appliance name."
    ) {
        backendApi.getAppliance(rfid)
    }

    suspend fun lookupOutlet(outletId: String): ApiResult<OutletLookupResponseDto> = safeApiCall(
        json = json,
        fallbackNetworkMessage = "Could not resolve outlet name."
    ) {
        backendApi.getOutlet(outletId)
    }
}
