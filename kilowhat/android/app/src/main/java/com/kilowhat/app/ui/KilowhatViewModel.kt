package com.kilowhat.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kilowhat.app.data.ble.BleState
import com.kilowhat.app.data.ble.KilowhatBleClient
import com.kilowhat.app.data.ble.Telemetry
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

enum class ConnectionSource {
    NONE,
    DEMO_SCRIPT,
    BLE
}

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
    val outletDialogError: String? = null,
    val connectionSource: ConnectionSource = ConnectionSource.NONE,
    val bleState: BleState = BleState.Idle,
    val isStreamStale: Boolean = false,
    val isPermissionDenied: Boolean = false,
    val energyWh: Double? = null,
    val frequency: Double? = null,
    val powerFactor: Double? = null
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

    val isBleBusy: Boolean
        get() = connectionSource == ConnectionSource.BLE && (
            bleState is BleState.Scanning ||
                bleState is BleState.Connecting ||
                bleState is BleState.Reconnecting
            )
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
 * Activity-scoped ViewModel managing both the Real-Time stream (Demo PY Script or Real BLE)
 * and the Devices ledger. Scoping to MainActivity guarantees that switching bottom tabs or
 * rotating the device never interrupts the active connection, and that dynamic ledger renames
 * propagate immediately across all views.
 */
