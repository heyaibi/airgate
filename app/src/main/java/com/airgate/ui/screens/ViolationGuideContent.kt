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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.ViolationType
import com.airgate.ui.components.SectionLabel
import com.airgate.ui.components.ViolationGuideInfo
import com.airgate.ui.components.ViolationGuideItem
import com.airgate.ui.components.ViolationGuideLegendCard

/** Static catalogue of every detection, its trigger, and its alarm/point behavior. */
private val guideItems: List<ViolationGuideInfo> = listOf(
    ViolationGuideInfo(
        violationType = ViolationType.WIFI_TRANSCEIVER_ENABLED,
        trigger = "The Wi-Fi transceiver is on \u2014 even with no network connected, with or without validated internet",
        alarmScreen = false,
        addsPoint = false,
        note = "Log entry only, for auditing. No alarm screen and no point."
    ),
    ViolationGuideInfo(
        violationType = ViolationType.VALIDATED_NETWORK,
        trigger = "any validated internet is present \u2014 Wi-Fi, cellular, ethernet or Bluetooth PAN",
        alarmScreen = true,
        addsPoint = true
    ),
    ViolationGuideInfo(
        violationType = ViolationType.AIRPLANE_MODE_OFF,
        trigger = "airplane mode is switched off",
        alarmScreen = true,
        addsPoint = true
    ),
    ViolationGuideInfo(
        violationType = ViolationType.BLUETOOTH_ACTIVITY,
        trigger = "Bluetooth is turned on",
        alarmScreen = true,
        addsPoint = true,
        note = "Passive proximity events (device found / bond changed) are logged only."
    ),
    ViolationGuideInfo(
        violationType = ViolationType.TETHERING_RNDIS,
        trigger = "USB tethering (RNDIS) is enabled",
        alarmScreen = true,
        addsPoint = true
    ),
    ViolationGuideInfo(
        violationType = ViolationType.USB_HOST_LINK,
        trigger = "a USB device enumerates as a host link (OTG)",
        alarmScreen = true,
        addsPoint = true,
        note = "Always enforced \u2014 a power-only charger or power bank is not a violation."
    ),
    ViolationGuideInfo(
        violationType = ViolationType.USB_FUNCTION_NOT_NONE,
        trigger = "a USB data function is active \u2014 MTP, PTP or ADB",
        alarmScreen = true,
        addsPoint = true,
        note = "Always enforced, independent of Block Debugging Features. Power-only charge sessions don't trigger it."
    ),
    ViolationGuideInfo(
        violationType = ViolationType.ADB_ENABLED_FLIP,
        trigger = "ADB (USB debugging) is switched on",
        alarmScreen = true,
        addsPoint = true,
        note = "Ignored while Block Debugging Features is off (recovery/install mode)."
    ),
    ViolationGuideInfo(
        violationType = ViolationType.OTG_ETHERNET_ATTACHED,
        trigger = "an ethernet adapter is attached",
        alarmScreen = true,
        addsPoint = true
    ),
    ViolationGuideInfo(
        violationType = ViolationType.DEVELOPER_OPTIONS_TOGGLE,
        trigger = "developer options are switched on",
        alarmScreen = true,
        addsPoint = true,
        note = "Ignored while Block Debugging Features is off."
    ),
    ViolationGuideInfo(
        violationType = ViolationType.SYSTEM_CLOCK_CHANGED,
        trigger = "the system clock moves beyond the clock-skew tolerance",
        alarmScreen = true,
        addsPoint = true
    ),
    ViolationGuideInfo(
        violationType = ViolationType.SIM_STATE_CHANGED,
        trigger = "a SIM card is present on a slot",
        alarmScreen = true,
        addsPoint = true
    ),
    ViolationGuideInfo(
        violationType = ViolationType.DO_RESTRICTION_MISSING,
        trigger = "a device-protection restriction is missing or self-defense fails",
        alarmScreen = true,
        addsPoint = true,
        note = "Off by default \u2014 enable 'Device Protection Bypassed' alarms. Self-defense failures route straight to the wipe path."
    )
)

private val scoringGroupIcon: (ScoringGroup) -> ImageVector = { group ->
    when (group) {
        ScoringGroup.WIRELESS -> Icons.Filled.Wifi
        ScoringGroup.USB -> Icons.Filled.Usb
        ScoringGroup.SYSTEM_TAMPER -> Icons.Filled.Shield
    }
}

