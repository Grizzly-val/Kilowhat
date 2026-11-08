package com.kilowhat.app.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kilowhat.app.ui.NamingDialogStep
import com.kilowhat.app.ui.NamingDialogUiState
import com.kilowhat.app.ui.SelectableDeviceUiItem

@Composable
fun RegisterApplianceDialogs(
    state: NamingDialogUiState,
    onManualInputChoiceClick: () -> Unit,
    onCreateGroupChoiceClick: () -> Unit,
    onRfidChange: (String) -> Unit,
    onCustomNameChange: (String) -> Unit,
    onManualSubmit: () -> Unit,
    onGroupNameChange: (String) -> Unit,
    onToggleGroupDevice: (String) -> Unit,
    onRetryLoadGroupDevices: () -> Unit,
    onCreateGroupSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state.step) {
        NamingDialogStep.NONE -> Unit

        NamingDialogStep.CHOICE_PROMPT -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        text = "Device & Group Actions",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Register an appliance RFID tag with a custom name or organize your devices into a group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Option 1: "Use NFC" — visible but disabled, does nothing
                        FilledTonalButton(
                            onClick = {},
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Use NFC",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        // Option 2: "Manual Input"
                        Button(
                            onClick = onManualInputChoiceClick,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EditNote,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Manual Input",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        // Option 3: "Create Group"
                        FilledTonalButton(
                            onClick = onCreateGroupChoiceClick,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Create Group",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }

        NamingDialogStep.MANUAL_FORM -> {
            AlertDialog(
                onDismissRequest = { if (!state.isSaving) onDismiss() },
                title = {
                    Text(
                        text = "Manual Input",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Enter the raw appliance RFID string and the custom name you want to display.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = state.rfidInput,
                            onValueChange = onRfidChange,
                            label = { Text("Appliance RFID") },
                            placeholder = { Text("e.g. RFID_TAG_A771B") },
                            singleLine = true,
                            enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = state.customNameInput,
                            onValueChange = onCustomNameChange,
                            label = { Text("Custom Name") },
                            placeholder = { Text("e.g. Fridge") },
                            singleLine = true,
                            enabled = !state.isSaving,
                            isError = !state.inlineError.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (!state.inlineError.isNullOrBlank()) {
                            Text(
                                text = state.inlineError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onManualSubmit,
                        enabled = !state.isSaving
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Save")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !state.isSaving
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        NamingDialogStep.CREATE_GROUP_FORM -> {
            val isSaveEnabled = state.groupNameInput.trim().isNotEmpty() && !state.isSaving

            AlertDialog(
                onDismissRequest = { if (!state.isSaving) onDismiss() },
                title = {
                    Text(
                        text = "Create Group",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = state.groupNameInput,
                            onValueChange = onGroupNameChange,
                            label = { Text("Group Name") },
                            placeholder = { Text("e.g. Maria's Devices") },
                            singleLine = true,
                            enabled = !state.isSaving,
                            isError = !state.inlineError.isNullOrBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (!state.inlineError.isNullOrBlank()) {
                            Text(
                                text = state.inlineError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        Text(
                            text = "Assign Devices (Optional)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        when {
                            state.isLoadingDevices -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "Loading devices…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            !state.devicesLoadError.isNullOrBlank() -> {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ErrorOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = state.devicesLoadError,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = onRetryLoadGroupDevices,
                                            enabled = !state.isSaving
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }

                            state.availableDevices.isEmpty() -> {
                                Text(
                                    text = "No devices yet. You can still create the group.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }

                            else -> {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                ) {
                                    items(
                                        items = state.availableDevices,
                                        key = { it.applianceRfid }
                                    ) { device ->
                                        SelectableDeviceRow(
                                            device = device,
                                            checked = device.applianceRfid in state.selectedRfids,
                                            enabled = !state.isSaving,
                                            onToggle = { onToggleGroupDevice(device.applianceRfid) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onCreateGroupSubmit,
                        enabled = isSaveEnabled
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Save")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !state.isSaving
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun SelectableDeviceRow(
    device: SelectableDeviceUiItem,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.applianceRfid,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!device.currentGroupName.isNullOrBlank()) {
                    Text(
                        text = "Currently in: ${device.currentGroupName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
