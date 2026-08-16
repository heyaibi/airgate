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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.airgate.domain.model.AppConfig

/**
 * Posture & Tamper Alarms card: which tamper signals wake the owner with an alarm.
 */
@Composable
fun PostureTamperAlarmsCard(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit
) {
    SettingsCard(title = "POSTURE & TAMPER ALARMS") {
        SettingToggleRow(
            title = "Device Protection Bypassed",
            hint = "Alarm when device-owner protection is lost or removed: missing user restrictions, Dhizuku status lost, or signature tamper. Off by default to avoid wake-up false alarms.",
            checked = config.deviceProtectionAlarmEnabled,
            onCheckedChange = { onConfigChange(config.copy(deviceProtectionAlarmEnabled = it)) }
        )
    }
}
