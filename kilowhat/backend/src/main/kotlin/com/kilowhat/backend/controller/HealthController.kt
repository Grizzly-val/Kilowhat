package com.kilowhat.backend.controller

import com.kilowhat.backend.dto.HealthResponse
import com.kilowhat.backend.service.DatabaseService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val database: DatabaseService) {

    @GetMapping("/api/health")
    fun health(): HealthResponse = database.health()
}
