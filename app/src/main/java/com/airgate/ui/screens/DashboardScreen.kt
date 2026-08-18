/*
 * Copyright (C) 2026 The Airgate project contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.airgate.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.SecurityState
import com.airgate.engine.ThreatEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.airgate.policy.ShieldLayerStatus
import com.airgate.policy.ShieldStatusChecker
import com.airgate.ui.components.DashboardThreatScoreCard
import com.airgate.ui.components.MasterActivationCard
import com.airgate.ui.components.ModeLabel
import com.airgate.ui.components.PendingAlarmBanner
import com.airgate.ui.components.PinVerifyDialog
import com.airgate.ui.components.ShieldStatusCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: SecurityStateRepository,
    onNavigateToBreaches: () -> Unit,
    onClearStreakRequested: () -> Unit
) {
    val context = LocalContext.current
    var streak by remember { mutableIntStateOf(repository.getStreak()) }
    var securityState by remember { mutableStateOf(repository.getSecurityState()) }
    val config = repository.getConfig()
    var currentConfig by remember { mutableStateOf(config) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showClearStreakPinDialog by remember { mutableStateOf(false) }
    val pinUsable by remember { mutableStateOf(repository.isPinUsable()) }
    var notificationsGranted by remember { mutableStateOf(repository.areNotificationsAllowed()) }
    var bluetoothConnectGranted by remember { mutableStateOf(repository.isBluetoothConnectAllowed()) }
    var exactAlarmGranted by remember { mutableStateOf(repository.canScheduleExactAlarms()) }

    // Persistent in-app alarm: raised whenever the engine escalates, it survives
    // even when the real-time surfaces were silent (notifications denied / activity
    // start blocked). It is surfaced here until the owner acknowledges it (or
    // cancels a pending wipe) with the Armed PIN.
    var pendingAlarm by remember { mutableStateOf(repository.getPendingAlarm()) }
    var showAcknowledgeAlarmPinDialog by remember { mutableStateOf(false) }
    val threatEngine = remember {
        ThreatEngine(context, repository, DhizukuManager(context.applicationContext))
    }

    // Live shield layer status (Dhizuku, Wireless Blockade, USB & ADB Guard).
    // The check performs several system binder reads (user restrictions, global
    // settings, USB device list), so it runs on a background dispatcher and the
    // UI renders the "Checking status…" placeholders until it resolves.
    val shieldChecker = remember { ShieldStatusChecker(context) }
    var shieldStatuses by remember { mutableStateOf<List<ShieldLayerStatus>>(emptyList()) }
    val shieldScope = rememberCoroutineScope()

    LaunchedEffect(shieldChecker) {
        shieldStatuses = withContext(Dispatchers.Default) { shieldChecker.check() }
    }

    // Re-read persisted state whenever the activity resumes. The WatchdogService
    // may have incremented streak / changed security state while the dashboard
    // was paused (e.g. an AlarmActivity was on top after a breach), so the value
    // captured at first composition cannot be relied on.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                streak = repository.getStreak()
                securityState = repository.getSecurityState()
                notificationsGranted = repository.areNotificationsAllowed()
                bluetoothConnectGranted = repository.isBluetoothConnectAllowed()
                exactAlarmGranted = repository.canScheduleExactAlarms()
                pendingAlarm = repository.getPendingAlarm()
                shieldScope.launch {
                    shieldStatuses = withContext(Dispatchers.Default) { shieldChecker.check() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {

            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Airgate",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Air-Gapped Shield",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    ModeLabel(dryRun = config.dryRunMode)
                    Spacer(modifier = Modifier.width(20.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Persistent in-app alarm banner: presented until the owner
            // acknowledges with the Armed PIN. Never dismissable by navigation.
            val alarm = pendingAlarm
            if (alarm != null) {
                PendingAlarmBanner(
                    pendingAlarm = alarm,
                    onAcknowledge = { showAcknowledgeAlarmPinDialog = true }
                )
            }

            // Hero Threat Score Card — big, centered, alarm-style
            DashboardThreatScoreCard(
                streak = streak,
                wipeThreshold = config.wipeThreshold,
                securityState = securityState,
                onNavigateToBreaches = onNavigateToBreaches
            )

            // Master Activation Card
            MasterActivationCard(
                config = currentConfig,
                context = context,
                pinUsable = pinUsable,
                notificationsGranted = notificationsGranted,
                bluetoothConnectGranted = bluetoothConnectGranted,
                exactAlarmGranted = exactAlarmGranted,
                onEnableBlocked = {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Protection requires a usable Armed PIN, notifications, Bluetooth detection, and exact-alarm access enabled.",
                            duration = SnackbarDuration.Short
                        )
                    }
                },
                onConfigChange = { updated ->
                    val effective = repository.saveConfig(updated)
                    currentConfig = effective
                }
            )

            // Shield Status Overview Card
            ShieldStatusCard(shieldStatuses = shieldStatuses)

            Button(
                onClick = { showClearStreakPinDialog = true },
                enabled = streak > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = if (streak > 0) "Clear Threat Streak ($streak pts)" else "Streak Cleared (0 pts)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (showClearStreakPinDialog) {
                PinVerifyDialog(
                    repository = repository,
                    title = "Clear Threat Streak",
                    description = "Resetting the threat score requires your Armed PIN.",
                    confirmLabel = "Clear Streak",
                    onDismiss = { showClearStreakPinDialog = false },
                    onVerified = {
                        showClearStreakPinDialog = false
                        onClearStreakRequested()
                        streak = repository.getStreak()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Threat streak successfully reset to 0 points.",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
            }

            if (showAcknowledgeAlarmPinDialog) {
                val alarm = pendingAlarm
                if (alarm != null) {
                    PinVerifyDialog(
                        repository = repository,
                        title = if (alarm.isCountdown) "Cancel Pending Wipe" else "Acknowledge Security Alarm",
                        description = if (alarm.isCountdown)
                            "Enter your Armed PIN to cancel the pending wipe and dismiss this alarm."
                        else
                            "Enter your Armed PIN to acknowledge this alarm and dismiss the banner.",
                        confirmLabel = if (alarm.isCountdown) "Cancel Wipe" else "Acknowledge",
                        onDismiss = { showAcknowledgeAlarmPinDialog = false },
                        onVerified = {
                            showAcknowledgeAlarmPinDialog = false
                            if (alarm.isCountdown) {
                                // Cancelling a pending wipe requires the owner's PIN;
                                // merely acknowledging never stops a scheduled wipe.
                                threatEngine.cancelPendingWipe()
                                repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
                                securityState = repository.getSecurityState()
                            }
                            repository.clearPendingAlarm()
                            pendingAlarm = null
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = if (alarm.isCountdown)
                                        "Pending wipe cancelled and alarm acknowledged."
                                    else
                                        "Alarm acknowledged.",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
