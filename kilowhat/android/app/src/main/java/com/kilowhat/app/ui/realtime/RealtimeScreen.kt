package com.kilowhat.app.ui.realtime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilowhat.app.data.ble.BleState
import com.kilowhat.app.data.ble.KilowhatBleClient
import com.kilowhat.app.ui.ConnectionSource
import com.kilowhat.app.ui.RealtimeUiState
import com.kilowhat.app.ui.theme.AmberWarning
import com.kilowhat.app.ui.theme.AmberWarningContainer
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val exactNumberFormatter = DecimalFormat("0.####", DecimalFormatSymbols(Locale.US))

private fun formatTelemetryNumber(value: Double): String =
    exactNumberFormatter.format(value)

@Composable
fun RealtimeScreen(
    state: RealtimeUiState,
    onNfcClick: () -> Unit,
    onManualBluetoothClick: () -> Unit,
    onConnectDemoScript: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onEditOutletClick: () -> Unit,
    onDismissEditOutlet: () -> Unit,
    onSaveOutletName: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isEditOutletDialogOpen) {
        OutletRenameDialog(
            outletId = state.outletId,
            initialCustomName = state.outletCustomName,
            isSaving = state.isSavingOutletName,
            errorMessage = state.outletDialogError,
            onDismiss = onDismissEditOutlet,
            onConfirm = onSaveOutletName
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!state.isConnected) {
            DisconnectedRealtimeContent(
                state = state,
                onNfcClick = onNfcClick,
                onManualBluetoothClick = onManualBluetoothClick,
                onConnectDemoScript = onConnectDemoScript,
                onDisconnect = onDisconnect,
                onOpenAppSettings = onOpenAppSettings
            )
        } else {
            ConnectedRealtimeContent(
                state = state,
                onNfcClick = onNfcClick,
                onManualBluetoothClick = onManualBluetoothClick,
                onConnectDemoScript = onConnectDemoScript,
                onEditOutletClick = onEditOutletClick,
                onDisconnect = onDisconnect
            )
        }
    }
}

