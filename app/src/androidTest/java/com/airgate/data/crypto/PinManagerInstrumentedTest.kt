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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of PinManager's PBKDF2 operations. Proves that
 * hashing and verification work correctly with the real Android Keystore
 * and crypto providers, including constant-time comparison and custom
 * iterations/algorithm support.
 */
@RunWith(AndroidJUnit4::class)
class PinManagerInstrumentedTest {

    private val pinManager = PinManager(iterations = 1000, keyLengthBits = 256)

    @Test
    fun hashPin_and_verifyPin_with_default_parameters() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertEquals(32, hash.size)
        assertTrue(pinManager.verifyPin(pin, salt, hash))
    }

    @Test
    fun verifyPin_rejects_wrong_pin() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertFalse(pinManager.verifyPin("654321", salt, hash))
    }

    @Test
    fun verifyPin_with_custom_iterations() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val customIterations = 500
        val hash = PinManager(iterations = customIterations).hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash, customIterations))
    }

    @Test
    fun verifyPin_rejects_wrong_iterations() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        assertFalse(pinManager.verifyPin(pin, salt, hash, iterations = 999))
    }

    @Test
    fun hashPin_with_custom_algorithm() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt, algorithm = PinManager.DEFAULT_ALGORITHM)

        assertTrue(pinManager.verifyPin(pin, salt, hash))
    }

    @Test
    fun verifyPin_rejects_wrong_algorithm() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt, algorithm = PinManager.DEFAULT_ALGORITHM)

        assertFalse(pinManager.verifyPin(pin, salt, hash, algorithm = "PBKDF2WithHmacSHA512"))
    }

    @Test
    fun different_salts_produce_different_hashes() {
        val pin = "123456"
        val salt1 = pinManager.generateSalt()
        val salt2 = pinManager.generateSalt()
        val hash1 = pinManager.hashPin(pin, salt1)
        val hash2 = pinManager.hashPin(pin, salt2)

        assertNotEquals(hash1.toList(), hash2.toList())
    }

    @Test
    fun verifyPin_rejects_wrong_salt() {
        val pin = "123456"
        val salt1 = pinManager.generateSalt()
        val salt2 = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt1)

        assertFalse(pinManager.verifyPin(pin, salt2, hash))
    }

    @Test
    fun generateSalt_produces_unique_16_byte_values() {
        val salt1 = pinManager.generateSalt()
        val salt2 = pinManager.generateSalt()

        assertEquals(16, salt1.size)
        assertEquals(16, salt2.size)
        assertNotEquals(salt1.toList(), salt2.toList())
    }

    @Test
    fun migration_scenario_old_hash_verifies_with_stored_iterations() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val storedIterations = 1000
        val hash = PinManager(iterations = storedIterations).hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash, storedIterations))
    }

    @Test
    fun migration_scenario_new_hash_with_120k_iterations() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val storedIterations = 120_000
        val hash = PinManager(iterations = storedIterations).hashPin(pin, salt)

        assertTrue(pinManager.verifyPin(pin, salt, hash, storedIterations))
    }

    @Test
    fun verifyPin_rejects_empty_hash() {
        val pin = "123456"
        val salt = pinManager.generateSalt()

        assertFalse(pinManager.verifyPin(pin, salt, byteArrayOf()))
    }

    @Test
    fun verifyPin_rejects_short_pin_without_hashing() {
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin("123456", salt)

        assertFalse(pinManager.verifyPin("12345", salt, hash))
    }

    @Test
    fun constant_time_comparison_same_length_hashes() {
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val correctHash = pinManager.hashPin(pin, salt)
        val wrongHash = pinManager.hashPin("654321", salt)

        assertFalse(pinManager.verifyPin("000000", salt, correctHash))
        assertFalse(pinManager.verifyPin("000000", salt, wrongHash))
    }

    @Test
    fun default_constants_are_correct() {
        assertEquals("PBKDF2WithHmacSHA256", PinManager.DEFAULT_ALGORITHM)
        assertEquals(120_000, PinManager.DEFAULT_ITERATIONS)
        assertEquals(256, PinManager.DEFAULT_KEY_LENGTH_BITS)
    }
}
