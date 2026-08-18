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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.data.repository.PinLockoutPolicy
import com.airgate.data.repository.ProtectedPrefsStore
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
 * Rendered-behavior tests for [AuthPinScreen]. The unlock submit must agree with
 * the verify dialog's fail-closed gate: a missing or unreadable PIN is never
 * verified against an empty hash and never counted as a wrong guess, so a
 * storage fault cannot grow the brute-force lockout. An owner whose PIN data is
 * unreadable is offered re-provisioning instead of a permanent brick.
 *
 * The verification/setup paths run real PBKDF2 off the main thread, so the
 * correct/incorrect branches wait (real time) for the async result via
 * waitUntil.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthPinScreenTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun freshInstall_showsSetupAndAuthenticatesOnNewPin() {
        val repository = freshRepository()
        assertFalse(repository.isPinSet())
        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNodeWithText("Create Armed PIN").assertIsDisplayed()
        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement(PIN)
        composeRule.onNode(hasText("Confirm PIN")).performTextReplacement(PIN)
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { authenticated.value }
        assertTrue(authenticated.value)
        assertTrue(repository.isPinSet())
        assertTrue(repository.isPinUsable())
        assertEquals(0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun setupMode_shortPin_showsError() {
        val repository = freshRepository()
        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement("12345")
        composeRule.onNode(hasText("Confirm PIN")).performTextReplacement("12345")
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        composeRule.onNodeWithText("PIN must be at least 6 digits", substring = true).performScrollTo().assertIsDisplayed()
        assertFalse(authenticated.value)
        assertFalse(repository.isPinSet())
    }

    @Test
    fun setupMode_mismatchedConfirm_showsError() {
        val repository = freshRepository()
        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement("123456")
        composeRule.onNode(hasText("Confirm PIN")).performTextReplacement("654321")
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        composeRule.onNodeWithText("PINs do not match", substring = true).performScrollTo().assertIsDisplayed()
        assertFalse(authenticated.value)
        assertFalse(repository.isPinSet())
    }

    @Test
    fun setupMode_saveFailure_showsErrorAndDoesNotAuthenticate() {
        // A keystore that cannot write must surface as an error instead of
        // pretending the PIN was set: the owner is told the save failed and is
        // never left believing a PIN exists that was never persisted.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "auth_pin_screen_failing_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs, FailingPrefsCrypto())
        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement(PIN)
        composeRule.onNode(hasText("Confirm PIN")).performTextReplacement(PIN)
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Could not save PIN", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(authenticated.value)
        assertFalse(repository.isPinSet())
        assertTrue(repository.consumeStateTamperFlag())
    }

    @Test
    fun unlock_correctPin_authenticatesAndClearsCounter() {
        val repository = freshRepository()
        setPin(repository, PIN)
        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNode(hasSetTextAction()).performTextReplacement(PIN)
        composeRule.onNodeWithText("Unlock").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { authenticated.value }
        assertTrue(authenticated.value)
        assertEquals("a correct entry clears any prior failures", 0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun unlock_incorrectPin_showsErrorAndCounts() {
        val repository = freshRepository()
        setPin(repository, PIN)
        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("000000")
        composeRule.onNodeWithText("Unlock").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Incorrect PIN", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(authenticated.value)
        assertEquals(1, repository.getPinFailedAttempts())
    }

    @Test
    fun unlock_fifthWrongPin_recordsMonotonicLockout() {
        val repository = freshRepository()
        setPin(repository, PIN)
        repeat(4) { repository.incrementPinFailedAttempts() }
        assertEquals(4, repository.getPinFailedAttempts())

        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("000000")
        composeRule.onNodeWithText("Unlock").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            repository.getPinLockoutRemainingMs() > 0L
        }

        assertEquals(5, repository.getPinFailedAttempts())
        val deadline = repository.getPinLockoutUntil()
        val now = repository.getMonotonicNow()
        assertTrue("deadline must be in the future on the monotonic clock", deadline > now)
        val expected = now + PinLockoutPolicy.lockoutMs(5)
        assertTrue(
            "deadline $deadline must sit near monotonic-now + base lockout $expected",
            kotlin.math.abs(deadline - expected) < 5_000L
        )
        assertFalse(authenticated.value)

        composeRule.onNodeWithText("Too many failed attempts", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun lockoutActive_showsCountdownAndDisablesButton() {
        val repository = freshRepository()
        setPin(repository, PIN)
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 60_000L)
        composeRule.mainClock.autoAdvance = false

        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNodeWithText("Too many failed attempts", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Locked", substring = true).assertIsNotEnabled()
        assertFalse(authenticated.value)
    }

    @Test
    fun unreadablePin_blocksEntryWithoutCounting() {
        val prefs = freshPrefs()
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .commit()
        val repository = SecurityStateRepository(prefs)
        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())

        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        // The store is unreadable: entry is blocked, the button offers recovery
        // rather than unlock, and no brute-force state moves.
        composeRule.onNodeWithText("PIN data is unreadable", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Re-provision PIN").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
        assertEquals(0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
        assertFalse(authenticated.value)
    }

    @Test
    fun unreadablePin_repeatedRecoveryActionsNeverCount() {
        val prefs = freshPrefs()
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .commit()
        val repository = SecurityStateRepository(prefs)

        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        // Enter recovery, then hammer the recovery actions (including invalid
        // setup submits). None of it may feed the brute-force lockout.
        composeRule.onNodeWithText("Re-provision PIN").performScrollTo().performClick()
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()
        composeRule.onNodeWithText("PIN must be at least 6 digits", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        assertEquals(
            "an unreadable store must never feed the brute-force lockout",
            0,
            repository.getPinFailedAttempts()
        )
        assertEquals(0L, repository.getPinLockoutUntil())
        assertFalse(authenticated.value)
    }

    @Test
    fun unreadablePin_reprovision_setsNewPinAndAuthenticates() {
        val prefs = freshPrefs()
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .commit()
        val repository = SecurityStateRepository(prefs)

        val authenticated = mutableStateOf(false)
        launchScreen(repository) { authenticated.value = true }

        composeRule.onNodeWithText("Re-provision PIN").performScrollTo().performClick()

        // Recovery mode warns the owner why a new PIN is being set, then takes
        // the normal setup flow.
        composeRule.onNodeWithText("previous Armed PIN could not be read", substring = true)
            .assertIsDisplayed()
        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement(NEW_PIN)
        composeRule.onNode(hasText("Confirm PIN")).performTextReplacement(NEW_PIN)
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { authenticated.value }
        assertTrue(authenticated.value)
        assertTrue("re-provisioning must leave a usable PIN", repository.isPinUsable())
        assertEquals(0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun unreadablePin_reprovision_clearsStaleLockoutAndCounter() {
        val prefs = freshPrefs()
        val repository = SecurityStateRepository(prefs)
        setPin(repository, PIN)
        repeat(2) { repository.incrementPinFailedAttempts() }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 60_000L)
        assertEquals(2, repository.getPinFailedAttempts())
        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        // The PIN record becomes unreadable (tamper/corruption) on top of the stale
        // lockout; recovery must supersede it, never inherit it.
        prefs.edit()
            .putString("pin_record", "enc:broken")
            .commit()
        val corrupted = SecurityStateRepository(prefs)
        assertFalse(corrupted.isPinUsable())

        val authenticated = mutableStateOf(false)
        launchScreen(corrupted) { authenticated.value = true }

        composeRule.onNodeWithText("Re-provision PIN").performScrollTo().performClick()
        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement(NEW_PIN)
        composeRule.onNode(hasText("Confirm PIN")).performTextReplacement(NEW_PIN)
        composeRule.onNodeWithText("Set PIN & Continue").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) { authenticated.value }
        assertTrue(authenticated.value)
        assertEquals(0, corrupted.getPinFailedAttempts())
        assertEquals(0L, corrupted.getPinLockoutUntil())
        assertEquals(0L, corrupted.getPinLockoutRemainingMs())
        assertTrue(corrupted.isPinUsable())
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

    private fun launchScreen(repository: SecurityStateRepository, onAuthenticated: () -> Unit) {
        composeRule.setContent {
            AuthPinScreen(
                repository = repository,
                onAuthenticated = onAuthenticated
            )
        }
    }

    /**
     * A [PrefsCrypto] whose every operation throws, standing in for a keystore
     * that is present but cannot write — every protected save is refused.
     */
    private class FailingPrefsCrypto : PrefsCrypto {
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            throw IllegalStateException("decrypt failed")
        override fun hmac(data: ByteArray): ByteArray =
            throw IllegalStateException("hmac failed")
    }

    private companion object {
        /** Isolated prefs file so the test never touches the app's real state. */
        const val PREFS_NAME = "auth_pin_screen_test_prefs"

        const val PIN = "246810"
        const val NEW_PIN = "135790"
    }
}
