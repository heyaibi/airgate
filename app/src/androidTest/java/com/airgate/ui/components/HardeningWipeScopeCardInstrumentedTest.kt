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

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ResponseTier
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered-behavior tests for the hardening & wipe card: the removed
 * "reset streak on unlock" toggle must not be offered anymore, and the remaining
 * controls (FRP wipe data toggle and the self-tamper response chips) must still
 * render and still fire config changes.
 */
@RunWith(AndroidJUnit4::class)
class HardeningWipeScopeCardInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun removedUnlockResetsToggle_isNotRendered() {
        composeRule.setContent {
            HardeningWipeScopeCard(config = AppConfig(), onConfigChange = {})
        }

        composeRule.onNodeWithText("User Unlock Resets Streak").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Automatically resets accumulated threat score to 0 when device is unlocked by keyguard."
        ).assertDoesNotExist()
    }

    @Test
    fun remainingControls_areStillRendered() {
        composeRule.setContent {
            HardeningWipeScopeCard(config = AppConfig(), onConfigChange = {})
        }

        composeRule.onNodeWithText("Include FRP Reset Data").assertExists()
        composeRule.onNodeWithText("Clears Factory Reset Protection data during device wipe.").assertExists()
        composeRule.onNodeWithText("Self-Tamper Response").assertExists()
        composeRule.onNodeWithText("Instant Wipe").assertExists()
        composeRule.onNodeWithText("Alarm + Streak").assertExists()
    }

    @Test
    fun frpToggle_stillFiresOnConfigChange() {
        var changed: AppConfig? = null

        composeRule.setContent {
            HardeningWipeScopeCard(config = AppConfig(), onConfigChange = { changed = it })
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        ).performClick()
        composeRule.waitForIdle()

        assertEquals("toggling FRP must flip includeFRPData", true, changed?.includeFRPData)
        // A config-change from the card carries the rest of the config untouched.
        assertEquals(AppConfig().copy(includeFRPData = true), changed)
    }

    @Test
    fun selfTamperChips_stillFireOnConfigChange() {
        var changed: AppConfig? = null

        composeRule.setContent {
            HardeningWipeScopeCard(config = AppConfig(), onConfigChange = { changed = it })
        }

        composeRule.onNodeWithTag("self_tamper_alarm_streak_chip").performClick()
        composeRule.waitForIdle()

        assertEquals("selecting the chip must switch the self-tamper tier", ResponseTier.ALARM_STREAK, changed?.selfTamperTier)

        composeRule.onNodeWithTag("self_tamper_instant_wipe_chip").performClick()
        composeRule.waitForIdle()

        assertEquals("re-selecting instant wipe must switch the tier back", ResponseTier.INSTANT_WIPE, changed?.selfTamperTier)
    }

    @Test
    fun card_emitsTheConfiguredSelectionOnChipClick() {
        var changed: AppConfig? = null

        composeRule.setContent {
            HardeningWipeScopeCard(config = AppConfig(selfTamperTier = ResponseTier.ALARM_STREAK), onConfigChange = { changed = it })
        }

        // Clicking the already-selected chip emits the full config (including the
        // configured tier), so callers always receive the effective value.
        composeRule.onNodeWithTag("self_tamper_alarm_streak_chip").performClick()
        composeRule.waitForIdle()

        assertEquals(ResponseTier.ALARM_STREAK, changed?.selfTamperTier)
    }
}
