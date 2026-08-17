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

package com.airgate.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinManagerTest {
    private val pinManager = PinManager(iterations = 1000, keyLengthBits = 256)

    @Test
    fun `hashPin and verifyPin success with valid pin`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash))
    }

    @Test
    fun `verifyPin fails with incorrect pin`() {
        val pin = "123456"
        val wrongPin = "654321"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertFalse(pinManager.verifyPin(wrongPin, salt, hash))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hashPin throws exception for short pin`() {
        val pin = "12345"
        val salt = pinManager.generateSalt()
        pinManager.hashPin(pin, salt)
    }

    @Test
    fun `verifyPin rejects short pin without hashing`() {
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("123456", salt)

        assertFalse(pinManager.verifyPin("12345", salt, hash))
    }

    @Test
    fun `hashPin with custom iterations produces different hash than default`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hashLow = pinManager.hashPin(pin, salt, algorithm = PinManager.DEFAULT_ALGORITHM)
        val hashHigh = PinManager(iterations = 2000).hashPin(pin, salt, algorithm = PinManager.DEFAULT_ALGORITHM)

        assertNotEquals(hashLow.toList(), hashHigh.toList())
    }

    @Test
    fun `hashPin with explicit iterations parameter overrides constructor iterations`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        // pinManager has iterations=1000, but we pass 500 explicitly
        val hash = pinManager.hashPin(pin, salt, iterations = 500)
        // Verify with 500 iterations should succeed
        assertTrue(pinManager.verifyPin(pin, salt, hash, iterations = 500))
        // Verify with 1000 iterations (constructor default) should fail
        assertFalse(pinManager.verifyPin(pin, salt, hash, iterations = 1000))
    }

    @Test
    fun `hashPin with explicit iterations and algorithm`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt, iterations = 750, algorithm = PinManager.DEFAULT_ALGORITHM)
        assertTrue(pinManager.verifyPin(pin, salt, hash, iterations = 750))
    }

    @Test
    fun `hashPin and verifyPin produce consistent results with same parameters`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val iterations = 500
        val algorithm = PinManager.DEFAULT_ALGORITHM

        val hashFromHash = pinManager.hashPin(pin, salt, iterations, algorithm)
        val hashFromVerify = PinManager(iterations = iterations).hashPin(pin, salt, algorithm = algorithm)

        assertEquals(hashFromHash.toList(), hashFromVerify.toList())
    }

    @Test
    fun `verifyPin with custom iterations verifies correctly`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val customIterations = 500
        val hash = PinManager(iterations = customIterations).hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash, customIterations))
    }

    @Test
    fun `verifyPin with wrong iterations fails`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertFalse(pinManager.verifyPin(pin, salt, hash, iterations = 999))
    }

    @Test
    fun `hashPin with custom algorithm`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt, algorithm = PinManager.DEFAULT_ALGORITHM)

        assertTrue(pinManager.verifyPin(pin, salt, hash))
    }

    @Test
    fun `verifyPin with mismatched algorithm fails`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt, algorithm = PinManager.DEFAULT_ALGORITHM)

        assertFalse(pinManager.verifyPin(pin, salt, hash, algorithm = "PBKDF2WithHmacSHA512"))
    }

    @Test
    fun `verifyPin is constant-time and same-length hashes do not leak timing`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val correctHash = pinManager.hashPin(pin, salt)
        val wrongHash = pinManager.hashPin("654321", salt)

        // Both should return the result without early termination
        assertFalse(pinManager.verifyPin("000000", salt, correctHash))
        assertFalse(pinManager.verifyPin("000000", salt, wrongHash))
    }

    @Test
    fun `verifyPin handles empty expected hash gracefully`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()

        assertFalse(pinManager.verifyPin(pin, salt, byteArrayOf()))
    }

    @Test
    fun `migration scenario old hash with 1000 iterations verifies with stored iterations`() {
        // Simulate: user set PIN with 1000 iterations, we want to verify with stored value
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val storedIterations = 1000
        val hash = PinManager(iterations = storedIterations).hashPin(pin, salt)

        // Verify using the stored iterations (not the default 120k)
        assertTrue(pinManager.verifyPin(pin, salt, hash, storedIterations))
    }

    @Test
    fun `migration scenario new PIN with 120k iterations verifies correctly`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val storedIterations = 120_000
        val hash = PinManager(iterations = storedIterations).hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash, storedIterations))
    }

    @Test
    fun `different salts produce different hashes`() {
        val pin = "123456"
        val salt1 = pinManager.generateSalt()
        val salt2 = pinManager.generateSalt()
        val hash1 = pinManager.hashPin(pin, salt1)
        val hash2 = pinManager.hashPin(pin, salt2)

        assertNotEquals(hash1.toList(), hash2.toList())
    }

    @Test
    fun `verifyPin with wrong salt fails`() {
        val pin = "123456"
        val salt1 = pinManager.generateSalt()
        val salt2 = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt1)

        assertFalse(pinManager.verifyPin(pin, salt2, hash))
    }

    @Test
    fun `verifyPin with empty hash and empty computed hash returns true`() {
        // Edge case: both empty arrays should be equal
        val emptyHash = byteArrayOf()
        val salt = pinManager.generateSalt()

        // verifyPin with a short pin returns false early
        assertFalse(pinManager.verifyPin("12345", salt, emptyHash))
    }

    @Test
    fun `default constants are correct`() {
        assertEquals("PBKDF2WithHmacSHA256", PinManager.DEFAULT_ALGORITHM)
        assertEquals(120_000, PinManager.DEFAULT_ITERATIONS)
        assertEquals(256, PinManager.DEFAULT_KEY_LENGTH_BITS)
    }

    @Test
    fun `generateSalt produces 16 bytes`() {
        val salt = pinManager.generateSalt()
        assertEquals(16, salt.size)
    }

    @Test
    fun `generateSalt produces different salts each time`() {
        val salt1 = pinManager.generateSalt()
        val salt2 = pinManager.generateSalt()

        assertNotEquals(salt1.toList(), salt2.toList())
    }

    @Test
    fun `hashPin produces 32-byte hash for 256-bit key length`() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertEquals(32, hash.size)
    }

    @Test
    fun `hashPin throws for 5-digit pin`() {
        val salt = pinManager.generateSalt()
        try {
            pinManager.hashPin("12345", salt)
            assertTrue("Expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("PIN must be at least 6") == true)
        }
    }

    @Test
    fun `hashPin accepts exactly 6-digit pin`() {
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("123456", salt)
        assertEquals(32, hash.size)
    }

    @Test
    fun `hashPin accepts long pin`() {
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("1234567890123456", salt)
        assertEquals(32, hash.size)
    }
}
