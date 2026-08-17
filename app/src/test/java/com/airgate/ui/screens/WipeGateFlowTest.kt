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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.WipeGate
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Rendered-behavior tests for the wipe-screen gate: the emergency screen is
 * driven by the repository's process-wide security-state flow, so a breach that
 * flips the state to WIPING in the background surfaces the wipe screen
 * immediately — the exact scenario a stale in-memory copy used to miss. The
 * state is collected with [collectAsStateWithLifecycle] exactly as MainActivity
 * does, so the test proves the end-to-end wiring, not just the gate in isolation.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WipeGateFlowTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wipeScreen_appearsWhenTheFlowEmitsWiping() {
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
    fun normalContent_returnsWhenTheFlowLeavesWiping() {
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
    fun wipeScreen_isShownImmediatelyWhenTheFlowStartsInWiping() {
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
        composeRule.onNodeWithText("NORMAL CONTENT").assertDoesNotExist()
    }

    @Test
    fun wipeScreen_showsDryRunTextInDryRunMode() {
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
    fun wipeScreen_showsLiveTextInLiveMode() {
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs: SharedPreferences = context.getSharedPreferences(
            "wipe_gate_flow_test_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }
}
