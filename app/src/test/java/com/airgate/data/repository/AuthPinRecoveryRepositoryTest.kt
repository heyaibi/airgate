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

import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository-level recovery semantics behind the unlock screen's re-provision
 * flow: when PIN material is unreadable, setting a fresh PIN overwrites the
 * broken blob so the owner is never permanently locked out, and an unreadable
 * store is never treated as a source of wrong guesses.
 */
class AuthPinRecoveryRepositoryTest {

    private val prefs = InMemorySharedPreferences()
    private val repository = SecurityStateRepository(prefs, JvmPrefsCrypto()) { 0L }

    private fun corruptPinMaterial() {
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()
    }

    @Test
    fun `savePin over an unreadable blob makes the PIN readable again`() {
        corruptPinMaterial()
        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())

        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("135790", salt)
        repository.savePin(hash, salt)

        assertTrue(repository.isPinUsable())
        val data = repository.getPinData()
        assertEquals(hash.toList(), data?.first?.toList())
        assertEquals(salt.toList(), data?.second?.toList())
    }

    @Test
    fun `the new pin verifies after re-provisioning over an unreadable blob`() {
        corruptPinMaterial()
        assertFalse(repository.isPinUsable())

        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("135790", salt)
        repository.savePin(hash, salt)

        assertTrue(
            "the re-provisioned PIN must unlock",
            pinManager.verifyPin("135790", salt, hash)
        )
    }

    @Test
    fun `the previous material is gone after re-provisioning`() {
        setReadablePin("246810")
        val original = repository.getPinData()
        corruptPinMaterial()
        assertFalse(repository.isPinUsable())

        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("135790", salt)
        repository.savePin(hash, salt)

        val replaced = repository.getPinData()
        assertTrue("the new material must differ from the old", replaced!!.first.toList() != original?.first?.toList())
        assertTrue(pinManager.verifyPin("135790", replaced.second, replaced.first))
    }

    @Test
    fun `unreadable pin material leaves the failed-attempt counter untouched`() {
        corruptPinMaterial()

        // Reading the gate (the screen's submit path for an unreadable store)
        // must not advance any brute-force state.
        assertFalse(repository.isPinUsable())
        assertEquals(0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun `an existing stale lockout and counter are cleared by re-provisioning`() {
        setReadablePin("246810")
        repeat(3) { repository.incrementPinFailedAttempts() }
        repository.setPinLockoutUntil(repository.getMonotonicNow() + 60_000L)
        assertEquals(3, repository.getPinFailedAttempts())
        assertTrue(repository.getPinLockoutRemainingMs() > 0L)

        // The screen's re-provision submit path: fresh credential, then reset.
        corruptPinMaterial()
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("135790", salt)
        repository.savePin(hash, salt)
        repository.resetPinFailedAttempts()
        repository.setPinLockoutUntil(0L)

        assertEquals(0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
        assertEquals(0L, repository.getPinLockoutRemainingMs())
        assertTrue(repository.isPinUsable())
    }

    @Test
    fun `re-provisioning never requires a readable old pin`() {
        corruptPinMaterial()

        // The recovery path must not depend on the old material being readable —
        // that would defeat the purpose.
        assertFalse(repository.isPinUsable())
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("135790", salt)

        repository.savePin(hash, salt)

        assertTrue(repository.isPinUsable())
        assertTrue(pinManager.verifyPin("135790", salt, hash))
    }

    private fun setReadablePin(pin: String = "246810") {
        val pinManager = PinManager()
        val salt = pinManager.generateSalt()
        repository.savePin(pinManager.hashPin(pin, salt), salt)
    }
}
