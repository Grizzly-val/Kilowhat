package com.kilowhat.backend.error

import org.springframework.http.HttpStatus

/** Error body used by every failure response: `{"error":"<code>","message":"<human readable>"}`. */
data class ErrorBody(
    val error: String,
    val message: String,
)

/** A failure the API reports deliberately, with a stable error code. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class ValidationException(message: String) :
    ApiException(HttpStatus.BAD_REQUEST, "validation_error", message)

class ReservedNameException(message: String) :
    ApiException(HttpStatus.BAD_REQUEST, "reserved_name", message)

class GroupNotFoundException(message: String) :
    ApiException(HttpStatus.BAD_REQUEST, "group_not_found", message)

class GroupExistsException(message: String = "A group with this name already exists.") :
    ApiException(HttpStatus.CONFLICT, "group_exists", message)

class DatabaseUnavailableException(message: String, cause: Throwable? = null) :
    ApiException(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable", message) {
    init {
        cause?.let { initCause(it) }
    }
}
