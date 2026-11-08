package com.kilowhat.app.data.network

import com.kilowhat.app.data.model.BleStreamReading
import com.kilowhat.app.data.model.PairResponse
import com.kilowhat.app.data.model.UnpairResponse
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Python hardware simulator HTTP contract (port 8001).
 */
interface SimulatorApi {

    @POST("pair")
    suspend fun pair(): PairResponse

    @POST("unpair")
    suspend fun unpair(): UnpairResponse

    @GET("ble-stream")
    suspend fun getBleStream(): BleStreamReading
}
