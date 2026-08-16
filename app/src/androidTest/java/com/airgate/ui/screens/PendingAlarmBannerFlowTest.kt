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

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered-behavior tests for the persistent in-app alarm surface on the dashboard:
 * the banner is shown while a pending alarm exists, is not dismissable by
 * navigation, and can only be cleared with the Armed PIN — acknowledging a plain
 * alarm clears the marker, while cancelling a countdown alarm also aborts the
 * scheduled wipe. On-device persistence of the marker is verified too.
 */
@RunWith(AndroidJUnit4::class)
class PendingAlarmBannerFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun alarmBanner_isShownAndAcknowledgedWithPin() {
        val repository = freshRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        repository.setPendingAlarm(
            PendingAlarm(
                category = "Wireless",
                description = "Network connection detected",
                timestamp = System.currentTimeMillis(),
                isCountdown = false
            )
        )

        composeRule.setContent {
            DashboardScreen(repository = repository, onNavigateToBreaches = {}, onClearStreakRequested = {})
        }

        composeRule.onNodeWithText("SECURITY ALARM — ACTION REQUIRED").assertIsDisplayed()
        composeRule.onNodeWithText("Wireless — Network connection detected", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Acknowledge with Armed PIN").performClick()
        composeRule.onNodeWithText("Acknowledge Security Alarm").assertIsDisplayed()

        composeRule.onNode(hasSetTextAction()).performTextReplacement(PIN)
        composeRule.onNodeWithText("Acknowledge").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { repository.getPendingAlarm() == null }
        composeRule.onNodeWithText("SECURITY ALARM — ACTION REQUIRED").assertDoesNotExist()
        // Acknowledging a plain alarm must not disturb the security state.
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun countdownBanner_cancelWithPin_clearsMarkerAndState() {
        val repository = freshRepository()
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60))
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        repository.setPendingAlarm(
            PendingAlarm(
                category = "WIPE COUNTDOWN",
                description = "A wipe is scheduled.",
                timestamp = System.currentTimeMillis(),
                isCountdown = true
            )
        )

        composeRule.setContent {
            DashboardScreen(repository = repository, onNavigateToBreaches = {}, onClearStreakRequested = {})
        }

        composeRule.onNodeWithText("WIPE COUNTDOWN ACTIVE").assertIsDisplayed()

        composeRule.onNodeWithText("Disarm & Cancel Wipe").performClick()
        composeRule.onNodeWithText("Cancel Pending Wipe").assertIsDisplayed()

        composeRule.onNode(hasSetTextAction()).performTextReplacement(PIN)
        composeRule.onNodeWithText("Cancel Wipe").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            repository.getPendingAlarm() == null &&
                repository.getSecurityState() == SecurityState.ARMED_COMPLIANT
        }
        composeRule.onNodeWithText("WIPE COUNTDOWN ACTIVE").assertDoesNotExist()
    }

    @Test
    fun noBanner_isShownWhenNoPendingAlarm() {
        val repository = freshRepository()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)

        composeRule.setContent {
            DashboardScreen(repository = repository, onNavigateToBreaches = {}, onClearStreakRequested = {})
        }

        composeRule.onNodeWithText("SECURITY ALARM — ACTION REQUIRED").assertDoesNotExist()
        composeRule.onNodeWithText("WIPE COUNTDOWN ACTIVE").assertDoesNotExist()
    }

    @Test
    fun pendingAlarm_roundTripsThroughRealPrefsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(
            "pending_alarm_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs)

        assertNull(repository.getPendingAlarm())

        repository.setPendingAlarm(
            PendingAlarm("USB", "USB device connected", 42L, isCountdown = false)
        )
        val reloaded = SecurityStateRepository(prefs)
        assertEquals("USB", reloaded.getPendingAlarm()?.category)
        assertEquals("USB device connected", reloaded.getPendingAlarm()?.description)
        assertEquals(42L, reloaded.getPendingAlarm()?.timestamp)
        assertTrue(reloaded.getPendingAlarm()?.isCountdown == false)

        reloaded.clearPendingAlarm()
        assertNull(reloaded.getPendingAlarm())
    }

    private fun freshPrefs(): SharedPreferences {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return prefs
    }

    private fun freshRepository(): SecurityStateRepository {
        val repository = SecurityStateRepository(freshPrefs())
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(PIN, salt)
        repository.savePin(hash, salt)
        return repository
    }

    private companion object {
        /** Isolated prefs file so the test never touches the app's real state. */
        const val PREFS_NAME = "pending_alarm_banner_test_prefs"

        const val PIN = "246810"
    }
}
