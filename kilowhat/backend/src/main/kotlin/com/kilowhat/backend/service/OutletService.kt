package com.kilowhat.backend.service

import com.kilowhat.backend.dto.OutletLookupResponse
import com.kilowhat.backend.dto.OutletNameResponse
import com.kilowhat.backend.dto.RegisterOutletRequest
import com.kilowhat.backend.repository.OutletRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OutletService(private val outlets: OutletRepository) {

    private val log = LoggerFactory.getLogger(OutletService::class.java)

    fun lookup(rawOutletId: String): OutletLookupResponse {
        val outletId = rawOutletId.trim()
        val mapping = outlets.find(outletId)
        val customName = mapping?.customName?.takeIf { it.isNotBlank() }
        return OutletLookupResponse(
            outletId = outletId,
            customName = customName,
            displayName = Inputs.displayName(customName, outletId),
            registered = customName != null,
        )
    }

    fun register(request: RegisterOutletRequest): OutletNameResponse {
        val outletId = Inputs.requiredText(request.outletId, "outlet_id", 50)
        val name = Inputs.requiredText(request.customName, "custom_name", 100)
        outlets.upsert(outletId, name)
        log.info("Outlet {} is now named '{}'", outletId, name)
        return OutletNameResponse(outletId = outletId, customName = name, displayName = name)
    }
}