@Composable
private fun DisconnectedRealtimeContent(
    state: RealtimeUiState,
    onNfcClick: () -> Unit,
    onManualBluetoothClick: () -> Unit,
    onConnectDemoScript: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val (statusDotColor, statusPillContainer, statusTextColor, statusLabel) = when {
        state.connectionSource == ConnectionSource.BLE && state.bleState is BleState.Scanning ->
            StatusBadgeStyle(
                dotColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                label = "Scanning"
            )
        state.connectionSource == ConnectionSource.BLE && state.bleState is BleState.Connecting ->
            StatusBadgeStyle(
                dotColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                label = "Connecting"
            )
        state.connectionSource == ConnectionSource.BLE && state.bleState is BleState.Reconnecting ->
            StatusBadgeStyle(
                dotColor = AmberWarning,
                containerColor = AmberWarningContainer,
                textColor = AmberWarning,
                label = "Reconnecting (attempt ${state.bleState.attempt})"
            )
        state.isPairing ->
            StatusBadgeStyle(
                dotColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                label = "Connecting"
            )
        !state.connectionError.isNullOrBlank() || state.bleState is BleState.Error ->
            StatusBadgeStyle(
                dotColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                textColor = MaterialTheme.colorScheme.onErrorContainer,
                label = "Error"
            )
        else ->
            StatusBadgeStyle(
                dotColor = MaterialTheme.colorScheme.outline,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                label = "DISCONNECTED"
            )
    }

    // Hero instructions card
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ElectricalServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column {
                    Surface(
                        color = statusPillContainer,
                        shape = CircleShape
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusTextColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connect to a Smart Outlet",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Text(
                text = "Pair with a Kilowhat smart outlet adapter over BLE or the Demo Python script to stream real-time power draw (Watts, Volts, Amperes) and automatically identify plugged-in appliances by their RFID tag.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // BLE active connection progress card (Scanning, Connecting, Reconnecting)
    AnimatedVisibility(
        visible = state.isBleBusy,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val progressMessage = when (val bleState = state.bleState) {
            is BleState.Scanning -> "Scanning for Kilowhat adapter…"
            is BleState.Connecting -> "Connecting to Kilowhat adapter…"
            is BleState.Reconnecting -> "Reconnecting to adapter (attempt ${bleState.attempt})…"
            else -> "Connecting…"
        }
        val dotColor = if (state.bleState is BleState.Reconnecting) {
            AmberWarning
        } else {
            MaterialTheme.colorScheme.primary
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = dotColor
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = progressMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    // Error alert banner with Retry (and Open Settings when permissions were denied)
    AnimatedVisibility(
        visible = !state.connectionError.isNullOrBlank(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.connectionError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isPermissionDenied) {
                        OutlinedButton(
                            onClick = onOpenAppSettings,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("App Settings")
                        }
                    }

                    Button(
                        onClick = {
                            if (state.connectionSource == ConnectionSource.DEMO_SCRIPT) {
                                onConnectDemoScript()
                            } else {
                                onManualBluetoothClick()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }

    // Connection methods card
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Choose Connection Method",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Button(
                onClick = onConnectDemoScript,
                enabled = !state.isPairing,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (state.isPairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Connecting to Simulator…")
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Demo PY Script",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            FilledTonalButton(
                onClick = onNfcClick,
                enabled = false,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Connect thru NFC",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = onManualBluetoothClick,
                enabled = !state.isPairing && !state.isBleBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (state.isBleBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    val busyLabel = when (val bleState = state.bleState) {
                        is BleState.Scanning -> "Scanning…"
                        is BleState.Connecting -> "Connecting…"
                        is BleState.Reconnecting -> "Reconnecting (attempt ${bleState.attempt})…"
                        else -> "Connecting…"
                    }
                    Text(
                        text = busyLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Manual Bluetooth Connection",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedRealtimeContent(
    state: RealtimeUiState,
    onNfcClick: () -> Unit,
    onManualBluetoothClick: () -> Unit,
    onConnectDemoScript: () -> Unit,
    onEditOutletClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isBleSource = state.connectionSource == ConnectionSource.BLE
    val connectedDeviceName = when (val bleState = state.bleState) {
        is BleState.Connected -> bleState.deviceName
        else -> if (state.outletId.isNotBlank()) {
            "${KilowhatBleClient.DEVICE_NAME_PREFIX}${state.outletId}"
        } else {
            KilowhatBleClient.DEFAULT_DEVICE_NAME
        }
    }

    val (headerDotColor, headerStatusText) = when {
        isBleSource && state.bleState is BleState.Reconnecting ->
            AmberWarning to "Reconnecting (attempt ${state.bleState.attempt})"
        isBleSource && state.isStreamStale ->
            AmberWarning to "Connected to $connectedDeviceName • No data from adapter"
        isBleSource ->
            MaterialTheme.colorScheme.primary to "Connected to $connectedDeviceName"
        else ->
            MaterialTheme.colorScheme.primary to "LIVE STREAM • 1 Hz"
    }

    // 1. Outlet Header Card with Edit Button
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(headerDotColor)
                    )
                    Text(
                        text = headerStatusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = headerDotColor
                    )
                }

                Text(
                    text = state.resolvedOutletTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (state.hasCustomOutletName && state.outletId.isNotBlank()) {
                    Text(
                        text = state.outletId,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onEditOutletClick,
                enabled = state.outletId.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit Outlet Name",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // Stale stream or Reconnecting banner while viewing live telemetry
    AnimatedVisibility(
        visible = isBleSource && (state.isStreamStale || state.bleState is BleState.Reconnecting),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val bannerText = when (val bleState = state.bleState) {
            is BleState.Reconnecting ->
                "Connection interrupted. Reconnecting (attempt ${bleState.attempt})…"
            else ->
                "No data from adapter"
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = AmberWarningContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.bleState is BleState.Reconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = AmberWarning
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AmberWarning)
                )
                Text(
                    text = bannerText,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF92400E),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // 2. Primary Watts Hero Telemetry Card + Volts & Amperes Cards
    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF065F46),
                            Color(0xFF047857),
                            Color(0xFF0F766E)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = Color(0xFF6EE7B7),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "REAL-TIME POWER DRAW",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFA7F3D0)
                        )
                    }

                    if (state.isGridSurgeWarning) {
                        Surface(
                            color = AmberWarningContainer,
                            shape = CircleShape
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = AmberWarning,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "GRID SURGE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AmberWarning
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatTelemetryNumber(state.watts),
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = "W",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFFA7F3D0),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Text(
                    text = "Watts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD1FAE5)
                )

                if (state.energyWh != null && state.frequency != null && state.powerFactor != null) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Energy: %.3f Wh  •  %.1f Hz  •  PF: %.2f",
                            state.energyWh,
                            state.frequency,
                            state.powerFactor
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFA7F3D0)
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TelemetryMetricCard(
            label = "Volts",
            unit = "V",
            value = formatTelemetryNumber(state.voltage),
            isHighlighted = state.voltage >= 242.0,
            modifier = Modifier.weight(1f)
        )
        TelemetryMetricCard(
            label = "Amperes",
            unit = "A",
            value = formatTelemetryNumber(state.current),
            isHighlighted = false,
            modifier = Modifier.weight(1f)
        )
    }

    // 3. Active Appliance Card
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (state.isAppliancePluggedIn) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Power,
                    contentDescription = null,
                    tint = if (state.isAppliancePluggedIn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(26.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "ACTIVE APPLIANCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!state.isAppliancePluggedIn) {
                    Text(
                        text = "No appliance plugged in",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val displayName = state.activeApplianceDisplayName?.takeIf { it.isNotBlank() }
                        ?: state.activeApplianceRfid
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = state.activeApplianceRfid,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.isAppliancePluggedIn && state.watts == 0.0) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "STANDBY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    // 4. Connection Controls (Switch source or Disconnect)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Connection Controls",
                style = MaterialTheme.typography.titleMedium
            )

            if (isBleSource) {
                OutlinedButton(
                    onClick = onConnectDemoScript,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Switch to Demo PY Script")
                }
            }

            FilledTonalButton(
                onClick = onNfcClick,
                enabled = false,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect thru NFC")
            }

            OutlinedButton(
                onClick = onManualBluetoothClick,
                enabled = !isBleSource,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manual Bluetooth Connection")
            }

            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LinkOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Disconnect",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private data class StatusBadgeStyle(
    val dotColor: Color,
    val containerColor: Color,
    val textColor: Color,
    val label: String
)

@Composable
private fun TelemetryMetricCard(
    label: String,
    unit: String,
    value: String,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                AmberWarningContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label.uppercase(Locale.US),
                style = MaterialTheme.typography.labelMedium,
                color = if (isHighlighted) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isHighlighted) Color(0xFF92400E) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isHighlighted) AmberWarning else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}
