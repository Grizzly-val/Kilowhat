package com.kilowhat.app.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.kilowhat.app.data.network.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

sealed class BleState {
    data object Idle : BleState()
    data object Scanning : BleState()
    data object Connecting : BleState()
    data class Connected(val deviceName: String) : BleState()
    data class Reconnecting(val attempt: Int) : BleState()
    data class Error(val message: String) : BleState()
}

data class Telemetry(
    val outletId: String,
    val applianceRfid: String?,
    val voltage: Double,
    val current: Double,
    val power: Double,
    val energyWh: Double,
    val frequency: Double,
    val powerFactor: Double,
    val receivedAtMillis: Long
)

@Serializable
private data class BleTelemetryPayloadDto(
    @SerialName("outlet_id") val outletId: String? = null,
    @SerialName("active_appliance_rfid") val activeApplianceRfid: String? = null,
    @SerialName("voltage") val voltage: Double? = null,
    @SerialName("current") val current: Double? = null,
    @SerialName("power") val power: Double? = null,
    @SerialName("watts") val watts: Double? = null,
    @SerialName("energy") val energy: Double? = null,
    @SerialName("frequency") val frequency: Double? = null,
    @SerialName("power_factor") val powerFactor: Double? = null
)

/**
 * Owns BLE scanning, GATT connection lifecycle, sequential GATT operation queueing,
 * JSON chunk reassembly, stale-stream detection, and exponential backoff reconnection
 * for the Kilowhat ESP32 smart outlet node.
 */
