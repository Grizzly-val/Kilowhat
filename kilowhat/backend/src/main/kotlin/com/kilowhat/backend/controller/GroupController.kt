package com.kilowhat.backend.controller

import com.kilowhat.backend.dto.CreateGroupRequest
import com.kilowhat.backend.dto.GroupCreatedResponse
import com.kilowhat.backend.dto.GroupsSummaryResponse
import com.kilowhat.backend.service.GroupService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/groups")
class GroupController(private val groups: GroupService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateGroupRequest): GroupCreatedResponse =
        groups.create(request)

    @GetMapping("/summary")
    fun summary(): GroupsSummaryResponse = groups.summary()
}
