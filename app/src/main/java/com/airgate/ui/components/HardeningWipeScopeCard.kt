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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ResponseTier

/**
 * Hardening & wipe card: FRP wipe data and the self-tamper response tier.
 */
@Composable
fun HardeningWipeScopeCard(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit
) {
    SettingsCard(title = "HARDENING & WIPE") {
        SettingToggleRow(
            title = "Include FRP Reset Data",
            hint = "Clears Factory Reset Protection data during device wipe.",
            checked = config.includeFRPData,
            onCheckedChange = { onConfigChange(config.copy(includeFRPData = it)) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Self-Tamper Response", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Action taken if Device Owner status is lost or app signature is tampered.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.selfTamperTier == ResponseTier.INSTANT_WIPE,
                    onClick = { onConfigChange(config.copy(selfTamperTier = ResponseTier.INSTANT_WIPE)) },
                    label = { Text("Instant Wipe") },
                    modifier = Modifier.testTag("self_tamper_instant_wipe_chip")
                )
                FilterChip(
                    selected = config.selfTamperTier == ResponseTier.ALARM_STREAK,
                    onClick = { onConfigChange(config.copy(selfTamperTier = ResponseTier.ALARM_STREAK)) },
                    label = { Text("Alarm + Streak") },
                    modifier = Modifier.testTag("self_tamper_alarm_streak_chip")
                )
            }
        }
    }
}
