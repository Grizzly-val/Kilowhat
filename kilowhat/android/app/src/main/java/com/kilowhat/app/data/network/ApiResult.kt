package com.kilowhat.app.data.network

import com.kilowhat.app.data.model.ApiErrorDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Sealed result wrapper used by every network call in the app so no network or serialization
 * exception ever escapes unhandled.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class HttpError(
        val code: Int,
        val errorCode: String?,
        val message: String
    ) : ApiResult<Nothing>()
    data class NetworkError(
        val message: String,
        val cause: Throwable? = null
    ) : ApiResult<Nothing>()
}

suspend fun <T> safeApiCall(
    json: Json,
    fallbackNetworkMessage: String,
    block: suspend () -> T
): ApiResult<T> = withContext(Dispatchers.IO) {
    try {
        ApiResult.Success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (httpEx: HttpException) {
        val statusCode = httpEx.code()
        val rawBody = try {
            httpEx.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        val parsed = rawBody?.takeIf { it.isNotBlank() }?.let { body ->
            try {
                json.decodeFromString<ApiErrorDto>(body)
            } catch (_: Exception) {
                null
            }
        }
        val readableMessage = parsed?.message?.takeIf { it.isNotBlank() }
            ?: when (statusCode) {
                400 -> "Invalid request sent to the server."
                403 -> "The outlet adapter is not paired."
                404 -> "Requested resource was not found."
                409 -> "A group with this name already exists."
                503 -> "Database is currently unavailable. Is XAMPP MySQL running?"
                else -> "Server returned HTTP $statusCode."
            }
        ApiResult.HttpError(
            code = statusCode,
            errorCode = parsed?.error,
            message = readableMessage
        )
    } catch (timeoutEx: SocketTimeoutException) {
        ApiResult.NetworkError(
            message = fallbackNetworkMessage,
            cause = timeoutEx
        )
    } catch (connectEx: ConnectException) {
        ApiResult.NetworkError(
            message = fallbackNetworkMessage,
            cause = connectEx
        )
    } catch (hostEx: UnknownHostException) {
        ApiResult.NetworkError(
            message = fallbackNetworkMessage,
            cause = hostEx
        )
    } catch (ioEx: IOException) {
        ApiResult.NetworkError(
            message = fallbackNetworkMessage,
            cause = ioEx
        )
    } catch (serEx: SerializationException) {
        ApiResult.NetworkError(
            message = "Received an unexpected response format from the server.",
            cause = serEx
        )
    } catch (ex: Exception) {
        ApiResult.NetworkError(
            message = fallbackNetworkMessage,
            cause = ex
        )
    }
}
