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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.airgate.dhizuku.DhizukuManager

/**
 * Required Access & Permissions card. These grants are essential for correct
 * operation, so missing grants are highlighted with an error container + warning
 * text to demand the owner's attention instead of silently degrading (alarms hidden
 * behind lockscreen, or policies unenforced because device-owner authority is missing).
 *
 * BatteryLife: requesting the battery-optimization exemption is a deliberate,
 * user-initiated action (explicit button tap) for a watchdog whose core function —
 * continuous background monitoring — is directly affected by Doze/App Standby.
 */
@SuppressLint("BatteryLife")
@Composable
fun RequiredPermissionsCard(
    context: Context,
    dhizukuManager: DhizukuManager,
    dhizukuGranted: Boolean,
    onDhizukuGrantedChange: (Boolean) -> Unit,
    overlayGranted: Boolean,
    batteryExempt: Boolean,
    notificationsGranted: Boolean,
    onNotificationsGrantedChange: (Boolean) -> Unit,
    bluetoothConnectGranted: Boolean,
    onBluetoothConnectGrantedChange: (Boolean) -> Unit,
    exactAlarmGranted: Boolean,
    onExactAlarmGrantedChange: (Boolean) -> Unit
) {
    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onNotificationsGrantedChange(granted) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onBluetoothConnectGrantedChange(granted) }

    val requiredGranted = dhizukuGranted && overlayGranted && notificationsGranted &&
        bluetoothConnectGranted && exactAlarmGranted
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (requiredGranted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(
                text = "REQUIRED PERMISSIONS",
                color = if (requiredGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
            )

            if (!requiredGranted) {
                Text(
                    text = "The app cannot function without these. Grant each below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 16.sp
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Dhizuku Access
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dhizuku Access", modifier = Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (dhizukuGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (dhizukuGranted) "Granted" else "Required",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dhizukuGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = "Enables enforcement and wipe via device owner.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Button(
                onClick = {
                    // The grant request is a bounded Dhizuku transaction: a wedged
                    // Dhizuku server stalls this call for at most the manager's
                    // timeout (default 3s, under the ANR threshold) and reports
                    // "not granted" instead of blocking the UI indefinitely.
                    dhizukuManager.requestPermission(context) { granted ->
                        onDhizukuGrantedChange(granted)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dhizukuGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                    contentColor = if (dhizukuGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Authorize Dhizuku", fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Display over other apps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Display over other apps", modifier = Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (overlayGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (overlayGranted) "Granted" else "Required",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (overlayGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = "Alarm must show over the lock screen.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri()
                            )
                        )
                    } catch (e: Exception) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            )
                        } catch (e2: Exception) {
                            // Best effort: nothing else to try
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (overlayGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                    contentColor = if (overlayGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Allow Overlay", fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Notifications
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notifications", modifier = Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (notificationsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (notificationsGranted) "Granted" else "Required",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (notificationsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = "The alarm needs notifications and full-screen alerts to wake you.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onNotificationsGrantedChange(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (notificationsGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                    contentColor = if (notificationsGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Allow Notifications", fontWeight = FontWeight.SemiBold)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // On Android 14+ the full-screen-intent permission is independently
                // revocable; arming requires it, so give the owner the in-app path to
                // the dedicated system setting.
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    "package:${context.packageName}".toUri()
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (notificationsGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                        contentColor = if (notificationsGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Allow Full-Screen Alerts", fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Bluetooth state detection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bluetooth detection", modifier = Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (bluetoothConnectGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (bluetoothConnectGranted) "Granted" else "Required",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (bluetoothConnectGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = "The monitor must be able to read Bluetooth state to detect a live radio.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        onBluetoothConnectGrantedChange(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (bluetoothConnectGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                    contentColor = if (bluetoothConnectGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Allow Bluetooth Detection", fontWeight = FontWeight.SemiBold)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Exact alarms (SCHEDULE_EXACT_ALARM / "Alarms & reminders" access)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Exact alarms", modifier = Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (exactAlarmGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = if (exactAlarmGranted) "Granted" else "Required",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (exactAlarmGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = "The precise wipe countdown is armed as an exact alarm; without this access its deadline could never be guaranteed. Grant “Alarms & reminders” in system settings.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (exactAlarmGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                        contentColor = if (exactAlarmGranted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Allow Exact Alarms", fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Battery optimization exemption (recommended, not strictly required)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Battery optimization", modifier = Modifier.weight(1f).padding(end = 16.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (batteryExempt) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (batteryExempt) "Exempt" else "Not exempt",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (batteryExempt) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "Keeps the watchdog running. Recommended.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                        )
                    } catch (e: Exception) {
                        // Fall back to general battery settings if unavailable
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text("Allow Exemption", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
