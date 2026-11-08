package com.kilowhat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kilowhat.app.ui.devices.DevicesScreen
import com.kilowhat.app.ui.realtime.RealtimeScreen

enum class KilowhatTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    REALTIME(
        route = "realtime",
        label = "Real-Time",
        selectedIcon = Icons.Filled.Bolt,
        unselectedIcon = Icons.Outlined.Bolt
    ),
    DEVICES(
        route = "devices",
        label = "Devices",
        selectedIcon = Icons.Filled.DevicesOther,
        unselectedIcon = Icons.Outlined.DevicesOther
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KilowhatApp(viewModel: KilowhatViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val realtimeState by viewModel.realtimeState.collectAsStateWithLifecycle()
    val devicesState by viewModel.devicesState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val namingDialogState by viewModel.namingDialogState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "Kilowhat",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    if (realtimeState.isConnected) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Paired",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                KilowhatTab.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(text = tab.label)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = KilowhatTab.REALTIME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(KilowhatTab.REALTIME.route) {
                RealtimeScreen(
                    state = realtimeState,
                    onNfcClick = viewModel::showDemoPlaceholderSnackbar,
                    onManualBluetoothClick = viewModel::showDemoPlaceholderSnackbar,
                    onConnectDemoScript = viewModel::connectDemoScript,
                    onDisconnect = viewModel::disconnect,
                    onEditOutletClick = viewModel::openEditOutletDialog,
                    onDismissEditOutlet = viewModel::dismissEditOutletDialog,
                    onSaveOutletName = viewModel::saveOutletName
                )
            }

            composable(KilowhatTab.DEVICES.route) {
                DevicesScreen(
                    devicesState = devicesState,
                    detailState = detailState,
                    namingDialogState = namingDialogState,
                    onScreenVisible = { viewModel.loadDevicesSummary(isRefresh = false) },
                    onRefresh = { viewModel.loadDevicesSummary(isRefresh = true) },
                    onRetry = { viewModel.loadDevicesSummary(isRefresh = false) },
                    onApplianceClick = viewModel::openApplianceDetail,
                    onDetailRetry = viewModel::retryApplianceDetail,
                    onDetailDismiss = viewModel::closeApplianceDetail,
                    onFabClick = {
                        // Prefill with the currently active RFID on the Real-Time stream if one is plugged in
                        val prefill = if (realtimeState.isConnected && realtimeState.isAppliancePluggedIn) {
                            realtimeState.activeApplianceRfid
                        } else {
                            ""
                        }
                        viewModel.openNamingChoiceDialog(prefillRfid = prefill)
                    },
                    onManualInputChoiceClick = viewModel::proceedToManualNamingForm,
                    onCreateGroupChoiceClick = viewModel::proceedToCreateGroupForm,
                    onNamingRfidChange = viewModel::updateNamingRfidInput,
                    onNamingCustomNameChange = viewModel::updateNamingCustomNameInput,
                    onNamingSubmit = viewModel::submitApplianceName,
                    onGroupNameChange = viewModel::updateGroupNameInput,
                    onToggleGroupDevice = viewModel::toggleGroupDeviceSelection,
                    onRetryLoadGroupDevices = viewModel::loadCreateGroupDevices,
                    onCreateGroupSubmit = viewModel::submitCreateGroup,
                    onNamingDismiss = viewModel::dismissNamingDialog
                )
            }
        }
    }
}