class KilowhatViewModel(
    application: Application,
    private val repository: KilowhatRepository,
    private val bleClient: KilowhatBleClient
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = KilowhatRepository(),
        bleClient = KilowhatBleClient(application.applicationContext)
    )

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
    private var applianceLookupJob: Job? = null
    private var outletLookupIdInFlight: String? = null
    private var lastStreamRfid: String? = null
    private var lastResolvedOutletId: String? = null

    init {
        observeBleClient()
    }

    private fun observeBleClient() {
        viewModelScope.launch {
            bleClient.state.collect { bleState ->
                if (_realtimeState.value.connectionSource != ConnectionSource.BLE &&
                    bleState is BleState.Idle
                ) {
                    return@collect
                }

                when (bleState) {
                    is BleState.Idle -> {
                        if (_realtimeState.value.connectionSource == ConnectionSource.BLE) {
                            _realtimeState.update {
                                it.copy(
                                    bleState = BleState.Idle,
                                    isConnected = false,
                                    isStreamStale = false
                                )
                            }
                        }
                    }

                    is BleState.Scanning -> {
                        _realtimeState.update {
                            it.copy(
                                connectionSource = ConnectionSource.BLE,
                                bleState = bleState,
                                isConnected = false,
                                isPairing = false,
                                connectionError = null,
                                isPermissionDenied = false,
                                isStreamStale = false
                            )
                        }
                    }

                    is BleState.Connecting -> {
                        _realtimeState.update {
                            it.copy(
                                connectionSource = ConnectionSource.BLE,
                                bleState = bleState,
                                isConnected = false,
                                isPairing = false,
                                connectionError = null,
                                isPermissionDenied = false,
                                isStreamStale = false
                            )
                        }
                    }

                    is BleState.Connected -> {
                        val inferredOutletId = bleState.deviceName
                            .removePrefix(KilowhatBleClient.DEVICE_NAME_PREFIX)
                            .takeIf {
                                it.isNotBlank() &&
                                    bleState.deviceName.startsWith(KilowhatBleClient.DEVICE_NAME_PREFIX)
                            }
                            .orEmpty()

                        _realtimeState.update { state ->
                            val effectiveOutletId = state.outletId.ifBlank { inferredOutletId }
                            state.copy(
                                connectionSource = ConnectionSource.BLE,
                                bleState = bleState,
                                isConnected = true,
                                isPairing = false,
                                connectionError = null,
                                isPermissionDenied = false,
                                outletId = effectiveOutletId,
                                energyWh = state.energyWh ?: 0.0,
                                frequency = state.frequency ?: 0.0,
                                powerFactor = state.powerFactor ?: 0.0
                            )
                        }

                        val outletToResolve = _realtimeState.value.outletId
                        if (outletToResolve.isNotBlank() && outletToResolve != lastResolvedOutletId) {
                            lastResolvedOutletId = outletToResolve
                            resolveOutletName(outletToResolve)
                        }
                    }

                    is BleState.Reconnecting -> {
                        lastStreamRfid = null
                        _realtimeState.update { state ->
                            state.copy(
                                connectionSource = ConnectionSource.BLE,
                                bleState = bleState,
                                isPairing = false,
                                connectionError = null,
                                isStreamStale = false
                            )
                        }
                    }

                    is BleState.Error -> {
                        lastStreamRfid = null
                        _realtimeState.update { state ->
                            state.copy(
                                connectionSource = ConnectionSource.BLE,
                                bleState = bleState,
                                isConnected = false,
                                isPairing = false,
                                isStreamStale = false,
                                connectionError = bleState.message
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            bleClient.isStale.collect { stale ->
                if (_realtimeState.value.connectionSource == ConnectionSource.BLE) {
                    _realtimeState.update { it.copy(isStreamStale = stale) }
                }
            }
        }

        viewModelScope.launch {
            bleClient.telemetry.collect { tick ->
                if (_realtimeState.value.connectionSource != ConnectionSource.BLE) return@collect
                onBleTelemetryTick(tick)
            }
        }
    }

    private fun onBleTelemetryTick(tick: Telemetry) {
        val currentState = _realtimeState.value
        val incomingOutletId = tick.outletId.trim()
        val effectiveOutletId = incomingOutletId.ifBlank { currentState.outletId }
        val outletChanged = effectiveOutletId.isNotBlank() && effectiveOutletId != currentState.outletId

        val rfid = tick.applianceRfid // null when "NONE"
        val rfidForUi = rfid ?: "NONE"
        val rfidChanged = rfid != lastStreamRfid
        lastStreamRfid = rfid

        val cachedDisplayName = if (rfid != null) applianceNameCache[rfid] else null
        val displayNameForUi = when {
            rfid == null -> null
            cachedDisplayName != null -> cachedDisplayName
            !rfidChanged && currentState.activeApplianceRfid == rfid ->
                currentState.activeApplianceDisplayName ?: rfid
            else -> rfid
        }

        _realtimeState.update { state ->
            state.copy(
                isConnected = true,
                outletId = effectiveOutletId,
                outletCustomName = if (outletChanged) null else state.outletCustomName,
                watts = tick.power,
                voltage = tick.voltage,
                current = tick.current,
                energyWh = tick.energyWh,
                frequency = tick.frequency,
                powerFactor = tick.powerFactor,
                activeApplianceRfid = rfidForUi,
                activeApplianceDisplayName = displayNameForUi
            )
        }

        if (outletChanged || (effectiveOutletId.isNotBlank() && effectiveOutletId != lastResolvedOutletId)) {
            lastResolvedOutletId = effectiveOutletId
            resolveOutletName(effectiveOutletId)
        }

        if (rfid == null) {
            applianceLookupJob?.cancel()
            applianceLookupJob = null
        } else if (rfidChanged && cachedDisplayName == null) {
            resolveApplianceNameForStream(rfid)
        }
    }

    fun showDemoPlaceholderSnackbar() {
        sendSnackbar("Not available in this demo yet")
    }

    fun sendSnackbar(message: String) {
        _snackbarMessages.trySend(message)
    }

    // ========================================================================
    // Real-Time Tab Actions
    // ========================================================================

    fun connectManualBluetooth() {
        stopDemoStreamInternal(unpairIfConnected = true)
        resetStreamLookupTracking()

        _realtimeState.value = RealtimeUiState(
            connectionSource = ConnectionSource.BLE,
            bleState = BleState.Scanning,
            isConnected = false,
            isPairing = false,
            connectionError = null,
            isPermissionDenied = false,
            isStreamStale = false,
            energyWh = 0.0,
            frequency = 0.0,
            powerFactor = 0.0
        )

        bleClient.start()
    }

    fun onBlePermissionDenied() {
        stopDemoStreamInternal(unpairIfConnected = true)
        bleClient.stop()
        resetStreamLookupTracking()

        val msg = "Bluetooth permissions are required to scan and connect to the Kilowhat adapter."
        _realtimeState.value = RealtimeUiState(
            connectionSource = ConnectionSource.BLE,
            bleState = BleState.Error(msg),
            isConnected = false,
            isPairing = false,
            connectionError = msg,
            isPermissionDenied = true
        )
    }

    fun onBluetoothEnableDeclined() {
        if (_realtimeState.value.connectionSource == ConnectionSource.DEMO_SCRIPT &&
            _realtimeState.value.isConnected
        ) {
            sendSnackbar("Bluetooth must be enabled to switch to BLE.")
            return
        }
        bleClient.stop()
        resetStreamLookupTracking()
        val msg = "Bluetooth is turned off. Enable Bluetooth to connect to the Kilowhat adapter."
        _realtimeState.value = RealtimeUiState(
            connectionSource = ConnectionSource.BLE,
            bleState = BleState.Error(msg),
            isConnected = false,
            isPairing = false,
            connectionError = msg,
            isPermissionDenied = false
        )
    }

    fun onBleHardwareUnavailable() {
        val msg = "This device does not support Bluetooth Low Energy (BLE). Use Demo PY Script mode instead."
        if (_realtimeState.value.connectionSource == ConnectionSource.DEMO_SCRIPT &&
            _realtimeState.value.isConnected
        ) {
            sendSnackbar(msg)
            return
        }
        bleClient.stop()
        resetStreamLookupTracking()
        _realtimeState.value = RealtimeUiState(
            connectionSource = ConnectionSource.NONE,
            bleState = BleState.Error(msg),
            isConnected = false,
            isPairing = false,
            connectionError = msg,
            isPermissionDenied = false
        )
        sendSnackbar(msg)
    }

    fun connectDemoScript() {
        val current = _realtimeState.value
        if (current.connectionSource == ConnectionSource.DEMO_SCRIPT &&
            (current.isPairing || current.isConnected)
        ) {
            return
        }

        // Stop BLE so only one source is active at a time.
        bleClient.stop()
        stopDemoStreamInternal(unpairIfConnected = false)
        resetStreamLookupTracking()

        _realtimeState.value = RealtimeUiState(
            connectionSource = ConnectionSource.DEMO_SCRIPT,
            bleState = BleState.Idle,
            isConnected = false,
            isPairing = true,
            connectionError = null,
            isPermissionDenied = false,
            isStreamStale = false,
            energyWh = null,
            frequency = null,
            powerFactor = null
        )

        viewModelScope.launch {
            when (val result = repository.pairSimulator()) {
                is ApiResult.Success -> {
                    val pairedOutletId = result.data.outletId.trim()
                    _realtimeState.update {
                        RealtimeUiState(
                            connectionSource = ConnectionSource.DEMO_SCRIPT,
                            bleState = BleState.Idle,
                            isConnected = true,
                            isPairing = false,
                            connectionError = null,
                            outletId = pairedOutletId
                        )
                    }
                    if (pairedOutletId.isNotEmpty()) {
                        lastResolvedOutletId = pairedOutletId
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
        val wasDemoMode = _realtimeState.value.connectionSource == ConnectionSource.DEMO_SCRIPT
        streamJob?.cancel()
        streamJob = null
        bleClient.stop()
        resetStreamLookupTracking()

        _realtimeState.value = RealtimeUiState()

        if (wasDemoMode) {
            // Best-effort unpair call; failures are intentionally ignored per specification.
            viewModelScope.launch {
                repository.unpairSimulator()
            }
        }
    }

    private fun stopDemoStreamInternal(unpairIfConnected: Boolean) {
        val wasDemoConnected = _realtimeState.value.connectionSource == ConnectionSource.DEMO_SCRIPT &&
            _realtimeState.value.isConnected
        streamJob?.cancel()
        streamJob = null
        if (unpairIfConnected && wasDemoConnected) {
            viewModelScope.launch {
                repository.unpairSimulator()
            }
        }
    }

    private fun resetStreamLookupTracking() {
        applianceLookupJob?.cancel()
        applianceLookupJob = null
        outletLookupIdInFlight = null
        lastStreamRfid = null
        lastResolvedOutletId = null
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
                        if (effectiveOutletId.isNotBlank() && effectiveOutletId != lastResolvedOutletId) {
                            lastResolvedOutletId = effectiveOutletId
                            resolveOutletName(effectiveOutletId)
                        }

                        val rawRfid = reading.activeApplianceRfid.trim().ifEmpty { "NONE" }
                        val isPluggedIn = !rawRfid.equals("NONE", ignoreCase = true)
                        val normalizedRfid = if (isPluggedIn) rawRfid else null
                        val rfidChanged = normalizedRfid != lastStreamRfid
                        lastStreamRfid = normalizedRfid

                        val cachedDisplayName = if (normalizedRfid != null) {
                            applianceNameCache[normalizedRfid]
                        } else {
                            null
                        }

                        val displayNameForUi = when {
                            normalizedRfid == null -> null
                            cachedDisplayName != null -> cachedDisplayName
                            !rfidChanged && currentState.activeApplianceRfid == normalizedRfid ->
                                currentState.activeApplianceDisplayName ?: normalizedRfid
                            else -> normalizedRfid
                        }

                        _realtimeState.update { state ->
                            state.copy(
                                outletId = effectiveOutletId,
                                watts = reading.watts,
                                voltage = reading.voltage,
                                current = reading.current,
                                activeApplianceRfid = rawRfid,
                                activeApplianceDisplayName = displayNameForUi
                            )
                        }

                        if (normalizedRfid == null) {
                            applianceLookupJob?.cancel()
                            applianceLookupJob = null
                        } else if (rfidChanged && cachedDisplayName == null) {
                            resolveApplianceNameForStream(normalizedRfid)
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
        resetStreamLookupTracking()
        _realtimeState.value = RealtimeUiState(
            connectionSource = ConnectionSource.DEMO_SCRIPT,
            isConnected = false,
            connectionError = reason
        )
        sendSnackbar(reason)
    }

    private fun resolveApplianceNameForStream(rfid: String) {
        applianceLookupJob?.cancel()
        applianceLookupJob = viewModelScope.launch {
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
                    // Keep showing raw RFID until the RFID changes again.
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

                    // 1. Update the Real-Time RFID name cache so any rename shows immediately.
                    applianceNameCache.clear()
                    applianceNameCache[saved.applianceRfid] = saved.displayName

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
        bleClient.stop()
        super.onCleared()
    }
}
