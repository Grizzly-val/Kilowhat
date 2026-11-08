package com.kilowhat.backend.service

import com.kilowhat.backend.dto.ApplianceSummaryItem
import com.kilowhat.backend.dto.CreateGroupRequest
import com.kilowhat.backend.dto.GroupCreatedResponse
import com.kilowhat.backend.dto.GroupSummaryItem
import com.kilowhat.backend.dto.GroupsSummaryResponse
import com.kilowhat.backend.error.GroupExistsException
import com.kilowhat.backend.error.ReservedNameException
import com.kilowhat.backend.error.ValidationException
import com.kilowhat.backend.repository.ApplianceRepository
import com.kilowhat.backend.repository.GroupRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class GroupService(
    private val groups: GroupRepository,
    private val appliances: ApplianceRepository,
) {

    private val log = LoggerFactory.getLogger(GroupService::class.java)

    @Transactional
    fun create(request: CreateGroupRequest): GroupCreatedResponse {
        val groupName = Inputs.requiredText(request.groupName, "group_name", GROUP_NAME_MAX)
        if (groupName.equals(UNASSIGNED_GROUP_NAME, ignoreCase = true)) {
            throw ReservedNameException("'$UNASSIGNED_GROUP_NAME' is a reserved group name.")
        }

        val rfids = request.applianceRfids.orEmpty()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        for (rfid in rfids) {
            if (rfid.length > RFID_MAX) {
                throw ValidationException("Each item in 'appliance_rfids' must be at most $RFID_MAX characters.")
            }
        }

        val groupId = try {
            groups.insert(groupName)
        } catch (ex: DuplicateKeyException) {
            throw GroupExistsException("A group with this name already exists.")
        }

        for (rfid in rfids) {
            appliances.assignGroup(rfid, groupId)
        }

        log.info("Created group {} '{}' with {} appliance(s)", groupId, groupName, rfids.size)
        return GroupCreatedResponse(
            groupId = groupId,
            groupName = groupName,
            applianceCount = rfids.size,
        )
    }

    @Transactional(readOnly = true)
    fun summary(): GroupsSummaryResponse {
        val realGroups = groups.findAllOrdered()
        val applianceRows = appliances.summary()

        val byGroupId = applianceRows.groupBy(
            keySelector = { it.groupId },
            valueTransform = { row ->
                val customName = row.customName?.takeIf { it.isNotBlank() }
                ApplianceSummaryItem(
                    applianceRfid = row.applianceRfid,
                    customName = customName,
                    displayName = Inputs.displayName(customName, row.applianceRfid),
                    totalWhConsumed = row.totalWh.setScale(WH_SCALE),
                    sessionCount = row.sessionCount,
                    lastUsed = row.lastUsed,
                )
            },
        )

        val groupItems = mutableListOf<GroupSummaryItem>()

        for (group in realGroups) {
            val items = byGroupId[group.groupId].orEmpty()
            val groupTotal = items.fold(BigDecimal.ZERO) { sum, a -> sum + a.totalWhConsumed }.setScale(WH_SCALE)
            groupItems.add(
                GroupSummaryItem(
                    groupId = group.groupId,
                    groupName = group.groupName,
                    totalWhConsumed = groupTotal,
                    applianceCount = items.size,
                    appliances = items,
                ),
            )
        }

        val unassignedItems = byGroupId[null].orEmpty()
        if (unassignedItems.isNotEmpty()) {
            val unassignedTotal = unassignedItems
                .fold(BigDecimal.ZERO) { sum, a -> sum + a.totalWhConsumed }
                .setScale(WH_SCALE)
            groupItems.add(
                GroupSummaryItem(
                    groupId = null,
                    groupName = UNASSIGNED_GROUP_NAME,
                    totalWhConsumed = unassignedTotal,
                    applianceCount = unassignedItems.size,
                    appliances = unassignedItems,
                ),
            )
        }

        val totalWhConsumed = groupItems
            .fold(BigDecimal.ZERO) { sum, g -> sum + g.totalWhConsumed }
            .setScale(WH_SCALE)

        return GroupsSummaryResponse(
            totalWhConsumed = totalWhConsumed,
            groups = groupItems,
        )
    }

    companion object {
        const val UNASSIGNED_GROUP_NAME = "Unknown / Unassigned"
        private const val GROUP_NAME_MAX = 100
        private const val RFID_MAX = 50
        private const val WH_SCALE = 3
    }
}
