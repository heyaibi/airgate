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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.airgate.Screen

/**
 * Quick Actions rendered as a persistent bottom navigation bar: a hairline
 * separator, navigation-bar inset, and equal-width items. Items are
 * [QuickActionNavItem] so the ripple covers the whole cell, MUI-style, and
 * selection is a pure color swap.
 */
@Composable
fun QuickActionsBottomBar(
    currentScreen: Screen,
    onSelect: (Screen) -> Unit,
    enabled: Boolean = true
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(outlineColor)
        )
        NavigationBar(
            containerColor = surfaceColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxWidth()
        ) {
            quickActionTabs.forEach { tab ->
                QuickActionNavItem(
                    selected = currentScreen == tab.screen,
                    enabled = enabled,
                    onClick = { onSelect(tab.screen) },
                    icon = tab.icon,
                    label = tab.label,
                    testTag = tab.testTag
                )
            }
        }
    }
}

private data class QuickActionTab(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

private val quickActionTabs = listOf(
    QuickActionTab(Screen.DASHBOARD, "Home", Icons.Filled.Home, "tabDashboard"),
    QuickActionTab(Screen.BREACH_DETAILS, "Activity", Icons.Filled.Shield, "tabActivity"),
    QuickActionTab(Screen.SETTINGS, "Settings", Icons.Filled.Settings, "tabSettings"),
    QuickActionTab(Screen.VIOLATION_GUIDE, "Guide", Icons.Filled.Warning, "tabGuide")
)
