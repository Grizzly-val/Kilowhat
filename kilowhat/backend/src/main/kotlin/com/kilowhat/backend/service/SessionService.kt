package com.kilowhat.backend.service

import com.kilowhat.backend.dto.ApplianceSessionsResponse
import com.kilowhat.backend.dto.SessionRequest
import com.kilowhat.backend.dto.SessionSavedResponse
import com.kilowhat.backend.error.ValidationException
import com.kilowhat.backend.repository.ApplianceRepository
import com.kilowhat.backend.repository.NewSession
import com.kilowhat.backend.repository.OutletRepository
import com.kilowhat.backend.repository.SessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset

@Service
class SessionService(
    private val sessions: SessionRepository,
    private val outlets: OutletRepository,
    private val appliances: ApplianceRepository,
) {
    private val log = LoggerFactory.getLogger(SessionService::class.java)

    /**
     * Stores a finished session. The outlet row is created first (inside the same transaction) so the
     * foreign key never rejects a session from an outlet the user has not named yet.
     */
    @Transactional
    fun save(request: SessionRequest): SessionSavedResponse {
        val session = validate(request)
        outlets.ensureExists(session.outletId)
        val id = sessions.insert(session)
        log.info(
            "Saved session {}: outlet={} appliance={} wh={} start={}Z end={}Z",
            id, session.outletId, session.applianceRfid, session.totalWh.toPlainString(),
            session.startUtc, session.endUtc,
        )
        return SessionSavedResponse(sessionId = id)
    }

    @Transactional(readOnly = true)
    fun forAppliance(rawRfid: String): ApplianceSessionsResponse {
        val rfid = rawRfid.trim()
        val items = sessions.findByAppliance(rfid)
        val total = items.fold(BigDecimal.ZERO) { sum, s -> sum + s.totalWhConsumed }.setScale(WH_SCALE)
        return ApplianceSessionsResponse(
            applianceRfid = rfid,
            displayName = Inputs.displayName(appliances.find(rfid)?.customName, rfid),
            totalWhConsumed = total,
            sessions = items,
        )
    }

    private fun validate(request: SessionRequest): NewSession {
        val outletId = Inputs.requiredText(request.outletId, "outlet_id", ID_MAX)
        val rfid = Inputs.requiredText(request.applianceRfid, "appliance_rfid", ID_MAX)
        val startInstant = Inputs.parseInstant(request.startTimestamp, "start_timestamp")
        val endInstant = Inputs.parseInstant(request.endTimestamp, "end_timestamp")
        val wh = request.totalWhConsumed ?: throw ValidationException("'total_wh_consumed' is required.")

        if (wh.signum() < 0) throw ValidationException("'total_wh_consumed' must be >= 0.")
        val rounded = wh.setScale(WH_SCALE, RoundingMode.HALF_UP)
        if (rounded > WH_MAX) throw ValidationException("'total_wh_consumed' must be at most $WH_MAX.")
        if (endInstant.isBefore(startInstant)) {
            throw ValidationException(
                "'end_timestamp' ($endInstant) must not be before 'start_timestamp' ($startInstant).",
            )
        }
        return NewSession(
            outletId = outletId,
            applianceRfid = rfid,
            startUtc = Inputs.toUtcLocalDateTime(startInstant),
            endUtc = Inputs.toUtcLocalDateTime(endInstant),
            totalWh = rounded,
        )
    }

    private companion object {
        const val ID_MAX = 50
        const val WH_SCALE = 3

        /** Largest value DECIMAL(10,3) can hold. */
        val WH_MAX = BigDecimal("9999999.999")
    }
}