class KilowhatBleClient(
    context: Context,
    private val json: Json = NetworkModule.json
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val reassemblyLock = Any()

    private val _state = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _isStale = MutableStateFlow(false)
    val isStale: StateFlow<Boolean> = _isStale.asStateFlow()

    private val _telemetry = MutableSharedFlow<Telemetry>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    private var isRunning: Boolean = false
    private var isScanning: Boolean = false
    private var activeScanner: BluetoothLeScanner? = null
    private var activeGatt: BluetoothGatt? = null
    private var activeDeviceName: String = DEFAULT_DEVICE_NAME
    private var reconnectAttempt: Int = 0

    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var staleWatchdogJob: Job? = null
    private var isReceiverRegistered: Boolean = false

    private val operationQueue = ArrayDeque<GattOperation>()
    private var operationInFlight: GattOperation? = null
    private val reassemblyBuffer = StringBuilder()

    private sealed interface GattOperation {
        data class RequestMtu(val mtu: Int) : GattOperation
        data object DiscoverServices : GattOperation
        data class EnableNotifications(
            val characteristic: BluetoothGattCharacteristic,
            val cccd: BluetoothGattDescriptor
        ) : GattOperation
        data class ReadCharacteristic(
            val characteristic: BluetoothGattCharacteristic
        ) : GattOperation
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val adapterState = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR
            )
            if (adapterState == BluetoothAdapter.STATE_TURNING_OFF ||
                adapterState == BluetoothAdapter.STATE_OFF
            ) {
                synchronized(lock) {
                    if (!isRunning) return
                    Log.w(TAG, "Bluetooth adapter turned off mid-stream")
                    isRunning = false
                    cancelJobsLocked()
                    stopScanLocked()
                    closeGattLocked()
                    unregisterBluetoothStateReceiverLocked()
                    _isStale.value = false
                    updateState(BleState.Error("Bluetooth was turned off. Turn on Bluetooth and tap Retry."))
                }
            }
        }
    }

    fun start() {
        synchronized(lock) {
            // Clean up any prior session before starting fresh.
            cancelJobsLocked()
            stopScanLocked()
            closeGattLocked()
            _isStale.value = false
            reconnectAttempt = 0

            if (!hasBleHardware(appContext)) {
                isRunning = false
                updateState(BleState.Error("This device does not support Bluetooth Low Energy."))
                return
            }

            if (!hasRequiredPermissions(appContext)) {
                isRunning = false
                updateState(BleState.Error("Bluetooth permissions are required to scan for the Kilowhat adapter."))
                return
            }

            if (!isBluetoothEnabled()) {
                isRunning = false
                updateState(BleState.Error("Bluetooth is turned off. Please enable Bluetooth and try again."))
                return
            }

            isRunning = true
            registerBluetoothStateReceiverLocked()
            startScanLocked(isReconnect = false, attempt = 0)
        }
    }

    fun stop() {
        synchronized(lock) {
            isRunning = false
            reconnectAttempt = 0
            cancelJobsLocked()
            stopScanLocked()
            closeGattLocked()
            unregisterBluetoothStateReceiverLocked()
            _isStale.value = false
            updateState(BleState.Idle)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanLocked(isReconnect: Boolean, attempt: Int) {
        if (!isRunning) return

        if (!hasScanPermission(appContext)) {
            isRunning = false
            unregisterBluetoothStateReceiverLocked()
            updateState(BleState.Error("Bluetooth scan permission was revoked."))
            return
        }

        val adapter = getBluetoothAdapter()
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            isRunning = false
            unregisterBluetoothStateReceiverLocked()
            updateState(BleState.Error("Bluetooth is unavailable or turned off."))
            return
        }

        stopScanLocked()

        if (!isReconnect) {
            updateState(BleState.Scanning)
        } else {
            updateState(BleState.Reconnecting(attempt))
        }

        // Include the service UUID filter as primary and an unfiltered fallback so devices
        // whose 31-byte advertisement omits the 128-bit UUID are still matched by "Kilowhat_" prefix.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build(),
            ScanFilter.Builder().build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val started = runCatching {
            scanner.startScan(filters, settings, scanCallback)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed to start BLE scan: ${e.message}")
            false
        }

        if (!started) {
            scheduleReconnect(null)
            return
        }

        isScanning = true
        activeScanner = scanner

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            synchronized(lock) {
                if (!isRunning || !isScanning) return@launch
                Log.w(TAG, "BLE scan timed out after ${SCAN_TIMEOUT_MS}ms")
                stopScanLocked()
                isRunning = false
                unregisterBluetoothStateReceiverLocked()
                _isStale.value = false
                updateState(BleState.Error(SCAN_TIMEOUT_MESSAGE))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanLocked() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val scanner = activeScanner
        activeScanner = null
        if (!isScanning || scanner == null) {
            isScanning = false
            return
        }
        isScanning = false
        if (!hasScanPermission(appContext)) return
        runCatching {
            scanner.stopScan(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanCandidate(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                if (handleScanCandidate(result)) break
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with errorCode=$errorCode")
            scheduleReconnect(null)
        }
    }

    private fun handleScanCandidate(result: ScanResult): Boolean {
        val matchedName = matchKilowhatScanResult(result) ?: return false

        synchronized(lock) {
            if (!isRunning || !isScanning) return false
            stopScanLocked()
            activeDeviceName = matchedName
            if (_state.value !is BleState.Reconnecting) {
                updateState(BleState.Connecting)
            }
            connectToDeviceLocked(result.device)
            return true
        }
    }

    private fun matchKilowhatScanResult(result: ScanResult): String? {
        val scanRecord = result.scanRecord
        val hasServiceUuid = scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        val advertisedName = scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: getDeviceNameSafely(result.device)
        val hasPrefixMatch = advertisedName?.startsWith(DEVICE_NAME_PREFIX) == true

        if (!hasServiceUuid && !hasPrefixMatch) return null
        return advertisedName?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceNameSafely(device: BluetoothDevice): String? {
        if (!hasConnectPermission(appContext)) return null
        return runCatching { device.name }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDeviceLocked(device: BluetoothDevice) {
        if (!hasConnectPermission(appContext)) {
            isRunning = false
            unregisterBluetoothStateReceiverLocked()
            updateState(BleState.Error("Bluetooth connect permission was revoked."))
            return
        }

        closeGattLocked()

        val gatt = runCatching {
            device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }.getOrElse { e ->
            Log.e(TAG, "connectGatt failed: ${e.message}")
            null
        }

        if (gatt == null) {
            scheduleReconnect(null)
            return
        }

        activeGatt = gatt
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                if (gatt !== activeGatt) {
                    disconnectAndCloseGattSafely(gatt)
                    return
                }
                if (!isRunning) {
                    closeGattLocked(gatt)
                    return
                }

                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "GATT connected to $activeDeviceName; queueing MTU and service discovery")
                    operationQueue.clear()
                    operationInFlight = null
                    clearReassemblyBuffer()
                    operationQueue.addLast(GattOperation.RequestMtu(TARGET_MTU))
                    operationQueue.addLast(GattOperation.DiscoverServices)
                    drainNextOperationLocked(gatt)
                    return
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    val reason = if (status == GATT_STATUS_133) {
                        "Transient GATT status 133; retrying"
                    } else {
                        "GATT disconnected (status=$status, newState=$newState)"
                    }
                    Log.w(TAG, reason)
                    scheduleReconnect(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(lock) {
                if (gatt !== activeGatt || !isRunning) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU negotiated to $mtu bytes")
                } else {
                    Log.w(TAG, "MTU request failed (status=$status, mtu=$mtu); continuing with service discovery")
                }
                operationInFlight = null
                drainNextOperationLocked(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(lock) {
                if (gatt !== activeGatt || !isRunning) return
                operationInFlight = null

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed with status=$status")
                    scheduleReconnect(gatt)
                    return
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "Kilowhat BLE service $SERVICE_UUID not found on device")
                    scheduleReconnect(gatt)
                    return
                }

                val characteristic = service.getCharacteristic(TELEMETRY_CHAR_UUID)
                if (characteristic == null) {
                    Log.w(TAG, "Telemetry characteristic $TELEMETRY_CHAR_UUID not found")
                    scheduleReconnect(gatt)
                    return
                }

                val cccd = characteristic.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    Log.w(TAG, "CCCD descriptor $CCCD_UUID not found on telemetry characteristic")
                    scheduleReconnect(gatt)
                    return
                }

                operationQueue.addLast(GattOperation.EnableNotifications(characteristic, cccd))
                operationQueue.addLast(GattOperation.ReadCharacteristic(characteristic))
                drainNextOperationLocked(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            synchronized(lock) {
                if (gatt !== activeGatt || !isRunning) return
                operationInFlight = null

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "CCCD write failed with status=$status")
                    scheduleReconnect(gatt)
                    return
                }

                reconnectAttempt = 0
                _isStale.value = false
                updateState(BleState.Connected(activeDeviceName))
                restartStaleWatchdogLocked()
                drainNextOperationLocked(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicReadCallback(gatt, characteristic.uuid, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val bytes = characteristic.value ?: ByteArray(0)
            handleCharacteristicReadCallback(gatt, characteristic.uuid, bytes, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotification(gatt, characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val bytes = characteristic.value ?: return
            handleCharacteristicNotification(gatt, characteristic.uuid, bytes)
        }
    }

    private fun handleCharacteristicReadCallback(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray,
        status: Int
    ) {
        synchronized(lock) {
            if (gatt !== activeGatt || !isRunning) return
            operationInFlight = null
            drainNextOperationLocked(gatt)
        }

        if (status == BluetoothGatt.GATT_SUCCESS && characteristicUuid == TELEMETRY_CHAR_UUID) {
            if (value.isNotEmpty()) {
                onRawPayloadChunk(value)
            }
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Initial characteristic read returned status=$status")
        }
    }

    private fun handleCharacteristicNotification(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray
    ) {
        synchronized(lock) {
            if (gatt !== activeGatt || !isRunning) return
        }
        if (characteristicUuid == TELEMETRY_CHAR_UUID && value.isNotEmpty()) {
            onRawPayloadChunk(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainNextOperationLocked(gatt: BluetoothGatt) {
        if (operationInFlight != null) return
        val nextOp = operationQueue.removeFirstOrNull() ?: return

        if (!hasConnectPermission(appContext)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission for GATT operation")
            scheduleReconnect(gatt)
            return
        }

        operationInFlight = nextOp

        when (nextOp) {
            is GattOperation.RequestMtu -> {
                val started = runCatching { gatt.requestMtu(nextOp.mtu) }.getOrDefault(false)
                if (!started) {
                    Log.w(TAG, "requestMtu(${nextOp.mtu}) failed to start; continuing to discoverServices")
                    operationInFlight = null
                    drainNextOperationLocked(gatt)
                }
            }

            is GattOperation.DiscoverServices -> {
                val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                if (!started) {
                    Log.w(TAG, "discoverServices() failed to start")
                    operationInFlight = null
                    scheduleReconnect(gatt)
                }
            }

            is GattOperation.EnableNotifications -> {
                val notifySet = runCatching {
                    gatt.setCharacteristicNotification(nextOp.characteristic, true)
                }.getOrDefault(false)

                if (!notifySet) {
                    Log.w(TAG, "setCharacteristicNotification returned false")
                    operationInFlight = null
                    scheduleReconnect(gatt)
                    return
                }

                val descriptorWriteStarted = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(
                            nextOp.cccd,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            nextOp.cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(nextOp.cccd)
                        }
                    }
                }.getOrDefault(false)

                if (!descriptorWriteStarted) {
                    Log.w(TAG, "writeDescriptor(CCCD) failed to start")
                    operationInFlight = null
                    scheduleReconnect(gatt)
                }
            }

            is GattOperation.ReadCharacteristic -> {
                val readStarted = runCatching {
                    gatt.readCharacteristic(nextOp.characteristic)
                }.getOrDefault(false)

                if (!readStarted) {
                    Log.w(TAG, "Initial readCharacteristic failed to start; waiting for notifications")
                    operationInFlight = null
                    drainNextOperationLocked(gatt)
                }
            }
        }
    }

    private fun onRawPayloadChunk(bytes: ByteArray) {
        val chunk = try {
            bytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode UTF-8 BLE payload: ${e.message}")
            return
        }
        if (chunk.isEmpty()) return

        val completedObjects = mutableListOf<String>()

        synchronized(reassemblyLock) {
            reassemblyBuffer.append(chunk)
            if (reassemblyBuffer.length > MAX_REASSEMBLY_BUFFER_BYTES) {
                Log.w(
                    TAG,
                    "BLE reassembly buffer exceeded $MAX_REASSEMBLY_BUFFER_BYTES bytes; clearing"
                )
                reassemblyBuffer.clear()
                return
            }

            while (true) {
                val firstBrace = reassemblyBuffer.indexOf('{')
                if (firstBrace < 0) {
                    if (reassemblyBuffer.isNotEmpty() && reassemblyBuffer.any { !it.isWhitespace() }) {
                        Log.w(TAG, "Discarding non-JSON characters from BLE buffer")
                    }
                    reassemblyBuffer.clear()
                    break
                }
                if (firstBrace > 0) {
                    reassemblyBuffer.delete(0, firstBrace)
                }

                var depth = 0
                var inString = false
                var escaped = false
                var endIdx = -1

                for (i in 0 until reassemblyBuffer.length) {
                    val ch = reassemblyBuffer[i]
                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (ch == '\\' && inString) {
                        escaped = true
                        continue
                    }
                    if (ch == '"') {
                        inString = !inString
                        continue
                    }
                    if (!inString) {
                        when (ch) {
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) {
                                    endIdx = i
                                    break
                                } else if (depth < 0) {
                                    Log.w(TAG, "Unbalanced braces in BLE stream; clearing buffer")
                                    reassemblyBuffer.clear()
                                    break
                                }
                            }
                        }
                    }
                }

                if (endIdx < 0) {
                    // Wait for the next notification chunk to complete the JSON object.
                    break
                }

                val jsonObjectStr = reassemblyBuffer.substring(0, endIdx + 1)
                reassemblyBuffer.delete(0, endIdx + 1)
                while (reassemblyBuffer.isNotEmpty() && reassemblyBuffer[0].isWhitespace()) {
                    reassemblyBuffer.deleteCharAt(0)
                }
                completedObjects.add(jsonObjectStr)
            }
        }

        for (jsonStr in completedObjects) {
            parseAndEmitTelemetry(jsonStr)
        }
    }

    private fun parseAndEmitTelemetry(rawJson: String) {
        val dto = try {
            json.decodeFromString<BleTelemetryPayloadDto>(rawJson)
        } catch (e: Exception) {
            Log.w(TAG, "Skipping malformed BLE JSON tick: ${e.message}")
            return
        }

        val outletId = dto.outletId?.trim()?.takeIf { it.isNotEmpty() }
        val rawRfid = dto.activeApplianceRfid?.trim()?.takeIf { it.isNotEmpty() }
        val voltage = dto.voltage?.takeIf { it.isFinite() }
        val current = dto.current?.takeIf { it.isFinite() }
        val resolvedPower = (dto.power ?: dto.watts)?.takeIf { it.isFinite() }
        val energyWh = dto.energy?.takeIf { it.isFinite() }
        val frequency = dto.frequency?.takeIf { it.isFinite() }
        val powerFactor = dto.powerFactor?.takeIf { it.isFinite() }

        if (outletId == null ||
            rawRfid == null ||
            voltage == null ||
            current == null ||
            resolvedPower == null ||
            energyWh == null ||
            frequency == null ||
            powerFactor == null
        ) {
            Log.w(TAG, "Skipping BLE tick due to missing or invalid telemetry field")
            return
        }

        val applianceRfid = if (rawRfid.equals("NONE", ignoreCase = true)) {
            null
        } else {
            rawRfid
        }

        val telemetry = Telemetry(
            outletId = outletId,
            applianceRfid = applianceRfid,
            voltage = voltage,
            current = current,
            power = resolvedPower,
            energyWh = energyWh,
            frequency = frequency,
            powerFactor = powerFactor,
            receivedAtMillis = System.currentTimeMillis()
        )

        synchronized(lock) {
            if (!isRunning) return
            _isStale.value = false
            restartStaleWatchdogLocked()
        }

        _telemetry.tryEmit(telemetry)
    }

    private fun scheduleReconnect(gatt: BluetoothGatt?) {
        synchronized(lock) {
            staleWatchdogJob?.cancel()
            staleWatchdogJob = null
            _isStale.value = false
            stopScanLocked()
            closeGattLocked(gatt)

            if (!isRunning) {
                updateState(BleState.Idle)
                return
            }

            if (!isBluetoothEnabled()) {
                isRunning = false
                unregisterBluetoothStateReceiverLocked()
                updateState(BleState.Error("Bluetooth was turned off. Turn on Bluetooth and tap Retry."))
                return
            }

            reconnectAttempt += 1
            val attempt = reconnectAttempt
            val backoffMs = (1_000L shl (attempt - 1).coerceAtMost(3)).coerceAtMost(MAX_BACKOFF_MS)
            updateState(BleState.Reconnecting(attempt))

            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(backoffMs)
                synchronized(lock) {
                    if (!isRunning) return@launch
                    startScanLocked(isReconnect = true, attempt = attempt)
                }
            }
        }
    }

    private fun restartStaleWatchdogLocked() {
        staleWatchdogJob?.cancel()
        staleWatchdogJob = scope.launch {
            delay(STALE_STREAM_TIMEOUT_MS)
            synchronized(lock) {
                if (!isRunning || _state.value !is BleState.Connected) return@launch
                if (!_isStale.value) {
                    _isStale.value = true
                    Log.w(TAG, "No BLE notification received for ${STALE_STREAM_TIMEOUT_MS}ms; stream marked stale")
                }
            }
        }
    }

    private fun cancelJobsLocked() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        staleWatchdogJob?.cancel()
        staleWatchdogJob = null
    }

    private fun closeGattLocked(gattToClose: BluetoothGatt? = activeGatt) {
        val gatt = gattToClose ?: activeGatt
        if (gatt != null && gatt === activeGatt) {
            activeGatt = null
        }
        operationQueue.clear()
        operationInFlight = null
        clearReassemblyBuffer()
        if (gatt != null) {
            disconnectAndCloseGattSafely(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAndCloseGattSafely(gatt: BluetoothGatt) {
        if (hasConnectPermission(appContext)) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        } else {
            runCatching { gatt.close() }
        }
    }

    private fun clearReassemblyBuffer() {
        synchronized(reassemblyLock) {
            reassemblyBuffer.clear()
        }
    }

    private fun registerBluetoothStateReceiverLocked() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    bluetoothStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext.registerReceiver(bluetoothStateReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterBluetoothStateReceiverLocked() {
        if (!isReceiverRegistered) return
        isReceiverRegistered = false
        runCatching {
            appContext.unregisterReceiver(bluetoothStateReceiver)
        }
    }

    private fun updateState(newState: BleState) {
        val previous = _state.value
        if (previous != newState) {
            _state.value = newState
            Log.i(TAG, "BLE state -> ${formatStateForLog(newState)}")
        }
    }

    private fun formatStateForLog(state: BleState): String = when (state) {
        is BleState.Idle -> "Idle"
        is BleState.Scanning -> "Scanning"
        is BleState.Connecting -> "Connecting"
        is BleState.Connected -> "Connected(${state.deviceName})"
        is BleState.Reconnecting -> "Reconnecting(attempt=${state.attempt})"
        is BleState.Error -> "Error(${state.message})"
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private fun isBluetoothEnabled(): Boolean =
        getBluetoothAdapter()?.isEnabled == true

    companion object {
        const val TAG = "KilowhatBLE"
        const val DEVICE_NAME_PREFIX = "Kilowhat_"
        const val DEFAULT_DEVICE_NAME = "Kilowhat_OUTLET_NODE_01"

        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)
        val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 247
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val STALE_STREAM_TIMEOUT_MS = 3_000L
        private const val MAX_BACKOFF_MS = 8_000L
        private const val MAX_REASSEMBLY_BUFFER_BYTES = 1024
        private const val GATT_STATUS_133 = 133

        const val SCAN_TIMEOUT_MESSAGE =
            "Kilowhat adapter not found. Is the ESP32 powered and advertising?"

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasBleHardware(context: Context): Boolean {
            val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            return hasFeature && adapter != null
        }

        fun hasScanPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

        fun hasConnectPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        fun hasRequiredPermissions(context: Context): Boolean =
            hasScanPermission(context) && hasConnectPermission(context)

        fun isBluetoothAdapterEnabled(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }
}
