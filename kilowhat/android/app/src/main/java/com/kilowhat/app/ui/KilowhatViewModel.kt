package com.kilowhat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kilowhat.app.data.model.ApplianceSummaryDto
import com.kilowhat.app.data.model.GroupSummaryDto
import com.kilowhat.app.data.model.SessionItemDto
import com.kilowhat.app.data.network.ApiResult
import com.kilowhat.app.data.repository.KilowhatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class RealtimeUiState(
    val isConnected: Boolean = false,
    val isPairing: Boolean = false,
    val connectionError: String? = null,
    val outletId: String = "",
    val outletCustomName: String? = null,
    val watts: Double = 0.0,
    val voltage: Double = 0.0,
    val current: Double = 0.0,
    val activeApplianceRfid: String = "NONE",
    val activeApplianceDisplayName: String? = null,
    val isEditOutletDialogOpen: Boolean = false,
    val isSavingOutletName: Boolean = false,
    val outletDialogError: String? = null
) {
    val isAppliancePluggedIn: Boolean
        get() = activeApplianceRfid.isNotBlank() && !activeApplianceRfid.equals("NONE", ignoreCase = true)

    val resolvedOutletTitle: String
        get() = outletCustomName?.takeIf { it.isNotBlank() }
            ?: outletId.ifBlank { "Smart Outlet" }

    val hasCustomOutletName: Boolean
        get() = !outletCustomName.isNullOrBlank()

    val isGridSurgeWarning: Boolean
        get() = voltage >= 242.0 || (!isAppliancePluggedIn && watts > 0.5)
}

