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

package com.airgate.data.repository

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.WipeGate
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose verification of the wipe-screen gate: the emergency screen
 * is driven by the repository's process-wide security-state flow, so a breach
 * that flips the state to WIPING in the background surfaces the wipe screen
 * immediately — no lifecycle event or activity recreation required. The state is
 * collected with [collectAsStateWithLifecycle] exactly as MainActivity does, so
 * this test proves the end-to-end wiring on the real platform with the real
 * keystore and SharedPreferences.
 */
@RunWith(AndroidJUnit4::class)
class WipeGateInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wipeScreen_appearsWhenFlowEmitsWiping_onDevice() {
        val repository = freshRepository()

        composeRule.setContent {
            val securityState by repository.securityStateFlow.collectAsStateWithLifecycle()
            WipeGate(
                securityState = securityState,
                repository = repository,
                onResetStreakRequested = {}
            ) {
                Text("NORMAL CONTENT")
            }
        }

        composeRule.onNodeWithText("NORMAL CONTENT").assertIsDisplayed()
        composeRule.onNodeWithText("FACTORY RESET PENDING").assertDoesNotExist()

        repository.saveConfig(AppConfig(dryRunMode = false))
        repository.setSecurityState(SecurityState.WIPING)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("FACTORY RESET PENDING").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("FACTORY RESET PENDING").assertIsDisplayed()
        composeRule.onNodeWithText("NORMAL CONTENT").assertDoesNotExist()
    }

    @Test
    fun normalContent_returnsWhenFlowLeavesWiping_onDevice() {
        val repository = freshRepository()
        repository.saveConfig(AppConfig(dryRunMode = false))
        repository.setSecurityState(SecurityState.WIPING)

        composeRule.setContent {
            val securityState by repository.securityStateFlow.collectAsStateWithLifecycle()
            WipeGate(
                securityState = securityState,
                repository = repository,
                onResetStreakRequested = {}
            ) {
                Text("NORMAL CONTENT")
            }
        }

        composeRule.onNodeWithText("FACTORY RESET PENDING").assertIsDisplayed()

        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("NORMAL CONTENT").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("NORMAL CONTENT").assertIsDisplayed()
        composeRule.onNodeWithText("FACTORY RESET PENDING").assertDoesNotExist()
    }

    @Test
    fun wipeScreen_showsDryRunTextInDryRunMode_onDevice() {
        val repository = freshRepository()
        repository.setSecurityState(SecurityState.WIPING)

        composeRule.setContent {
            val securityState by repository.securityStateFlow.collectAsStateWithLifecycle()
            WipeGate(
                securityState = securityState,
                repository = repository,
                onResetStreakRequested = {}
            ) {
                Text("NORMAL CONTENT")
            }
        }

        composeRule.onNodeWithText("SIMULATED WIPE").assertIsDisplayed()
        composeRule.onNodeWithText("SIMULATION — NO DATA WILL BE DESTROYED").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("FACTORY RESET PENDING").assertDoesNotExist()
        composeRule.onNodeWithText("LIVE WIPE — THIS DEVICE WILL BE FACTORY-RESET").assertDoesNotExist()
    }

    @Test
    fun wipeScreen_showsLiveTextInLiveMode_onDevice() {
        val repository = freshRepository()
        repository.saveConfig(AppConfig(dryRunMode = false))
        repository.setSecurityState(SecurityState.WIPING)

        composeRule.setContent {
            val securityState by repository.securityStateFlow.collectAsStateWithLifecycle()
            WipeGate(
                securityState = securityState,
                repository = repository,
                onResetStreakRequested = {}
            ) {
                Text("NORMAL CONTENT")
            }
        }

        composeRule.onNodeWithText("FACTORY RESET PENDING").assertIsDisplayed()
        composeRule.onNodeWithText("LIVE WIPE — THIS DEVICE WILL BE FACTORY-RESET").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SIMULATED WIPE").assertDoesNotExist()
        composeRule.onNodeWithText("SIMULATION — NO DATA WILL BE DESTROYED").assertDoesNotExist()
    }

    private fun freshRepository(): SecurityStateRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(
            "wipe_gate_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }
}
