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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.ui.components.BackNavigationTopAppBar
import com.airgate.ui.components.BreachDetailsEmptyState
import com.airgate.ui.components.SectionLabel
import com.airgate.ui.components.ThreatScoreHeroCard
import com.airgate.ui.components.ViolationGroupCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreachDetailsScreen(
    repository: SecurityStateRepository,
    onBack: () -> Unit,
    onNavigateToViolationGuide: () -> Unit
) {
    val vtCounts = repository.getAllVtCounts()
    val streak = repository.getStreak()
    val config = repository.getConfig()

    val isViolated = streak > 0
    val statusColor = when {
        streak >= config.wipeThreshold -> MaterialTheme.colorScheme.error
        streak > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val progressFraction = (streak.toFloat() / config.wipeThreshold.toFloat()).coerceIn(0f, 1f)

    val groupedVts = vtCounts.entries.groupBy { it.key.scoringGroup }
    val currentGrouped = groupedVts.filter { (group, _) -> repository.isScoringGroupClaimedToday(group) }
    val previousGrouped = groupedVts.filter { (group, _) -> !repository.isScoringGroupClaimedToday(group) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BackNavigationTopAppBar(
                title = "Security Activity",
                onBack = onBack,
                titleFontSize = 18.sp
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            ThreatScoreHeroCard(
                streak = streak,
                wipeThreshold = config.wipeThreshold,
                isViolated = isViolated,
                statusColor = statusColor,
                progressFraction = progressFraction
            )

            if (currentGrouped.isNotEmpty()) {
                SectionLabel("ACTIVE CATEGORIES")

                currentGrouped.forEach { (group, entries) ->
                    ViolationGroupCard(
                        group = group,
                        entries = entries,
                        repository = repository,
                        active = true,
                        statusColor = statusColor
                    )
                }
            }

            if (previousGrouped.isNotEmpty()) {
                SectionLabel("INACTIVE CATEGORIES")

                previousGrouped.forEach { (group, entries) ->
                    ViolationGroupCard(
                        group = group,
                        entries = entries,
                        repository = repository,
                        active = false,
                        statusColor = statusColor
                    )
                }
            }

            if (currentGrouped.isEmpty() && previousGrouped.isEmpty()) {
                BreachDetailsEmptyState()
            }

            TextButton(
                onClick = onNavigateToViolationGuide,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "What triggers a violation?",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
