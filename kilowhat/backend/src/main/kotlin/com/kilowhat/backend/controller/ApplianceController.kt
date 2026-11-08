package com.kilowhat.backend.controller

import com.kilowhat.backend.dto.ApplianceLookupResponse
import com.kilowhat.backend.dto.ApplianceNameResponse
import com.kilowhat.backend.dto.ApplianceSessionsResponse
import com.kilowhat.backend.dto.ApplianceSummaryItem
import com.kilowhat.backend.dto.RegisterApplianceRequest
import com.kilowhat.backend.service.ApplianceService
import com.kilowhat.backend.service.SessionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/appliances")
class ApplianceController(
    private val appliances: ApplianceService,
    private val sessions: SessionService,
) {

    @GetMapping("/summary")
    fun summary(): List<ApplianceSummaryItem> = appliances.summary()

    @GetMapping("/{rfid}")
    fun lookup(@PathVariable rfid: String): ApplianceLookupResponse = appliances.lookup(rfid)

    @GetMapping("/{rfid}/sessions")
    fun sessions(@PathVariable rfid: String): ApplianceSessionsResponse = sessions.forAppliance(rfid)

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterApplianceRequest): ApplianceNameResponse =
        appliances.register(request)
}
