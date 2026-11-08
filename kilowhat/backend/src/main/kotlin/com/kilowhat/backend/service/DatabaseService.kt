package com.kilowhat.backend.service

import com.kilowhat.backend.dto.HealthResponse
import com.kilowhat.backend.error.DatabaseUnavailableException
import com.kilowhat.backend.error.GlobalExceptionHandler
import com.kilowhat.backend.repository.DatabaseRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Service

@Service
class DatabaseService(private val database: DatabaseRepository) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DatabaseService::class.java)

    fun health(): HealthResponse {
        try {
            database.ping()
        } catch (ex: Exception) {
            throw DatabaseUnavailableException("The database is unreachable. Is XAMPP MySQL running?", ex)
        }
        return HealthResponse(status = "ok", database = "up")
    }

    /** Startup check only: the backend never creates or alters tables. */
    override fun run(args: ApplicationArguments) {
        try {
            val missing = REQUIRED_TABLES - database.existingTables(REQUIRED_TABLES)
            if (missing.isEmpty()) {
                log.info("Database schema OK: tables {} found", REQUIRED_TABLES)
            } else {
                log.error(
                    "Database schema incomplete: missing tables {}. Import database/kilowhat_init.sql " +
                        "(phpMyAdmin > Import, or: mysql -u root < database/kilowhat_init.sql).",
                    missing,
                )
            }
        } catch (ex: Exception) {
            val reason = if (GlobalExceptionHandler.isDatabaseDown(ex)) "database unreachable" else "check failed"
            log.error(
                "Could not verify the database schema ({}: {}). Start XAMPP MySQL and make sure database/kilowhat_init.sql " +
                    "has been imported. The API will answer 503 until the database is reachable.",
                reason, GlobalExceptionHandler.rootMessage(ex),
            )
        }
    }

    private companion object {
        val REQUIRED_TABLES = setOf("outlets", "appliances", "sessions", "groups")
    }
}
