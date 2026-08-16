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

import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for the unlock-screen submit decision. The unlock screen and
 * the verify dialog share [resolvePinGate], so they can never disagree about a
 * PIN's state. The contract under test:
 *
 *  1. no PIN configured       -> NoPinConfigured, verification never runs
 *  2. PIN set, unreadable     -> PinUnreadable, verification never runs
 *     (never an empty-hash verify, never a counted failure)
 *  3. PIN set, readable, OK   -> Unlock
 *  4. PIN set, readable, bad  -> IncorrectPin
 */
class AuthPinSubmitDecisionTest {

    private val prefs = InMemorySharedPreferences()
    private val repository = SecurityStateRepository(prefs, JvmPrefsCrypto()) { 0L }

    private val acceptAll: (String, ByteArray, ByteArray) -> Boolean = { _, _, _ -> true }
    private val rejectAll: (String, ByteArray, ByteArray) -> Boolean = { _, _, _ -> false }

    private fun corruptPinMaterial() {
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()
    }

    private fun setReadablePin(pin: String = "123456") {
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        repository.savePin(pinManager.hashPin(pin, salt), salt)
    }

    @Test
    fun `no configured pin resolves to NoPinConfigured`() {
        assertFalse(repository.isPinSet())
        assertEquals(
            AuthPinSubmitDecision.NoPinConfigured,
            decideAuthPinSubmit(repository, acceptAll, "123456")
        )
    }

    @Test
    fun `no configured pin never reaches verification`() {
        var verified = false
        val spy: (String, ByteArray, ByteArray) -> Boolean = { _, _, _ ->
            verified = true
            true
        }

        decideAuthPinSubmit(repository, spy, "123456")

        assertFalse("nothing to verify against when no PIN is configured", verified)
    }

    @Test
    fun `unreadable pin material resolves to PinUnreadable`() {
        corruptPinMaterial()
        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())

        assertEquals(
            AuthPinSubmitDecision.PinUnreadable,
            decideAuthPinSubmit(repository, acceptAll, "123456")
        )
    }

    @Test
    fun `unreadable pin material never verifies against an empty hash`() {
        corruptPinMaterial()
        var verified = false
        val spy: (String, ByteArray, ByteArray) -> Boolean = { _, _, _ ->
            verified = true
            true
        }

        val outcome = decideAuthPinSubmit(repository, spy, "123456")

        assertEquals(AuthPinSubmitDecision.PinUnreadable, outcome)
        assertFalse(
            "an unreadable store must never be verified against an empty hash",
            verified
        )
    }

    @Test
    fun `unreadable pin material is neither an unlock nor a counted failure`() {
        corruptPinMaterial()

        val outcome = decideAuthPinSubmit(repository, acceptAll, "123456")

        assertTrue(outcome is AuthPinSubmitDecision.PinUnreadable)
        assertTrue(outcome !is AuthPinSubmitDecision.Unlock)
        assertTrue(outcome !is AuthPinSubmitDecision.IncorrectPin)
    }

    @Test
    fun `plaintext pin material fails closed as PinUnreadable`() {
        // A plaintext (non-"enc:") value under a protected key carries no
        // integrity binding and is indistinguishable from tampering, so it must
        // fail closed exactly like a corrupt blob.
        prefs.edit()
            .putString("pin_hash", "not-a-blob")
            .putString("pin_salt", "also-not-a-blob")
            .apply()

        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())
        assertEquals(
            AuthPinSubmitDecision.PinUnreadable,
            decideAuthPinSubmit(repository, acceptAll, "123456")
        )
    }

    @Test
    fun `a partially corrupted pin store fails closed as PinUnreadable`() {
        setReadablePin("123456")
        // Break only the hash blob; the salt stays readable. Incomplete material
        // is still unreadable — never a wrong guess.
        prefs.edit().putString("pin_hash", "enc:broken").apply()

        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())
        assertEquals(
            AuthPinSubmitDecision.PinUnreadable,
            decideAuthPinSubmit(repository, acceptAll, "123456")
        )
    }

    @Test
    fun `correct pin on a readable store resolves to Unlock`() {
        setReadablePin("123456")
        val pinManager = PinManager()

        assertEquals(
            AuthPinSubmitDecision.Unlock,
            decideAuthPinSubmit(repository, pinManager::verifyPin, "123456")
        )
    }

    @Test
    fun `incorrect pin on a readable store resolves to IncorrectPin`() {
        setReadablePin("123456")
        val pinManager = PinManager()

        assertEquals(
            AuthPinSubmitDecision.IncorrectPin,
            decideAuthPinSubmit(repository, pinManager::verifyPin, "000000")
        )
    }

    @Test
    fun `a readable store with a rejecting verifier is IncorrectPin`() {
        setReadablePin("123456")

        assertEquals(
            AuthPinSubmitDecision.IncorrectPin,
            decideAuthPinSubmit(repository, rejectAll, "123456")
        )
    }

    @Test
    fun `the stored material is what a correct pin is verified against`() {
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("246810", salt)
        repository.savePin(hash, salt)

        var capturedSalt: ByteArray? = null
        var capturedHash: ByteArray? = null
        val spy: (String, ByteArray, ByteArray) -> Boolean = { _, s, h ->
            capturedSalt = s
            capturedHash = h
            true
        }

        assertEquals(
            AuthPinSubmitDecision.Unlock,
            decideAuthPinSubmit(repository, spy, "anything")
        )
        assertEquals(salt.toList(), capturedSalt?.toList())
        assertEquals(hash.toList(), capturedHash?.toList())
    }

    @Test
    fun `short typed pin is decided only by the stored material, not length`() {
        // The screen filters digits and enforces length at setup; at unlock the
        // decision is purely whether the typed PIN matches the stored material.
        setReadablePin("123456")
        val pinManager = PinManager()

        assertEquals(
            AuthPinSubmitDecision.IncorrectPin,
            decideAuthPinSubmit(repository, pinManager::verifyPin, "12")
        )
    }
}
