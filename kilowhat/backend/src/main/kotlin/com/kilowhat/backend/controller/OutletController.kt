package com.kilowhat.backend.controller

import com.kilowhat.backend.dto.OutletLookupResponse
import com.kilowhat.backend.dto.OutletNameResponse
import com.kilowhat.backend.dto.RegisterOutletRequest
import com.kilowhat.backend.service.OutletService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/outlets")
class OutletController(private val outlets: OutletService) {

    @GetMapping("/{outletId}")
    fun lookup(@PathVariable outletId: String): OutletLookupResponse = outlets.lookup(outletId)

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterOutletRequest): OutletNameResponse = outlets.register(request)
}
