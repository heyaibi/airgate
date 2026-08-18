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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.domain.model.AppConfig
import com.airgate.service.SafetyNetScheduler
import com.airgate.service.WatchdogService

/** Master Activation card: the protection on/off switch on the dashboard. */
@Composable
fun MasterActivationCard(
    config: AppConfig,
    context: Context,
    pinUsable: Boolean,
    notificationsGranted: Boolean,
    bluetoothConnectGranted: Boolean,
    exactAlarmGranted: Boolean,
    onEnableBlocked: () -> Unit,
    onConfigChange: (AppConfig) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionLabel("PROTECTION")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (config.isEnabled) "Protection Active" else "Protection Paused",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (config.isEnabled) "Your airgapped phone is monitored." else "Tap the switch to enable background security watchdogs.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.isEnabled,
                    onCheckedChange = { isChecked ->
                        // Refuse to arm until an Armed PIN is configured and readable,
                        // the app can post notifications, Bluetooth state can be read,
                        // AND exact-alarm access is granted — without these the owner
                        // could never disarm, could never see the wipe countdown, the
                        // monitor would be blind to a live Bluetooth radio, or the
                        // precise wipe deadline could never fire on time.
                        if (isChecked && (!pinUsable || !notificationsGranted || !bluetoothConnectGranted || !exactAlarmGranted)) {
                            onEnableBlocked()
                            return@Switch
                        }
                        onConfigChange(config.copy(isEnabled = isChecked))
                        // The service always runs; flipping this only changes
                        // enforcement. Re-start it to re-arm the safety-net
                        // alarm (schedule() no-ops while disabled), or cancel
                        // the alarm when disabling to stop wasteful wakeups.
                        if (isChecked) {
                            WatchdogService.startService(context)
                        } else {
                            SafetyNetScheduler.cancel(context)
                        }
                    },
                    modifier = Modifier.testTag("master_activation_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}
