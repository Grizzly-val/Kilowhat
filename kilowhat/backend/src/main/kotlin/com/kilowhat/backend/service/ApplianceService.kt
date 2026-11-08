package com.kilowhat.backend.service

import com.kilowhat.backend.dto.ApplianceLookupResponse
import com.kilowhat.backend.dto.ApplianceNameResponse
import com.kilowhat.backend.dto.ApplianceSummaryItem
import com.kilowhat.backend.dto.RegisterApplianceRequest
import com.kilowhat.backend.error.GroupNotFoundException
import com.kilowhat.backend.error.ValidationException
import com.kilowhat.backend.repository.ApplianceRepository
import com.kilowhat.backend.repository.GroupRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ApplianceService(
    private val appliances: ApplianceRepository,
    private val groups: GroupRepository,
) {

    private val log = LoggerFactory.getLogger(ApplianceService::class.java)

    /** Names are resolved here, at read time, so a rename is visible everywhere on the next request. */
    fun summary(): List<ApplianceSummaryItem> = appliances.summary().map { row ->
        val customName = row.customName?.takeIf { it.isNotBlank() }
        ApplianceSummaryItem(
            applianceRfid = row.applianceRfid,
            customName = customName,
            displayName = Inputs.displayName(customName, row.applianceRfid),
            totalWhConsumed = row.totalWh.setScale(3),
            sessionCount = row.sessionCount,
            lastUsed = row.lastUsed,
        )
    }

    fun lookup(rawRfid: String): ApplianceLookupResponse {
        val rfid = rawRfid.trim()
        val mapping = appliances.find(rfid)
        val customName = mapping?.customName?.takeIf { it.isNotBlank() }
        return ApplianceLookupResponse(
            applianceRfid = rfid,
            customName = customName,
            displayName = Inputs.displayName(customName, rfid),
            registered = customName != null,
        )
    }

    @Transactional
    fun register(request: RegisterApplianceRequest): ApplianceNameResponse {
        val rfid = Inputs.requiredText(request.applianceRfid, "appliance_rfid", 50)
        val hasCustomName = request.customName != null
        val hasGroupId = request.groupId != null
        if (!hasCustomName && !hasGroupId) {
            throw ValidationException("At least one of 'custom_name' or 'group_id' is required.")
        }

        val customName = if (hasCustomName) {
            Inputs.requiredText(request.customName, "custom_name", 100)
        } else {
            null
        }

        val groupId = request.groupId
        if (groupId != null && groups.findById(groupId) == null) {
            throw GroupNotFoundException("Group with id $groupId does not exist.")
        }

        appliances.upsertRegistration(rfid, customName, groupId)
        val saved = appliances.findWithGroup(rfid)
        val savedCustomName = saved?.customName?.takeIf { it.isNotBlank() }
        val displayName = Inputs.displayName(savedCustomName, rfid)

        log.info(
            "Registered appliance {}: custom_name='{}' group_id={} group_name='{}'",
            rfid, savedCustomName, saved?.groupId, saved?.groupName,
        )
        return ApplianceNameResponse(
            applianceRfid = rfid,
            customName = savedCustomName,
            displayName = displayName,
            groupId = saved?.groupId,
            groupName = saved?.groupName,
        )
    }
}
