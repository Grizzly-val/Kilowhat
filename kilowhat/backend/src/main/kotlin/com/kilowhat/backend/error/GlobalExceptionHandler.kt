package com.kilowhat.backend.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.ConnectException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException

/** Single place that turns every failure into the `{"error","message"}` shape. Stack traces never leave the server. */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: HttpServletRequest): ResponseEntity<ErrorBody> {
        if (ex.status.is5xxServerError) {
            log.error("{} {} failed: {} ({})", request.method, request.requestURI, ex.code, ex.message, ex.cause)
        } else {
            log.warn("{} {} rejected: {} ({})", request.method, request.requestURI, ex.code, ex.message)
        }
        return respond(ex.status, ex.code, ex.message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorBody> {
        log.warn("{} {} rejected: malformed JSON ({})", request.method, request.requestURI, ex.mostSpecificCause.message)
        return respond(
            HttpStatus.BAD_REQUEST,
            "malformed_json",
            "Request body is missing, is not valid JSON, or has a field of the wrong type.",
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleAny(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorBody> {
        if (isDatabaseDown(ex)) {
            log.error("{} {} failed: database unavailable ({})", request.method, request.requestURI, rootMessage(ex))
            return respond(
                HttpStatus.SERVICE_UNAVAILABLE,
                "database_unavailable",
                "The database is unreachable. Is XAMPP MySQL running?",
            )
        }
        if (ex is ErrorResponse) {
            // Framework-level errors such as unknown route (404) or wrong HTTP method (405).
            val status = HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.BAD_REQUEST
            log.warn("{} {} rejected: {} ({})", request.method, request.requestURI, status.value(), ex.message)
            val code = status.name.lowercase()
            return respond(status, code, ex.body.detail ?: status.reasonPhrase)
        }
        log.error("{} {} failed with an unexpected error", request.method, request.requestURI, ex)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred.")
    }

    private fun respond(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ErrorBody(code, message))

    companion object {
        /** True when the failure (or any of its causes) means the database could not be reached. */
        fun isDatabaseDown(ex: Throwable): Boolean =
            generateSequence(ex) { it.cause }.take(20).any {
                it is DataAccessResourceFailureException ||
                    it is CannotCreateTransactionException ||
                    it is SQLTransientConnectionException ||
                    it is SQLNonTransientConnectionException ||
                    it is ConnectException
            }

        fun rootMessage(ex: Throwable): String =
            generateSequence(ex) { it.cause }.take(20).last().message ?: ex.javaClass.simpleName
    }
}
