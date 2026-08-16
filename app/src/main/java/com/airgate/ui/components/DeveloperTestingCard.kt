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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.testing.DryRunHarness

/**
 * Developer & Offline Testing card: the dry-run switch (gated behind a confirmation
 * dialog when turning it OFF) and the one-tap simulation harness triggers.
 */
@Composable
fun DeveloperTestingCard(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    context: Context,
    repository: SecurityStateRepository,
    onRequestDryRunDisable: () -> Unit
) {
    SettingsCard(title = "DEVELOPER & OFFLINE TESTING", containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        SettingToggleRow(
            title = "Dry-Run Simulation Mode",
            hint = "Changes nothing about normal operation: policy enforcement (airplane mode, ADB block, restrictions) runs for real in every mode. When ON, only the destructive factory reset / user removal is simulated — the wipe screen appears instead of the real wipe. When OFF, the real wipe executes.",
            checked = config.dryRunMode,
            onCheckedChange = { enabled ->
                if (enabled) {
                    onConfigChange(config.copy(dryRunMode = true))
                } else {
                    // Turning dry-run OFF means real wipes when conditions are met.
                    // Confirm before committing; canceling leaves dry-run ON (the
                    // switch snaps back because config is untouched).
                    onRequestDryRunDisable()
                }
            }
        )

        if (config.dryRunMode) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Text(
                text = "Simulation Harness Triggers",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            // The default OutlinedButton border uses `outline`, which is nearly
            // identical to the surfaceVariant card in the light theme (and can be
            // faint with dynamic color). Force a primary-toned border so the
            // harness buttons are clearly outlined in both light and dark mode.
            val harnessBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            DryRunHarness(context, repository).simulateWifiBreach()
                            android.widget.Toast.makeText(context, "Simulated Wi-Fi breach (+1 point)", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        border = harnessBorder
                    ) {
                        Text("Wi-Fi (+1)", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            DryRunHarness(context, repository).simulateBluetoothBreach()
                            android.widget.Toast.makeText(context, "Simulated Bluetooth breach (+1 point)", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        border = harnessBorder
                    ) {
                        Text("Bluetooth (+1)", fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            DryRunHarness(context, repository).simulateUsbHostAttach()
                            android.widget.Toast.makeText(context, "Simulated USB attach (+1 point)", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        border = harnessBorder
                    ) {
                        Text("USB (+1)", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
