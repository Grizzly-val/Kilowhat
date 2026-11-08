package com.kilowhat.backend.controller

import com.kilowhat.backend.dto.SessionRequest
import com.kilowhat.backend.dto.SessionSavedResponse
import com.kilowhat.backend.service.SessionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sessions")
class SessionController(private val sessions: SessionService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: SessionRequest): SessionSavedResponse = sessions.save(request)
}
