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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.ui.components.PinVerifyDialog
import com.airgate.ui.components.ThreatScoreMeter
import com.airgate.ui.components.WipeActionsPanel
import com.airgate.ui.components.WipeBeacon
import com.airgate.ui.components.WipeStatusStrip
import com.airgate.ui.theme.WipePalette

@Composable
fun SimulatedWipeScreen(
    repository: SecurityStateRepository,
    onResetStreakRequested: () -> Unit
) {
    var showPinDialog by remember { mutableStateOf(false) }
    val config = remember { repository.getConfig() }
    val streak = remember { repository.getStreak() }
    val threshold = config.wipeThreshold.coerceAtLeast(1)
    val progress = (streak.toFloat() / threshold.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(WipePalette.backdropTop, WipePalette.backdropBottom)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WipeStatusStrip()

            Spacer(modifier = Modifier.height(32.dp))

            WipeBeacon()

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "DEVICE WIPE EXECUTED",
                color = WipePalette.headline,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Breach threat threshold reached. Production airgap protocol would now trigger an immediate, non-recoverable zero-fill factory reset.",
                color = WipePalette.bodyText,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            ThreatScoreMeter(streak = streak, threshold = threshold, progress = progress)

            Spacer(modifier = Modifier.height(20.dp))

            WipeActionsPanel()

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = WipePalette.beacon.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, WipePalette.beacon.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(WipePalette.beacon)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SIMULATED — NO REAL DATA DESTROYED",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 0.4.sp,
                        color = WipePalette.beacon
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { showPinDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WipePalette.actionSurface,
                    contentColor = WipePalette.actionText
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Reset Threat Score",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Recovering from a simulated wipe requires your Armed PIN",
                color = WipePalette.detailText.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            if (showPinDialog) {
                PinVerifyDialog(
                    repository = repository,
                    title = "Reset Threat Score",
                    description = "Recovering from a simulated wipe requires your Armed PIN.",
                    confirmLabel = "Reset",
                    onDismiss = { showPinDialog = false },
                    onVerified = {
                        showPinDialog = false
                        onResetStreakRequested()
                    }
                )
            }
        }
    }
}