data class DevicesUiState(
    val totalWhConsumed: Double = 0.0,
    val groups: List<GroupSummaryDto> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

data class ApplianceDetailUiState(
    val isOpen: Boolean = false,
    val applianceRfid: String = "",
    val displayName: String = "",
    val totalWhConsumed: Double = 0.0,
    val sessions: List<SessionItemDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

enum class NamingDialogStep {
    NONE,
    CHOICE_PROMPT,
    MANUAL_FORM,
    CREATE_GROUP_FORM
}

data class SelectableDeviceUiItem(
    val applianceRfid: String,
    val displayName: String,
    val currentGroupName: String? = null
)

data class NamingDialogUiState(
    val step: NamingDialogStep = NamingDialogStep.NONE,
    val rfidInput: String = "",
    val customNameInput: String = "",
    val groupNameInput: String = "",
    val availableDevices: List<SelectableDeviceUiItem> = emptyList(),
    val selectedRfids: Set<String> = emptySet(),
    val isLoadingDevices: Boolean = false,
    val devicesLoadError: String? = null,
    val isSaving: Boolean = false,
    val inlineError: String? = null
)

/**
 * Activity-scoped ViewModel managing both the Real-Time BLE stream and the Devices ledger.
 * Scoping to MainActivity guarantees that switching bottom tabs or rotating the device never
 * interrupts the 1 Hz simulator polling loop, and that dynamic ledger renames propagate
 * immediately across all views.
 */
class KilowhatViewModel(
    private val repository: KilowhatRepository = KilowhatRepository()
) : ViewModel() {

    private val _realtimeState = MutableStateFlow(RealtimeUiState())
    val realtimeState: StateFlow<RealtimeUiState> = _realtimeState.asStateFlow()

    private val _devicesState = MutableStateFlow(DevicesUiState(isLoading = true))
    val devicesState: StateFlow<DevicesUiState> = _devicesState.asStateFlow()

    private val _detailState = MutableStateFlow(ApplianceDetailUiState())
    val detailState: StateFlow<ApplianceDetailUiState> = _detailState.asStateFlow()

    private val _namingDialogState = MutableStateFlow(NamingDialogUiState())
    val namingDialogState: StateFlow<NamingDialogUiState> = _namingDialogState.asStateFlow()

    private val _snackbarMessages = Channel<String>(Channel.BUFFERED)
    val snackbarMessages = _snackbarMessages.receiveAsFlow()

    /** Per-RFID display name cache. Cleared whenever any appliance is registered/renamed. */
    private val applianceNameCache = ConcurrentHashMap<String, String>()

    private var streamJob: Job? = null
    private var outletLookupIdInFlight: String? = null

    fun showDemoPlaceholderSnackbar() {
        sendSnackbar("Not available in this demo yet")
    }

    fun sendSnackbar(message: String) {
        _snackbarMessages.trySend(message)
    }

    // ========================================================================
    // Real-Time Tab Actions
    // ========================================================================

    fun connectDemoScript() {
        if (_realtimeState.value.isPairing || _realtimeState.value.isConnected) return

        _realtimeState.update {
            it.copy(isPairing = true, connectionError = null)
        }

        viewModelScope.launch {
            when (val result = repository.pairSimulator()) {
                is ApiResult.Success -> {
                    val pairedOutletId = result.data.outletId.trim()
                    _realtimeState.update {
                        RealtimeUiState(
                            isConnected = true,
                            isPairing = false,
                            connectionError = null,
                            outletId = pairedOutletId
                        )
                    }
                    if (pairedOutletId.isNotEmpty()) {
                        resolveOutletName(pairedOutletId)
                    }
                    startBleStreamLoop()
                }
                is ApiResult.HttpError -> {
                    val msg = "Could not pair with the simulator (HTTP ${result.code}): ${result.message}"
                    _realtimeState.update {
                        it.copy(isConnected = false, isPairing = false, connectionError = msg)
                    }
                    sendSnackbar(msg)
                }
                is ApiResult.NetworkError -> {
                    val msg = "Could not reach the simulator. Is the Python script running?"
                    _realtimeState.update {
                        it.copy(isConnected = false, isPairing = false, connectionError = msg)
                    }
                    sendSnackbar(msg)
                }
            }
        }
    }

    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        outletLookupIdInFlight = null

        _realtimeState.value = RealtimeUiState()

        // Best-effort unpair call; failures are intentionally ignored per specification.
        viewModelScope.launch {
            repository.unpairSimulator()
        }
    }

    private fun startBleStreamLoop() {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var consecutiveFailures = 0

            while (isActive) {
                when (val result = repository.pollBleStream()) {
                    is ApiResult.Success -> {
                        consecutiveFailures = 0
                        val reading = result.data
                        val incomingOutletId = reading.outletId.trim()
                        val currentState = _realtimeState.value

                        val effectiveOutletId = currentState.outletId.ifBlank { incomingOutletId }
                        if (currentState.outletId.isBlank() && effectiveOutletId.isNotBlank()) {
                            resolveOutletName(effectiveOutletId)
                        }

                        val rfid = reading.activeApplianceRfid.trim().ifEmpty { "NONE" }
                        val isPluggedIn = !rfid.equals("NONE", ignoreCase = true)

                        val cachedDisplayName = if (isPluggedIn) applianceNameCache[rfid] else null

                        _realtimeState.update { state ->
                            state.copy(
                                outletId = effectiveOutletId,
                                watts = reading.watts,
                                voltage = reading.voltage,
                                current = reading.current,
                                activeApplianceRfid = rfid,
                                activeApplianceDisplayName = if (isPluggedIn) {
                                    cachedDisplayName ?: rfid
                                } else {
                                    null
                                }
                            )
                        }

                        if (isPluggedIn && cachedDisplayName == null) {
                            resolveApplianceNameForStream(rfid)
                        }
                    }

                    is ApiResult.HttpError -> {
                        if (result.code == 403 || result.errorCode == "not_paired") {
                            returnToDisconnected("Outlet is not paired. Please connect again.")
                            break
                        }
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            returnToDisconnected("Lost connection to the outlet")
                            break
                        }
                    }

                    is ApiResult.NetworkError -> {
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            returnToDisconnected("Lost connection to the outlet")
                            break
                        }
                    }
                }

                delay(1_000L)
            }
        }
    }

    private fun returnToDisconnected(reason: String) {
        streamJob?.cancel()
        streamJob = null
        outletLookupIdInFlight = null
        _realtimeState.value = RealtimeUiState(
            isConnected = false,
            connectionError = reason
        )
        sendSnackbar(reason)
    }

    private fun resolveApplianceNameForStream(rfid: String) {
        viewModelScope.launch {
            when (val res = repository.lookupAppliance(rfid)) {
                is ApiResult.Success -> {
                    val displayName = res.data.displayName.ifBlank { rfid }
                    applianceNameCache[rfid] = displayName
                    _realtimeState.update { state ->
                        if (state.isConnected && state.activeApplianceRfid == rfid) {
                            state.copy(activeApplianceDisplayName = displayName)
                        } else {
                            state
                        }
                    }
                }
                else -> {
                    // Leave uncached so the next tick retries cleanly.
                }
            }
        }
    }

    private fun resolveOutletName(outletId: String) {
        if (outletId.isBlank() || outletLookupIdInFlight == outletId) return
        outletLookupIdInFlight = outletId
        viewModelScope.launch {
            when (val res = repository.lookupOutlet(outletId)) {
                is ApiResult.Success -> {
                    _realtimeState.update { state ->
                        if (state.outletId == outletId) {
                            state.copy(outletCustomName = res.data.customName)
                        } else {
                            state
                        }
                    }
                }
                else -> {
                    // Keep raw outletId on header if backend is temporarily unreachable.
                }
            }
            if (outletLookupIdInFlight == outletId) {
                outletLookupIdInFlight = null
            }
        }
    }

    fun openEditOutletDialog() {
        if (!_realtimeState.value.isConnected || _realtimeState.value.outletId.isBlank()) return
        _realtimeState.update {
            it.copy(
                isEditOutletDialogOpen = true,
                isSavingOutletName = false,
                outletDialogError = null
            )
        }
    }

    fun dismissEditOutletDialog() {
        if (_realtimeState.value.isSavingOutletName) return
        _realtimeState.update {
            it.copy(
                isEditOutletDialogOpen = false,
                outletDialogError = null
            )
        }
    }

    fun saveOutletName(customNameInput: String) {
        val currentOutletId = _realtimeState.value.outletId.trim()
        val trimmedName = customNameInput.trim()

        if (currentOutletId.isEmpty()) {
            _realtimeState.update { it.copy(outletDialogError = "No active outlet ID to rename.") }
            return
        }
        if (trimmedName.isEmpty()) {
            _realtimeState.update { it.copy(outletDialogError = "Outlet name must not be blank.") }
            return
        }
        if (trimmedName.length > 100) {
            _realtimeState.update { it.copy(outletDialogError = "Outlet name must be at most 100 characters.") }
            return
        }

        _realtimeState.update {
            it.copy(isSavingOutletName = true, outletDialogError = null)
        }

        viewModelScope.launch {
            when (val res = repository.registerOutlet(currentOutletId, trimmedName)) {
                is ApiResult.Success -> {
                    _realtimeState.update { state ->
                        state.copy(
                            outletCustomName = res.data.customName ?: trimmedName,
                            isEditOutletDialogOpen = false,
                            isSavingOutletName = false,
                            outletDialogError = null
                        )
                    }
                    sendSnackbar("Outlet named \"${res.data.displayName}\"")

                    // If a device detail sheet is open, refresh it so its session rows reflect the new outlet name.
                    val openDetail = _detailState.value
                    if (openDetail.isOpen && openDetail.applianceRfid.isNotBlank()) {
                        loadApplianceSessions(openDetail.applianceRfid, showFullLoading = false)
                    }
                }
                is ApiResult.HttpError -> {
                    _realtimeState.update {
                        it.copy(isSavingOutletName = false, outletDialogError = res.message)
                    }
                }
                is ApiResult.NetworkError -> {
                    _realtimeState.update {
                        it.copy(isSavingOutletName = false, outletDialogError = res.message)
                    }
                }
            }
        }
    }

    // ========================================================================
    // Devices Tab & Detail Sheet Actions
    // ========================================================================

    fun loadDevicesSummary(isRefresh: Boolean = false) {
        val hadDataOnScreen = _devicesState.value.groups.isNotEmpty()
        _devicesState.update { state ->
            when {
                isRefresh && state.groups.isNotEmpty() -> {
                    state.copy(isRefreshing = true, error = null)
                }
                state.groups.isNotEmpty() -> {
                    state.copy(error = null)
                }
                else -> {
                    state.copy(isLoading = true, isRefreshing = false, error = null)
                }
            }
        }

        viewModelScope.launch {
            when (val res = repository.getGroupsSummary()) {
                is ApiResult.Success -> {
                    _devicesState.value = DevicesUiState(
                        totalWhConsumed = res.data.totalWhConsumed,
                        groups = res.data.groups,
                        isLoading = false,
                        isRefreshing = false,
                        error = null
                    )
                }
                is ApiResult.HttpError -> {
                    if (hadDataOnScreen || _devicesState.value.groups.isNotEmpty()) {
                        _devicesState.update {
                            it.copy(isLoading = false, isRefreshing = false, error = null)
                        }
                        sendSnackbar(res.message)
                    } else {
                        _devicesState.update {
                            it.copy(isLoading = false, isRefreshing = false, error = res.message)
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    if (hadDataOnScreen || _devicesState.value.groups.isNotEmpty()) {
                        _devicesState.update {
                            it.copy(isLoading = false, isRefreshing = false, error = null)
                        }
                        sendSnackbar(res.message)
                    } else {
                        _devicesState.update {
                            it.copy(isLoading = false, isRefreshing = false, error = res.message)
                        }
                    }
                }
            }
        }
    }

    fun openApplianceDetail(item: ApplianceSummaryDto) {
        _detailState.value = ApplianceDetailUiState(
            isOpen = true,
            applianceRfid = item.applianceRfid,
            displayName = item.displayName,
            totalWhConsumed = item.totalWhConsumed,
            sessions = emptyList(),
            isLoading = true,
            error = null
        )
        loadApplianceSessions(item.applianceRfid, showFullLoading = true)
    }

    fun retryApplianceDetail() {
        val rfid = _detailState.value.applianceRfid
        if (rfid.isNotBlank()) {
            loadApplianceSessions(rfid, showFullLoading = true)
        }
    }

    fun closeApplianceDetail() {
        _detailState.value = ApplianceDetailUiState()
    }

    private fun loadApplianceSessions(rfid: String, showFullLoading: Boolean) {
        if (showFullLoading) {
            _detailState.update { it.copy(isLoading = true, error = null) }
        }
        viewModelScope.launch {
            when (val res = repository.getApplianceSessions(rfid)) {
                is ApiResult.Success -> {
                    val data = res.data
                    _detailState.update { state ->
                        if (state.isOpen && state.applianceRfid == rfid) {
                            state.copy(
                                displayName = data.displayName,
                                totalWhConsumed = data.totalWhConsumed,
                                sessions = data.sessions,
                                isLoading = false,
                                error = null
                            )
                        } else {
                            state
                        }
                    }
                }
                is ApiResult.HttpError -> {
                    _detailState.update { state ->
                        if (state.isOpen && state.applianceRfid == rfid) {
                            state.copy(isLoading = false, error = res.message)
                        } else {
                            state
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    _detailState.update { state ->
                        if (state.isOpen && state.applianceRfid == rfid) {
                            state.copy(isLoading = false, error = res.message)
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // "+" FAB Dialogs (Manual Input & Create Group)
    // ========================================================================

    fun openNamingChoiceDialog(prefillRfid: String = "") {
        _namingDialogState.value = NamingDialogUiState(
            step = NamingDialogStep.CHOICE_PROMPT,
            rfidInput = prefillRfid
        )
    }

    fun proceedToManualNamingForm() {
        _namingDialogState.update {
            it.copy(
                step = NamingDialogStep.MANUAL_FORM,
                inlineError = null
            )
        }
    }

    fun proceedToCreateGroupForm() {
        _namingDialogState.value = NamingDialogUiState(
            step = NamingDialogStep.CREATE_GROUP_FORM,
            isLoadingDevices = true
        )
        loadCreateGroupDevices()
    }

    fun loadCreateGroupDevices() {
        _namingDialogState.update { state ->
            if (state.step == NamingDialogStep.CREATE_GROUP_FORM) {
                state.copy(isLoadingDevices = true, devicesLoadError = null)
            } else {
                state
            }
        }

        viewModelScope.launch {
            when (val res = repository.getGroupsSummary()) {
                is ApiResult.Success -> {
                    val flattened = res.data.groups
                        .flatMap { group ->
                            val realGroupName = if (group.groupId != null) group.groupName else null
                            group.appliances.map { appliance ->
                                SelectableDeviceUiItem(
                                    applianceRfid = appliance.applianceRfid,
                                    displayName = appliance.displayName,
                                    currentGroupName = realGroupName
                                )
                            }
                        }
                        .distinctBy { it.applianceRfid }

                    _namingDialogState.update { state ->
                        if (state.step == NamingDialogStep.CREATE_GROUP_FORM) {
                            state.copy(
                                availableDevices = flattened,
                                isLoadingDevices = false,
                                devicesLoadError = null
                            )
                        } else {
                            state
                        }
                    }
                }
                is ApiResult.HttpError -> {
                    _namingDialogState.update { state ->
                        if (state.step == NamingDialogStep.CREATE_GROUP_FORM) {
                            state.copy(
                                isLoadingDevices = false,
                                devicesLoadError = res.message
                            )
                        } else {
                            state
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    _namingDialogState.update { state ->
                        if (state.step == NamingDialogStep.CREATE_GROUP_FORM) {
                            state.copy(
                                isLoadingDevices = false,
                                devicesLoadError = res.message
                            )
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    fun updateNamingRfidInput(value: String) {
        _namingDialogState.update {
            it.copy(rfidInput = value, inlineError = null)
        }
    }

    fun updateNamingCustomNameInput(value: String) {
        _namingDialogState.update {
            it.copy(customNameInput = value, inlineError = null)
        }
    }

    fun updateGroupNameInput(value: String) {
        _namingDialogState.update {
            it.copy(groupNameInput = value, inlineError = null)
        }
    }

    fun toggleGroupDeviceSelection(rfid: String) {
        if (_namingDialogState.value.isSaving) return
        _namingDialogState.update { state ->
            val updated = if (rfid in state.selectedRfids) {
                state.selectedRfids - rfid
            } else {
                state.selectedRfids + rfid
            }
            state.copy(selectedRfids = updated)
        }
    }

    fun dismissNamingDialog() {
        if (_namingDialogState.value.isSaving) return
        _namingDialogState.value = NamingDialogUiState()
    }

    fun submitApplianceName() {
        val current = _namingDialogState.value
        if (current.isSaving) return

        val rfid = current.rfidInput.trim()
        val customName = current.customNameInput.trim()

        if (rfid.isEmpty()) {
            _namingDialogState.update { it.copy(inlineError = "Appliance RFID is required.") }
            return
        }
        if (rfid.length > 50) {
            _namingDialogState.update { it.copy(inlineError = "Appliance RFID must be at most 50 characters.") }
            return
        }
        if (customName.isEmpty()) {
            _namingDialogState.update { it.copy(inlineError = "Custom name must not be blank.") }
            return
        }
        if (customName.length > 100) {
            _namingDialogState.update { it.copy(inlineError = "Custom name must be at most 100 characters.") }
            return
        }

        _namingDialogState.update { it.copy(isSaving = true, inlineError = null) }

        viewModelScope.launch {
            when (val res = repository.registerAppliance(rfid, customName)) {
                is ApiResult.Success -> {
                    val saved = res.data

                    // 1. Clear the Real-Time RFID name cache so any rename shows on the next tick.
                    applianceNameCache.clear()

                    // If the renamed tag is currently plugged in on the Real-Time tab, update it immediately.
                    _realtimeState.update { rt ->
                        if (rt.isConnected && rt.activeApplianceRfid == saved.applianceRfid) {
                            rt.copy(activeApplianceDisplayName = saved.displayName)
                        } else {
                            rt
                        }
                    }

                    // 2. Close the dialog and show a confirmation message.
                    _namingDialogState.value = NamingDialogUiState()
                    sendSnackbar("Saved \"${saved.displayName}\" for ${saved.applianceRfid}")

                    // 3. Refresh the Devices summary list.
                    loadDevicesSummary(isRefresh = true)

                    // 4. If a detail sheet is open for this RFID, refresh its header and sessions immediately.
                    val openDetail = _detailState.value
                    if (openDetail.isOpen && openDetail.applianceRfid == saved.applianceRfid) {
                        _detailState.update { it.copy(displayName = saved.displayName) }
                        loadApplianceSessions(saved.applianceRfid, showFullLoading = false)
                    }
                }
                is ApiResult.HttpError -> {
                    _namingDialogState.update {
                        it.copy(isSaving = false, inlineError = res.message)
                    }
                }
                is ApiResult.NetworkError -> {
                    _namingDialogState.update {
                        it.copy(isSaving = false, inlineError = res.message)
                    }
                }
            }
        }
    }

    fun submitCreateGroup() {
        val current = _namingDialogState.value
        if (current.isSaving) return

        val trimmedName = current.groupNameInput.trim()
        if (trimmedName.isEmpty()) {
            _namingDialogState.update { it.copy(inlineError = "Group name must not be blank.") }
            return
        }
        if (trimmedName.length > 100) {
            _namingDialogState.update { it.copy(inlineError = "Group name must be at most 100 characters.") }
            return
        }

        _namingDialogState.update { it.copy(isSaving = true, inlineError = null) }

        val rfidsToAssign = current.selectedRfids.toList()
        viewModelScope.launch {
            when (val res = repository.createGroup(trimmedName, rfidsToAssign)) {
                is ApiResult.Success -> {
                    val created = res.data

                    // Clear Real-Time tab's cached appliance names
                    applianceNameCache.clear()

                    // Close the form and show confirmation
                    _namingDialogState.value = NamingDialogUiState()
                    sendSnackbar("Created group \"${created.groupName}\"")

                    // Refresh the summary so devices instantly appear under the new group card
                    loadDevicesSummary(isRefresh = true)
                }
                is ApiResult.HttpError -> {
                    val errorMsg = when {
                        res.code == 409 || res.errorCode == "group_exists" ->
                            "A group with this name already exists."
                        else -> res.message
                    }
                    _namingDialogState.update {
                        it.copy(isSaving = false, inlineError = errorMsg)
                    }
                }
                is ApiResult.NetworkError -> {
                    _namingDialogState.update {
                        it.copy(isSaving = false, inlineError = res.message)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
