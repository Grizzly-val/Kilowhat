package com.kilowhat.app.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kilowhat.app.data.model.ApplianceSummaryDto
import com.kilowhat.app.data.model.GroupSummaryDto
import com.kilowhat.app.ui.ApplianceDetailUiState
import com.kilowhat.app.ui.DevicesUiState
import com.kilowhat.app.ui.NamingDialogUiState
import com.kilowhat.app.util.TimeFormatters
import java.util.Locale

private fun groupStorageKey(group: GroupSummaryDto): String =
    group.groupId?.toString() ?: "unassigned"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devicesState: DevicesUiState,
    detailState: ApplianceDetailUiState,
    namingDialogState: NamingDialogUiState,
    onScreenVisible: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onApplianceClick: (ApplianceSummaryDto) -> Unit,
    onDetailRetry: () -> Unit,
    onDetailDismiss: () -> Unit,
    onFabClick: () -> Unit,
    onManualInputChoiceClick: () -> Unit,
    onCreateGroupChoiceClick: () -> Unit,
    onNamingRfidChange: (String) -> Unit,
    onNamingCustomNameChange: (String) -> Unit,
    onNamingSubmit: () -> Unit,
    onGroupNameChange: (String) -> Unit,
    onToggleGroupDevice: (String) -> Unit,
    onRetryLoadGroupDevices: () -> Unit,
    onCreateGroupSubmit: () -> Unit,
    onNamingDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Store expanded state by group key (group_id or "unassigned") across refreshes and rotations.
    var expandedGroupKeys: Set<String> by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { set: Set<String> -> set.toList() },
            restore = { list: List<String> -> list.toSet() }
        )
    ) {
        mutableStateOf(emptySet<String>())
    }

    // Reload summary whenever the Devices tab becomes visible
    LaunchedEffect(Unit) {
        onScreenVisible()
    }

    if (detailState.isOpen) {
        DeviceDetailSheet(
            state = detailState,
            onRetry = onDetailRetry,
            onDismiss = onDetailDismiss
        )
    }

    RegisterApplianceDialogs(
        state = namingDialogState,
        onManualInputChoiceClick = onManualInputChoiceClick,
        onCreateGroupChoiceClick = onCreateGroupChoiceClick,
        onRfidChange = onNamingRfidChange,
        onCustomNameChange = onNamingCustomNameChange,
        onManualSubmit = onNamingSubmit,
        onGroupNameChange = onGroupNameChange,
        onToggleGroupDevice = onToggleGroupDevice,
        onRetryLoadGroupDevices = onRetryLoadGroupDevices,
        onCreateGroupSubmit = onCreateGroupSubmit,
        onDismiss = onNamingDismiss
    )

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = devicesState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                devicesState.isLoading && devicesState.groups.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading devices ledger…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                !devicesState.error.isNullOrBlank() && devicesState.groups.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = devicesState.error,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = onRetry) {
                                Text("Retry")
                            }
                        }
                    }
                }

                devicesState.groups.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DevicesOther,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(52.dp)
                            )
                            Text(
                                text = "No devices yet",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Run a session from the simulator or tap the + button below to register an appliance or create a group.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 16.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "system_metrics_header") {
                            SystemMetricsCard(totalWhConsumed = devicesState.totalWhConsumed)
                        }

                        items(
                            items = devicesState.groups,
                            key = { group -> "group_${groupStorageKey(group)}" }
                        ) { group ->
                            val key = groupStorageKey(group)
                            val isExpanded = key in expandedGroupKeys
                            ExpandableGroupCard(
                                group = group,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    expandedGroupKeys = if (isExpanded) {
                                        expandedGroupKeys - key
                                    } else {
                                        expandedGroupKeys + key
                                    }
                                },
                                onApplianceClick = onApplianceClick
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onFabClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Add appliance or group"
            )
        }
    }
}

@Composable
private fun SystemMetricsCard(totalWhConsumed: Double) {
    val formattedTotal = String.format(Locale.US, "%.3f Wh", totalWhConsumed)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "System Metrics",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = "Total household consumption",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )

            Text(
                text = formattedTotal,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ExpandableGroupCard(
    group: GroupSummaryDto,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onApplianceClick: (ApplianceSummaryDto) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )
    val formattedGroupTotal = String.format(Locale.US, "%.3f Wh", group.totalWhConsumed)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row: group name on left (single line, ellipsized), group total + chevron on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = group.groupName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formattedGroupTotal,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse group" else "Expand group",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }
            }

            // Expandable body
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )

                    if (group.appliances.isEmpty()) {
                        Text(
                            text = "No appliances in this group",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            group.appliances.forEach { item ->
                                ApplianceSummaryRowItem(
                                    item = item,
                                    onClick = { onApplianceClick(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplianceSummaryRowItem(
    item: ApplianceSummaryDto,
    onClick: () -> Unit
) {
    // Exact row label format: "<display_name> | <total_wh_consumed> Wh" (3 decimals, Locale.US)
    val formattedLine = String.format(
        Locale.US,
        "%s | %.3f Wh",
        item.displayName,
        item.totalWhConsumed
    )

    val lastUsedFormatted = TimeFormatters.formatLastUsed(item.lastUsed)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formattedLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!item.customName.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = item.applianceRfid,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    val sessionsLabel = if (item.sessionCount == 1L) "1 session" else "${item.sessionCount} sessions"
                    Text(
                        text = if (lastUsedFormatted != null) {
                            "$sessionsLabel • Last used $lastUsedFormatted"
                        } else {
                            "$sessionsLabel • Never used"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "View sessions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