@Composable
private fun scoringGroupColor(group: ScoringGroup): androidx.compose.ui.graphics.Color =
    when (group) {
        ScoringGroup.WIRELESS -> MaterialTheme.colorScheme.primary
        ScoringGroup.USB -> MaterialTheme.colorScheme.tertiary
        ScoringGroup.SYSTEM_TAMPER -> MaterialTheme.colorScheme.error
    }

/**
 * Plain-language reference of every violation: what triggers it, whether it shows the
 * full-screen alarm, and whether it adds a threat point. Grouped by scoring category,
 * with a search field that filters the catalogue live. Rendered as one tab inside [GuideScreen].
 */
@Composable
fun ViolationGuideContent() {
    var query by remember { mutableStateOf("") }
    var filterAlarmScreen by remember { mutableStateOf(false) }
    var filterLogOnly by remember { mutableStateOf(false) }
    var filterAddsPoint by remember { mutableStateOf(false) }
    var filterNoPoint by remember { mutableStateOf(false) }
    var guideExpanded by remember { mutableStateOf(true) }
    val normalized = query.trim()
    val isSearching = normalized.isNotEmpty()
    val anyBehaviorFilter = filterAlarmScreen || filterLogOnly || filterAddsPoint || filterNoPoint
    val isFiltering = isSearching || anyBehaviorFilter
    val filteredItems = guideItems.filter {
        (!anyBehaviorFilter || matchesBehaviorFilter(it, filterAlarmScreen, filterLogOnly, filterAddsPoint, filterNoPoint)) &&
            (!isSearching || matches(it, normalized))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "Search violations\u2026",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(imageVector = Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterAlarmScreen,
                onClick = { filterAlarmScreen = !filterAlarmScreen },
                label = { Text("Alarm screen", fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            )
            FilterChip(
                selected = filterLogOnly,
                onClick = { filterLogOnly = !filterLogOnly },
                label = { Text("Log only", fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            )
            FilterChip(
                selected = filterAddsPoint,
                onClick = { filterAddsPoint = !filterAddsPoint },
                label = { Text("Adds point", fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            )
            FilterChip(
                selected = filterNoPoint,
                onClick = { filterNoPoint = !filterNoPoint },
                label = { Text("No point", fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            )
        }

        if (isFiltering) {
            SectionLabel(
                text = if (filteredItems.isEmpty()) "NO MATCHES" else "${filteredItems.size} MATCH${if (filteredItems.size == 1) "" else "ES"}"
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { guideExpanded = !guideExpanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel(
                text = "HOW TO READ THIS GUIDE",
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Icon(
                imageVector = if (guideExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (guideExpanded) "Collapse guide" else "Expand guide",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = guideExpanded) {
            ViolationGuideLegendCard()
        }

        ScoringGroup.entries.forEach { group ->
            val items = filteredItems.filter { it.violationType.scoringGroup == group }
            if (items.isEmpty()) return@forEach

            val groupColor = scoringGroupColor(group)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = scoringGroupIcon(group),
                    contentDescription = null,
                    tint = groupColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = group.displayName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = groupColor
                )
                Text(
                    text = "${items.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(
                color = groupColor.copy(alpha = 0.15f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    ViolationGuideItem(info = item)
                }
            }
        }

        if (filteredItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = "No violations match",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Try a different search or clear the filters.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!isFiltering) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(13.dp)
                        .padding(top = 1.dp)
                )
                Text(
                    text = "A wipe starts when the threat score reaches the wipe threshold. " +
                        "In Dry-Run mode the factory reset is simulated; with Dry-Run off the real reset executes.",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun matches(info: ViolationGuideInfo, query: String): Boolean {
    val haystack = listOf(
        info.violationType.description,
        info.violationType.scoringGroup.displayName,
        info.trigger,
        info.note.orEmpty()
    )
    return haystack.any { it.contains(query, ignoreCase = true) }
}

private fun matchesBehaviorFilter(
    info: ViolationGuideInfo,
    filterAlarmScreen: Boolean,
    filterLogOnly: Boolean,
    filterAddsPoint: Boolean,
    filterNoPoint: Boolean
): Boolean =
    (filterAlarmScreen && info.alarmScreen) ||
        (filterLogOnly && !info.alarmScreen) ||
        (filterAddsPoint && info.addsPoint) ||
        (filterNoPoint && !info.addsPoint)
