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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.data.repository.SecurityStateRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Rendered-behavior tests for [PinManagementScreen]. A PIN change is persisted
 * only when the new credential verifies AND the atomic save reports success; a
 * refused save surfaces an error and leaves the old PIN fully usable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changePin_success_persistsNewCredentialAndShowsSuccess() {
        val repository = freshRepository()
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        repository.savePin(
            pinManager.hashPin(OLD_PIN, salt),
            salt,
            PinManager.DEFAULT_ITERATIONS,
            PinManager.DEFAULT_ALGORITHM
        )
        assertTrue(repository.isPinUsable())

        composeRule.setContent { PinManagementScreen(repository = repository, onBack = {}) }

        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement(NEW_PIN)
        composeRule.onNode(hasText("Confirm New PIN")).performTextReplacement(NEW_PIN)
        composeRule.onNodeWithText("Update PIN").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("PIN updated successfully", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val pinData = repository.getPinData()
        assertTrue(pinData != null)
        assertTrue(
            "the new PIN must verify against the stored credential",
            pinManager.verifyPin(NEW_PIN, pinData!!.salt, pinData.hash, pinData.iterations, pinData.algorithm)
        )
        assertFalse(
            "the old PIN must stop working after the change",
            pinManager.verifyPin(OLD_PIN, pinData.salt, pinData.hash, pinData.iterations, pinData.algorithm)
        )
    }

    @Test
    fun changePin_saveFailure_showsErrorAndKeepsOldPinUsable() {
        val prefs = prefsForTest()
        val healthy = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        healthy.savePin(
            pinManager.hashPin(OLD_PIN, salt),
            salt,
            PinManager.DEFAULT_ITERATIONS,
            PinManager.DEFAULT_ALGORITHM
        )
        val oldPinData = healthy.getPinData()

        // A repository over the same prefs whose writes always fail: the change
        // must be refused, and the persisted credential must stay the old one.
        val failingRepository = SecurityStateRepository(prefs, FailingPrefsCrypto())
        composeRule.setContent { PinManagementScreen(repository = failingRepository, onBack = {}) }

        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement(NEW_PIN)
        composeRule.onNode(hasText("Confirm New PIN")).performTextReplacement(NEW_PIN)
        composeRule.onNodeWithText("Update PIN").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Could not save PIN", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val pinData = reloaded.getPinData()
        assertTrue(pinData != null)
        assertTrue("the old credential must survive the refused save", pinData!!.hash.contentEquals(oldPinData!!.hash))
        assertTrue(
            "the old PIN must still verify after a refused change",
            pinManager.verifyPin(OLD_PIN, pinData.salt, pinData.hash, pinData.iterations, pinData.algorithm)
        )
    }

    @Test
    fun changePin_shortPin_showsError() {
        val repository = freshRepository()
        composeRule.setContent { PinManagementScreen(repository = repository, onBack = {}) }

        composeRule.onNode(hasText("New PIN (6+ digits)")).performTextReplacement("12345")
        composeRule.onNode(hasText("Confirm New PIN")).performTextReplacement("12345")
        composeRule.onNodeWithText("Update PIN").performScrollTo().performClick()

        composeRule.onNodeWithText("PIN must be at least 6 digits", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    private fun prefsForTest(): SharedPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "pin_management_screen_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }

    private fun freshRepository(): SecurityStateRepository =
        SecurityStateRepository(prefsForTest(), JvmPrefsCrypto())

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
        const val OLD_PIN = "246810"
        const val NEW_PIN = "135790"
    }
}
