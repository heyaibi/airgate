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

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.PinLockoutPolicy
import com.airgate.data.repository.SecurityStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import com.airgate.testutil.crypto.AndroidKeyStoreRule

/**
 * Rendered-behavior tests for [PinVerifyDialog]: verifies the fail-closed gate
 * is surfaced in the UI. Every branch of the gate must block without calling
 * `onVerified` unless a real, correct PIN is entered:
 *
 *  1. no PIN configured       -> blocked, "No Armed PIN configured…" shown
 *  2. PIN set but unreadable  -> blocked, "PIN data is unreadable." shown
 *  3. correct PIN             -> `onVerified` called
 *  4. incorrect PIN           -> "Incorrect PIN…" shown, `onVerified` not called
 *  5. lockout active          -> countdown shown, Verify disabled
 *
 * The dialog verification runs real PBKDF2 off the main thread, so the correct/
 * incorrect branches wait (real time) for the async result via waitUntil.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinVerifyDialogTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noPinConfigured_blocksWithoutCallingOnVerified() {
        val repository = freshRepository()
        val verified = mutableStateOf(false)
        launchDialog(repository) { verified.value = true }

        composeRule.onNodeWithText("Verify").performClick()

        composeRule.onNodeWithText("No Armed PIN configured", substring = true).assertIsDisplayed()
        assertFalse(verified.value)
    }

    @Test
    fun unreadablePin_blocksWithoutCallingOnVerified() {
        // The PIN keys exist (so the PIN is "configured") but the protected blobs
        // cannot be decoded/decrypted — the tamper/corruption case. The gate must
        // fail closed in the UI, never treat this as authorization.
        val prefs = freshPrefs()
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .commit()
        val repository = SecurityStateRepository(prefs)
        val verified = mutableStateOf(false)
        launchDialog(repository) { verified.value = true }

        composeRule.onNodeWithText("Verify").performClick()

        composeRule.onNodeWithText("PIN data is unreadable", substring = true).assertIsDisplayed()
        assertFalse(verified.value)
    }

    @Test
    fun correctPin_callsOnVerified() {
        val repository = freshRepository()
        setPin(repository, PIN)
        val verified = mutableStateOf(false)
        launchDialog(repository) { verified.value = true }

        composeRule.onNode(hasSetTextAction()).performTextReplacement(PIN)
        composeRule.onNodeWithText("Verify").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { verified.value }
        assertTrue(verified.value)
    }

    @Test
    fun incorrectPin_showsIncorrectAndDoesNotCallOnVerified() {
        val repository = freshRepository()
        setPin(repository, PIN)
        val verified = mutableStateOf(false)
        launchDialog(repository) { verified.value = true }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("000000")
        composeRule.onNodeWithText("Verify").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Incorrect PIN", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(verified.value)
    }

    @Test
    fun lockoutActive_showsCountdownAndDisablesVerify() {
        val repository = freshRepository()
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 60_000L)
        // Freeze the clock so the countdown stays visible instead of expiring.
        composeRule.mainClock.autoAdvance = false
        val verified = mutableStateOf(false)
        launchDialog(repository) { verified.value = true }

        composeRule.onNodeWithText("Too many failed attempts", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Verify").assertIsNotEnabled()
        assertFalse(verified.value)
    }

    @Test
    fun fifthWrongPin_persistsAMonotonicLockoutDeadline() {
        // The write path: the fifth failure must record the lockout deadline on
        // the monotonic clock (getMonotonicNow + base lockout), never the wall
        // clock — a rolled-back wall clock must not clear it.
        val repository = freshRepository()
        setPin(repository, PIN)
        repeat(4) { repository.incrementPinFailedAttempts() }
        assertEquals(4, repository.getPinFailedAttempts())

        val verified = mutableStateOf(false)
        launchDialog(repository) { verified.value = true }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("000000")
        composeRule.onNodeWithText("Verify").performClick()

        // The fifth failure flips the dialog straight into the locked view, so the
        // "Incorrect PIN" text never sticks; wait for the lockout to be recorded.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            repository.getPinLockoutRemainingMs() > 0L
        }

        assertEquals(5, repository.getPinFailedAttempts())
        val deadline = repository.getPinLockoutUntil()
        val now = repository.getMonotonicNow()
        assertTrue("deadline must be in the future on the monotonic clock", deadline > now)
        // The deadline must be monotonic-now + the base lockout: a wall-clock
        // timestamp (~1.7e12) would be far from that window.
        val expected = now + PinLockoutPolicy.lockoutMs(5)
        assertTrue(
            "deadline $deadline must sit near monotonic-now + base lockout $expected",
            kotlin.math.abs(deadline - expected) < 5_000L
        )
        assertTrue(repository.getPinLockoutRemainingMs() in 1..PinLockoutPolicy.BASE_LOCKOUT_MS)

        composeRule.onNodeWithText("Too many failed attempts", substring = true).assertIsDisplayed()
        assertFalse(verified.value)
    }

    private fun freshPrefs(): SharedPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return prefs
    }

    private fun freshRepository(): SecurityStateRepository = SecurityStateRepository(freshPrefs())

    private fun setPin(repository: SecurityStateRepository, pin: String) {
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)
        repository.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
    }

    private fun launchDialog(repository: SecurityStateRepository, onVerified: () -> Unit) {
        composeRule.setContent {
            PinVerifyDialog(
                repository = repository,
                title = "Confirm Action",
                description = "Enter your Armed PIN.",
                confirmLabel = "Verify",
                onDismiss = {},
                onVerified = onVerified
            )
        }
    }

    private companion object {
        /** Isolated prefs file so the test never touches the app's real state. */
        const val PREFS_NAME = "pin_gate_test_prefs"

        const val PIN = "246810"
    }
}
