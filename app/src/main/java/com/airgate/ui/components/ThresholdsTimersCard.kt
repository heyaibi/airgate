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

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.airgate.domain.model.AppConfig

/**
 * Thresholds & Timers card: every breach-scoring limit and background cadence slider.
 */
@Composable
fun ThresholdsTimersCard(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit
) {
    SettingsCard(title = "THRESHOLDS & TIMERS", verticalSpacing = 20.dp) {
        // Threat Limit Slider (1 to 10)
        SliderSettingRow(
            title = "Threat Limit (wipeThreshold)",
            hint = "Total accumulated breach weight that triggers device wipe execution.",
            valueText = "${config.wipeThreshold} points",
            value = config.wipeThreshold.toFloat(),
            valueRange = 1f..10f,
            steps = 8,
            onValueChange = { onConfigChange(config.copy(wipeThreshold = it.toInt())) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

        // Alarms Per Breach Slider (2 to 10)
        SliderSettingRow(
            title = "Alarms Per Breach",
            hint = "Number of repeated warning alerts fired when a security event occurs.",
            valueText = "${config.notificationsPerBreach} alerts",
            value = config.notificationsPerBreach.toFloat(),
            valueRange = 2f..10f,
            steps = 7,
            onValueChange = { onConfigChange(config.copy(notificationsPerBreach = it.toInt())) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

        // Continuous Slider for Notification Tail Gap (12h to 7d)
        SliderSettingRow(
            title = "Notification Tail Gap",
            hint = "Re-alarm interval gap to alert sleeping owners during ongoing breaches.",
            valueText = "${config.notificationTailMinutes / 60} hours",
            value = config.notificationTailMinutes.toFloat(),
            valueRange = 720f..10080f,
            steps = (10080 - 720) / 720 - 1,
            onValueChange = { onConfigChange(config.copy(notificationTailMinutes = it.toInt())) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

        // Continuous Slider for Pre-Wipe Grace Window (0s to 3600s)
        SliderSettingRow(
            title = "Pre-Wipe Grace Window",
            hint = "Countdown timer duration allowing user cancellation before wipe triggers.",
            valueText = if (config.graceWindowSeconds == 0) "Immediate (0s)" else "${config.graceWindowSeconds} seconds",
            value = config.graceWindowSeconds.toFloat(),
            valueRange = 0f..3600f,
            steps = (3600 / 30) - 1,
            onValueChange = { onConfigChange(config.copy(graceWindowSeconds = it.toInt())) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

        // Continuous Slider for Safety Net Check Cadence (1m to 60m)
        SliderSettingRow(
            title = "Safety Net Check Cadence",
            hint = "Periodic background job frequency that re-audits radio states & restrictions.",
            valueText = "${config.safetyNetIntervalMinutes} min",
            value = config.safetyNetIntervalMinutes.toFloat(),
            valueRange = 1f..60f,
            steps = 58,
            onValueChange = { onConfigChange(config.copy(safetyNetIntervalMinutes = it.toInt())) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

        // Stepper for Clock Skew Floor (1m to 60m)
        SliderSettingRow(
            title = "Clock Skew Tolerance",
            hint = "Allowed system clock deviation before clock tampering breach is raised.",
            valueText = "${config.clockSkewToleranceMinutes} min",
            value = config.clockSkewToleranceMinutes.toFloat(),
            valueRange = 1f..60f,
            steps = 58,
            onValueChange = { onConfigChange(config.copy(clockSkewToleranceMinutes = it.toInt())) }
        )
    }
}
