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

package com.airgate.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.MainActivity
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the app to every screen in a representative state and parks on each
 * one so `make screens` / `make screens-dark` can capture it.
 *
 * The app sets FLAG_SECURE, so `adb screencap` returns a black frame; the
 * emulator's own screenshot (`adb emu screenrecord screenshot`) captures the
 * real display instead. This test is run in the background by the Makefile: on
 * each view it writes a marker file (`<name>.park`) and stays put, the Makefile
 * waits for the marker, screencaps the emulator, and lets the test move on.
 * Theme is controlled with `cmd uimode night no|yes` before the run.
 *
 * State is seeded through the same repository the app reads (instrumented tests
 * share the app's data dir), so the screenshots show a realistic post-setup
 * device: a PIN is set, the threat streak is at 2/3, and the three scoring
 * groups are marked as active today for the Security Activity screen. The app's
 * master switch is left OFF (fresh default) so the background watchdog stays
 * passive and never fires a real alarm over the seeded state.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun parkOnEachView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SecurityStateRepository(context)
        seedRepository(repository)

        // Clear any leftover markers from a previous run so the host's poll
        // can't trip on a stale one before this run parks on the view.
        context.filesDir.listFiles { it.name.endsWith(".park") }?.forEach { it.delete() }

        val scenario = ActivityScenario.launch(MainActivity::class.java)

        fun park(name: String) {
            closeKeyboard(scenario)
            File(context.filesDir, "$name.park").writeText("1")
            Thread.sleep(PARK_MS)
        }

        // 1. Lock screen — every app launch lands here until the Armed PIN is entered.
        waitForText("Enter Armed PIN")
        composeRule.waitForIdle()
        park("pin-lock")

        // 2. Unlock with the seeded PIN -> Dashboard. The threat score is at 2 of 3
        //    points so the hero meter shows "At Risk" and the streak is clearable.
        composeRule.onNode(hasSetTextAction()).performTextReplacement(PIN)
        composeRule.onNodeWithText("Unlock").performClick()
        waitForText("THREAT SCORE")
        composeRule.waitForIdle()
        park("dashboard")

        // 3. Security Activity via the bottom bar — all three scoring groups were
        //    claimed "today", so they render under ACTIVE CATEGORIES.
        composeRule.onNodeWithTag("tabActivity").performClick()
        waitForText("Security Activity")
        composeRule.waitForIdle()
        park("activity")

        // 4. The Violations tab of the Guide, reached from the breach details link.
        composeRule.onNodeWithText("What triggers a violation?").performScrollTo().performClick()
        waitForText("HOW TO READ THIS GUIDE")
        composeRule.waitForIdle()
        park("guide-violations")

        // 5. Back to the dashboard, then the Protection Vectors tab of the Guide.
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("THREAT SCORE")
        composeRule.onNodeWithTag("tabGuide").performClick()
        waitForText("SHIELD ARCHITECTURE")
        composeRule.waitForIdle()
        park("guide-vectors")

        // 6. Security Settings — captured at several scroll positions so the docs
        //    can show the whole long screen. The card column scrolls as one unit,
        //    so scrolling to a section label brings that card into view.
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("THREAT SCORE")
        composeRule.onNodeWithTag("tabSettings").performClick()
        waitForText("Security Settings")
        composeRule.waitForIdle()
        park("settings")

        // 6a. Scrolled to the middle cards (PIN security, thresholds & timers).
        composeRule.onNodeWithText("THRESHOLDS & TIMERS").performScrollTo()
        composeRule.waitForIdle()
        park("settings-mid")

        // 6b. Scrolled to the hardening & wipe card.
        composeRule.onNodeWithText("HARDENING & WIPE").performScrollTo()
        composeRule.waitForIdle()
        park("settings-scope")

        // 6c. Scrolled to the bottom (developer testing, Done, reset).
        composeRule.onNodeWithText("Reset to factory defaults").performScrollTo()
        composeRule.waitForIdle()
        park("settings-bottom")

        // 7. Change Armed PIN screen.
        composeRule.onNodeWithText("Manage / Reset Armed PIN").performScrollTo().performClick()
        waitForText("Change Armed PIN")
        composeRule.waitForIdle()
        park("pin-change")

        // 8. Simulated wipe screen. The wipe UI is driven by MainActivity's
        //    collection of the repository's process-wide security-state flow, so
        //    setting WIPING in the repository surfaces the screen immediately —
        //    no activity recreation is needed.
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Security Settings")
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("THREAT SCORE")
        repository.saveConfig(com.airgate.domain.model.AppConfig(dryRunMode = false))
        repository.setSecurityState(SecurityState.WIPING)
        waitForText("FACTORY RESET PENDING")
        composeRule.waitForIdle()
        park("wipe")

        scenario.close()
    }

    /**
     * Seeds the shared prefs-backed repository with a realistic post-setup state:
     * an Armed PIN, a 2/3 threat streak, and per-group violation counts plus
     * reasons so the Security Activity screen is populated.
     */
    private fun seedRepository(repository: SecurityStateRepository) {
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(PIN, salt)
        repository.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        repository.setStreak(2)

        // Each seed explicitly claims its violation type's scoring group for today
        // (the tracker stamps the group date), so the three categories show as ACTIVE.
        seedActive(repository, ViolationType.BLUETOOTH_ACTIVITY, 3, "Bluetooth activity detected")
        seedActive(repository, ViolationType.USB_HOST_LINK, 2, "USB device connected")
        seedActive(repository, ViolationType.SYSTEM_CLOCK_CHANGED, 1, "System clock changed")
    }

    private fun seedActive(
        repository: SecurityStateRepository,
        violationType: ViolationType,
        count: Int,
        reason: String
    ) {
        repository.recordVtBreach(violationType)
        // Claim the scoring group's point so the category renders as ACTIVE.
        repository.claimScoringGroupPoint(violationType)
        repository.setVtCount(violationType, count)
        repository.recordVtReason(violationType, reason)
    }

    private fun closeKeyboard(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.currentFocus?.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String, substring: Boolean = true) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** How long to stay parked on each view so the host can screencap. */
        const val PARK_MS = 8_000L

        const val PIN = "246810"
    }
}
