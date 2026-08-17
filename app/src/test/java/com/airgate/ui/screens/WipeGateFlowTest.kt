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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.WipeGate
import com.airgate.data.repository.SecurityStateRepository
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
        composeRule.onNodeWithText("DEVICE WIPE EXECUTED").assertDoesNotExist()

        // A background component (the watchdog) flips the state while the UI is
        // already in the foreground — no lifecycle event fires.
        repository.setSecurityState(SecurityState.WIPING)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("DEVICE WIPE EXECUTED").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("DEVICE WIPE EXECUTED").assertIsDisplayed()
        composeRule.onNodeWithText("NORMAL CONTENT").assertDoesNotExist()
    }

    @Test
    fun normalContent_returnsWhenTheFlowLeavesWiping() {
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

        composeRule.onNodeWithText("DEVICE WIPE EXECUTED").assertIsDisplayed()

        // The owner's PIN-gated reset returns the state to compliant.
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("NORMAL CONTENT").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("NORMAL CONTENT").assertIsDisplayed()
        composeRule.onNodeWithText("DEVICE WIPE EXECUTED").assertDoesNotExist()
    }

    @Test
    fun wipeScreen_isShownImmediatelyWhenTheFlowStartsInWiping() {
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

        // A fresh collector replays the current state, so an app that restarts
        // mid-wipe lands directly on the emergency screen.
        composeRule.onNodeWithText("DEVICE WIPE EXECUTED").assertIsDisplayed()
        composeRule.onNodeWithText("NORMAL CONTENT").assertDoesNotExist()
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
