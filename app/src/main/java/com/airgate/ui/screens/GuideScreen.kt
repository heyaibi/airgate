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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.ui.components.BackNavigationTopAppBar

private enum class GuideTab(val label: String) {
    PROTECTION_VECTORS("Protection Vectors"),
    VIOLATIONS("Violations")
}

/**
 * Multi-tab Guide screen. Owns only tab selection; each tab delegates to its own
 * single-purpose content composable ([ProtectionVectorsContent], [ViolationGuideContent])
 * so the screen itself stays a thin navigation container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    repository: SecurityStateRepository,
    onBack: () -> Unit,
    initialTab: Int = 0
) {
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BackNavigationTopAppBar(title = "Guide", onBack = onBack)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                GuideTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = tab.label,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }
            }
            when (GuideTab.entries[selectedTab]) {
                GuideTab.PROTECTION_VECTORS -> ProtectionVectorsContent(repository)
                GuideTab.VIOLATIONS -> ViolationGuideContent()
            }
        }
    }
}
