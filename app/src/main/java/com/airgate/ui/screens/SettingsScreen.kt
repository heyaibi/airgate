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

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.policy.DevicePolicyEnforcer
import com.airgate.service.SafetyNetScheduler
import com.airgate.service.WatchdogService
import com.airgate.ui.components.BackNavigationTopAppBar
import com.airgate.ui.components.DeveloperTestingCard
import com.airgate.ui.components.HardeningWipeScopeCard
import com.airgate.ui.components.MasterControlsCard
import com.airgate.ui.components.PinSecurityCard
import com.airgate.ui.components.PostureTamperAlarmsCard
import com.airgate.ui.components.PrimaryActionButton
import com.airgate.ui.components.RequiredPermissionsCard
import com.airgate.ui.components.SettingToggleRow
import com.airgate.ui.components.ThresholdsTimersCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings hub. Owns the screen's shared state (config, permission grants, ADB-block
 * verification status) and composes one focused card per concern; each card lives in
 * its own file and renders itself from the state passed down here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SecurityStateRepository,
    onNavigateToPinManagement: () -> Unit,
    onBack: () -> Unit,
    useSystemColors: Boolean,
    onUseSystemColorsChange: (Boolean) -> Unit
) {

    val context = LocalContext.current
    val dhizukuManager = remember { DhizukuManager(context) }
    val blockEnforcer = remember { DevicePolicyEnforcer(context, dhizukuManager) }
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    var config by remember { mutableStateOf(repository.getConfig()) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDryRunDisableDialog by remember { mutableStateOf(false) }
    var showPinRequiredDialog by remember { mutableStateOf(false) }
    var showNotificationsRequiredDialog by remember { mutableStateOf(false) }
    var showBluetoothRequiredDialog by remember { mutableStateOf(false) }
    val pinUsable by remember { mutableStateOf(repository.isPinUsable()) }
    var dhizukuGranted by remember { mutableStateOf(dhizukuManager.isDhizukuAvailable()) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var notificationsGranted by remember { mutableStateOf(repository.areNotificationsAllowed()) }
    var bluetoothConnectGranted by remember { mutableStateOf(repository.isBluetoothConnectAllowed()) }
    var blockStatus by remember { mutableStateOf("") }
    var blockIsError by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()

    // Debounce config persistence: dragging a slider fires onValueChange many
    // times per second, and each repository.saveConfig() performs ~17 keystore
    // encrypts (binder round-trips). Persisting on every tick would make the
    // settings screen janky. The UI state updates immediately; the disk write is
    // coalesced into one after the user stops changing values. The write is
    // flushed on dispose so the last change is never lost. The job holder is a
    // plain remember (not a state) so re-assigning it never triggers recomposition.
    val saveJobHolder = remember { arrayOfNulls<Job>(1) }
    val dirtyHolder = remember { arrayOf(false) }
    fun saveConfig(newConfig: AppConfig) {
        // The watchdog must never be enabled without a usable Armed PIN.
        // Coerce here so the local UI state also snaps back, mirroring the
        // central guard in SecurityStateRepository.saveConfig().
        val effective = if (newConfig.isEnabled && !repository.isPinUsable()) {
            newConfig.copy(isEnabled = false)
        } else {
            newConfig
        }
        config = effective
        dirtyHolder[0] = true
        saveJobHolder[0]?.cancel()
        saveJobHolder[0] = settingsScope.launch {
            delay(300)
            repository.saveConfig(config)
            dirtyHolder[0] = false
        }
    }
    // Immediately persist the current config. Used by controls whose side effects
    // (starting/cancelling the watchdog or safety-net alarm) read back isEnabled
    // from the repository, so those writes must land before the service is told.
    fun flushConfigNow() {
        saveJobHolder[0]?.cancel()
        repository.saveConfig(config)
        dirtyHolder[0] = false
    }
    DisposableEffect(Unit) {
        onDispose {
            saveJobHolder[0]?.cancel()
            // Flush the last change if the debounce timer hadn't fired yet.
            if (dirtyHolder[0]) {
                repository.saveConfig(config)
            }
        }
    }

    // Block Debugging Features: real enforcement, verified against the device, with
    // a truthful status. Status is recomputed on resume and after every toggle.
    fun refreshBlockStatus() {
        val effective = blockEnforcer.isDebuggingBlockEffective(config)
        when {
            !dhizukuGranted -> {
                blockStatus = "Requires Dhizuku grant — cannot block ADB."
                blockIsError = true
            }
            !effective -> {
                blockStatus = if (config.blockDebuggingFeatures) {
                    "ADB is NOT blocked on this device."
                } else {
                    "ADB was not restored."
                }
                blockIsError = true
            }
            else -> {
                blockStatus = if (config.blockDebuggingFeatures) {
                    "ADB blocked and verified."
                } else {
                    "ADB enabled — recovery available."
                }
                blockIsError = false
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset to Default Settings?", fontWeight = FontWeight.Bold) },
            text = { Text("This will restore all threshold limits, timers, and security posture choices to factory default values.") },
            confirmButton = {
                Button(
                    onClick = {
                        saveConfig(AppConfig())
                        // Factory defaults disable the watchdog; cancel the periodic
                        // audit alarm so it stops waking the device. The service
                        // itself keeps running (detectors remain registered).
                        SafetyNetScheduler.cancel(context)
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Defaults")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDryRunDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDryRunDisableDialog = false },
            title = { Text("Turn OFF Dry-Run Mode?", fontWeight = FontWeight.Bold) },
            text = { Text("With dry-run OFF, the app will perform a REAL factory reset (or user removal) the moment the threat threshold is reached. A wipe is unrecoverable — there is no undo. Are you sure you want to go live?") },
            confirmButton = {
                Button(
                    onClick = {
                        saveConfig(config.copy(dryRunMode = false))
                        showDryRunDisableDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Yes, Go Live")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDryRunDisableDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPinRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showPinRequiredDialog = false },
            title = { Text("Armed PIN Required", fontWeight = FontWeight.Bold) },
            text = { Text("The watchdog can only be enabled after you set an Armed PIN. Set one now, then come back to arm the device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showPinRequiredDialog = false
                        onNavigateToPinManagement()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Set PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinRequiredDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNotificationsRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationsRequiredDialog = false },
            title = { Text("Notifications Required", fontWeight = FontWeight.Bold) },
            text = { Text("The watchdog can only be enabled while notifications and full-screen alerts are allowed — the wipe countdown alarm depends on them. Grant them in the Required Permissions section above, then try again.") },
            confirmButton = {
                Button(
                    onClick = { showNotificationsRequiredDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationsRequiredDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBluetoothRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothRequiredDialog = false },
            title = { Text("Bluetooth Detection Required", fontWeight = FontWeight.Bold) },
            text = { Text("The watchdog can only be enabled while Bluetooth detection is allowed — the monitor must be able to read Bluetooth state to catch a live radio. Grant it in the Required Permissions section above, then try again.") },
            confirmButton = {
                Button(
                    onClick = { showBluetoothRequiredDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBluetoothRequiredDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Refresh statuses whenever this screen resumes (e.g. after returning
    // from the Dhizuku grant dialog or the system permission page).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dhizukuGranted = dhizukuManager.isDhizukuAvailable()
                overlayGranted = Settings.canDrawOverlays(context)
                batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                notificationsGranted = repository.areNotificationsAllowed()
                bluetoothConnectGranted = repository.isBluetoothConnectAllowed()
                refreshBlockStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),

        topBar = {
            BackNavigationTopAppBar(
                title = "Security Settings",
                onBack = onBack
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
            Spacer(modifier = Modifier.height(8.dp))

            RequiredPermissionsCard(
                context = context,
                dhizukuManager = dhizukuManager,
                dhizukuGranted = dhizukuGranted,
                onDhizukuGrantedChange = { dhizukuGranted = it },
                overlayGranted = overlayGranted,
                batteryExempt = batteryExempt,
                notificationsGranted = notificationsGranted,
                onNotificationsGrantedChange = { notificationsGranted = it },
                bluetoothConnectGranted = bluetoothConnectGranted,
                onBluetoothConnectGrantedChange = { bluetoothConnectGranted = it }
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingToggleRow(
                        title = "Use System Colors (Off by Default)",
                        hint = "Keep the app's #0055EA brand accent, or follow the device theme colors (Android 12+).",
                        checked = useSystemColors,
                        onCheckedChange = onUseSystemColorsChange
                    )
                }
            }

            MasterControlsCard(
                config = config,
                onConfigChange = ::saveConfig,
                onConfigFlush = ::flushConfigNow,
                context = context,
                blockEnforcer = blockEnforcer,
                dhizukuGranted = dhizukuGranted,
                blockStatus = blockStatus,
                blockIsError = blockIsError,
                onBlockStatusChange = { status, isError ->
                    blockStatus = status
                    blockIsError = isError
                },
                pinUsable = pinUsable,
                notificationsGranted = notificationsGranted,
                bluetoothConnectGranted = bluetoothConnectGranted,
                onEnableBlocked = { showPinRequiredDialog = true },
                onNotificationsBlocked = { showNotificationsRequiredDialog = true },
                onBluetoothBlocked = { showBluetoothRequiredDialog = true }
            )

            PostureTamperAlarmsCard(
                config = config,
                onConfigChange = ::saveConfig
            )

            PinSecurityCard(onNavigateToPinManagement = onNavigateToPinManagement)

            ThresholdsTimersCard(
                config = config,
                onConfigChange = ::saveConfig
            )

            HardeningWipeScopeCard(
                config = config,
                onConfigChange = ::saveConfig
            )

            DeveloperTestingCard(
                config = config,
                onConfigChange = ::saveConfig,
                context = context,
                repository = repository,
                onRequestDryRunDisable = { showDryRunDisableDialog = true }
            )

            PrimaryActionButton(
                text = "Done",
                onClick = onBack,
                height = 54.dp,
                cornerRadius = 16.dp
            )

            TextButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text(
                    text = "Reset to factory defaults",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
