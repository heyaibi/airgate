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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airgate.policy.ShieldLayerStatus

/** Shield Status Overview card: renders the live Dhizuku / wireless / USB guard state. */
@Composable
fun ShieldStatusCard(shieldStatuses: List<ShieldLayerStatus>) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            SectionLabel("SHIELD STATUS")
            Spacer(modifier = Modifier.height(16.dp))

            val dhizuku = shieldStatuses.getOrNull(0)
            val wireless = shieldStatuses.getOrNull(1)
            val usb = shieldStatuses.getOrNull(2)

            FriendlyStatusRow(
                title = dhizuku?.title ?: "Dhizuku Device Owner",
                subtitle = dhizuku?.subtitle ?: "Checking status…",
                status = dhizuku?.status ?: "Unknown",
                isOk = dhizuku?.isOk ?: false
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 10.dp))
            FriendlyStatusRow(
                title = wireless?.title ?: "Wireless Transceiver Blockade",
                subtitle = wireless?.subtitle ?: "Checking status…",
                status = wireless?.status ?: "Unknown",
                isOk = wireless?.isOk ?: false
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 10.dp))
            FriendlyStatusRow(
                title = usb?.title ?: "USB & ADB Guard",
                subtitle = usb?.subtitle ?: "Checking status…",
                status = usb?.status ?: "Unknown",
                isOk = usb?.isOk ?: false
            )
        }
    }
}
