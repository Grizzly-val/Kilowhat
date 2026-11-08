package com.kilowhat.app.data.network

import com.kilowhat.app.data.model.ApplianceLookupResponseDto
import com.kilowhat.app.data.model.ApplianceNameResponseDto
import com.kilowhat.app.data.model.ApplianceSessionsResponseDto
import com.kilowhat.app.data.model.ApplianceSummaryDto
import com.kilowhat.app.data.model.CreateGroupRequestDto
import com.kilowhat.app.data.model.GroupCreatedResponseDto
import com.kilowhat.app.data.model.GroupsSummaryResponseDto
import com.kilowhat.app.data.model.OutletLookupResponseDto
import com.kilowhat.app.data.model.OutletNameResponseDto
import com.kilowhat.app.data.model.RegisterApplianceRequestDto
import com.kilowhat.app.data.model.RegisterOutletRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Kotlin Spring Boot REST backend contract (port 8080).
 */
interface BackendApi {

    @GET("api/groups/summary")
    suspend fun getGroupsSummary(): GroupsSummaryResponseDto

    @POST("api/groups")
    suspend fun createGroup(
        @Body request: CreateGroupRequestDto
    ): GroupCreatedResponseDto

    @GET("api/appliances/summary")
    suspend fun getAppliancesSummary(): List<ApplianceSummaryDto>

    @GET("api/appliances/{rfid}/sessions")
    suspend fun getApplianceSessions(
        @Path("rfid") rfid: String
    ): ApplianceSessionsResponseDto

    @POST("api/appliances/register")
    suspend fun registerAppliance(
        @Body request: RegisterApplianceRequestDto
    ): ApplianceNameResponseDto

    @POST("api/outlets/register")
    suspend fun registerOutlet(
        @Body request: RegisterOutletRequestDto
    ): OutletNameResponseDto

    @GET("api/appliances/{rfid}")
    suspend fun getAppliance(
        @Path("rfid") rfid: String
    ): ApplianceLookupResponseDto

    @GET("api/outlets/{outletId}")
    suspend fun getOutlet(
        @Path("outletId") outletId: String
    ): OutletLookupResponseDto
}
