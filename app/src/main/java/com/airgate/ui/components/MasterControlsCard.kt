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

package com.airgate.ui.components

import android.content.Context
import android.os.UserManager
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.domain.model.AppConfig
import com.airgate.policy.DevicePolicyEnforcer
import com.airgate.service.SafetyNetScheduler
import com.airgate.service.WatchdogService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Master Controls card: watchdog master switch, paranoid preset, and the verified
 * ADB debugging block. The block toggle enforces against the device, verifies the
 * result, and only commits the config when the enforcement is confirmed.
 */
@Composable
fun MasterControlsCard(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    onConfigFlush: () -> Unit,
    context: Context,
    blockEnforcer: DevicePolicyEnforcer,
    dhizukuGranted: Boolean,
    blockStatus: String,
    blockIsError: Boolean,
    onBlockStatusChange: (String, Boolean) -> Unit,
    pinUsable: Boolean,
    notificationsGranted: Boolean,
    bluetoothConnectGranted: Boolean,
    exactAlarmGranted: Boolean,
    onEnableBlocked: () -> Unit,
    onNotificationsBlocked: () -> Unit,
    onBluetoothBlocked: () -> Unit,
    onExactAlarmBlocked: () -> Unit
) {
    // The ADB block toggle enforces ~20 Dhizuku binder transactions and verifies
    // the result before committing; that work must never run on the main thread,
    // so the toggle launches it on a background dispatcher. The busy flag keeps a
    // second toggle from re-entering while an enforcement is in flight.
    val enforceScope = rememberCoroutineScope()
    var enforceBusy by remember { mutableStateOf(false) }

    SettingsCard(title = "MASTER PREFERENCES") {
        SettingToggleRow(
            title = "Enable Watchdog",
            hint = "Main switch to enable or pause all background monitoring enforcement.",
            checked = config.isEnabled,
            testTag = "enable_watchdog_switch",
            onCheckedChange = { enabled ->
                // Refuse to arm until an Armed PIN is configured and readable.
                if (enabled && !pinUsable) {
                    onEnableBlocked()
                    return@SettingToggleRow
                }
                // Refuse to arm until the app can post notifications: an armed
                // device whose alarm path could be entirely silent is a broken
                // enforcement state, so the owner is sent to grant the permission.
                if (enabled && !notificationsGranted) {
                    onNotificationsBlocked()
                    return@SettingToggleRow
                }
                // Refuse to arm while Bluetooth state cannot be read: arming
                // without it would be silently blind to a core air-gap signal.
                if (enabled && !bluetoothConnectGranted) {
                    onBluetoothBlocked()
                    return@SettingToggleRow
                }
                // Refuse to arm without exact-alarm access: a precise wipe countdown
                // cannot be guaranteed otherwise, so the owner is sent to grant it.
                if (enabled && !exactAlarmGranted) {
                    onExactAlarmBlocked()
                    return@SettingToggleRow
                }
                onConfigChange(config.copy(isEnabled = enabled))
                // The service always runs; this toggle only changes whether
                // detections are enforced. Re-arm the safety net on enable,
                // cancel it on disable — identical to the dashboard switch.
                onConfigFlush()
                if (enabled) {
                    WatchdogService.startService(context)
                } else {
                    SafetyNetScheduler.cancel(context)
                }
            }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        SettingToggleRow(
            title = "Paranoid Mode Preset",
            hint = "Enforces strict limits (wipeThreshold=1, safetyNet=1m) for high-risk posture.",
            checked = config.aggressiveMode,
            testTag = "paranoid_mode_switch",
            onCheckedChange = { on ->
                // The preset enables the watchdog, so it also requires a usable Armed PIN.
                if (on && !pinUsable) {
                    onEnableBlocked()
                    return@SettingToggleRow
                }
                // The preset arms the watchdog, so it inherits the same notification
                // requirement as the master switch.
                if (on && !notificationsGranted) {
                    onNotificationsBlocked()
                    return@SettingToggleRow
                }
                // The preset also inherits the Bluetooth-readability requirement.
                if (on && !bluetoothConnectGranted) {
                    onBluetoothBlocked()
                    return@SettingToggleRow
                }
                // The preset arms the watchdog, so it also inherits the
                // exact-alarm requirement of the precise wipe countdown.
                if (on && !exactAlarmGranted) {
                    onExactAlarmBlocked()
                    return@SettingToggleRow
                }
                // The preset only enforces strict limits; the owner's own
                // choices for Dry Run and Device Protection Bypassed alarms
                // are never touched.
                val newConfig = if (on) {
                    AppConfig.aggressivePreset().copy(
                        dryRunMode = config.dryRunMode,
                        deviceProtectionAlarmEnabled = config.deviceProtectionAlarmEnabled
                    )
                } else {
                    AppConfig(isEnabled = config.isEnabled)
                }
                onConfigChange(newConfig)
                // The preset enables the watchdog; keep the safety-net
                // alarm in sync with the resulting config.
                onConfigFlush()
                if (newConfig.isEnabled) {
                    WatchdogService.startService(context)
                } else {
                    SafetyNetScheduler.cancel(context)
                }
            }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        SettingToggleRow(
            title = "Block Debugging Features",
            hint = "Enforces DISALLOW_DEBUGGING_FEATURES (hides developer options & USB debugging). Turning this OFF clears the restriction immediately so the device can be recovered via ADB without a factory reset.",
            checked = config.blockDebuggingFeatures,
            onCheckedChange = { enabled ->
                if (enforceBusy) return@SettingToggleRow
                enforceBusy = true
                // Enforce, verify against the device, then commit or revert. Runs
                // off the main thread: each policy write is a Dhizuku binder
                // transaction, and a slow or wedged server must never stall the UI.
                enforceScope.launch {
                    val target = config.copy(blockDebuggingFeatures = enabled)
                    val ok = withContext(Dispatchers.Default) {
                        runCatching {
                            val results = blockEnforcer.enforceAllPolicies(target)
                            val verified = blockEnforcer.isDebuggingBlockEffective(target)
                            val writesOk = results[UserManager.DISALLOW_DEBUGGING_FEATURES] == true &&
                                results["adb_enabled"] == true &&
                                results["development_settings_enabled"] == true
                            verified && writesOk
                        }.getOrDefault(false)
                    }
                    if (ok) {
                        onConfigChange(target)
                        onBlockStatusChange(
                            if (enabled) "ADB blocked and verified." else "ADB enabled — recovery available.",
                            false
                        )
                    } else {
                        // Leave config untouched so the switch snaps back; surface
                        // the failure instead of pretending the device is blocked.
                        onBlockStatusChange(
                            if (!dhizukuGranted) "Requires Dhizuku grant — cannot block ADB."
                            else "Enforcement failed — ADB state not verified.",
                            true
                        )
                    }
                    enforceBusy = false
                }
            }
        )
        if (blockStatus.isNotEmpty()) {
            Text(
                text = blockStatus,
                fontSize = 12.sp,
                color = if (blockIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}
