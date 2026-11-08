package com.kilowhat.app.data.network

import com.kilowhat.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Provides the two distinct Retrofit instances required by the spec:
 * - [simulatorApi]: short timeouts (connect 2s, read 3s) for 1 Hz polling against port 8001.
 * - [backendApi]: standard timeouts (connect 5s, read 10s) against port 8080.
 */
object NetworkModule {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val contentType = "application/json".toMediaType()

    private val simulatorOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val backendOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val simulatorApi: SimulatorApi = Retrofit.Builder()
        .baseUrl(ensureTrailingSlash(BuildConfig.SIMULATOR_BASE_URL))
        .client(simulatorOkHttpClient)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
        .create(SimulatorApi::class.java)

    val backendApi: BackendApi = Retrofit.Builder()
        .baseUrl(ensureTrailingSlash(BuildConfig.BACKEND_BASE_URL))
        .client(backendOkHttpClient)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
        .create(BackendApi::class.java)

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
