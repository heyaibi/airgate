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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinStoreTest {

    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var store: ProtectedPrefsStore
    private lateinit var clock: MonotonicClock
    private lateinit var pinStore: PinStore

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        store = ProtectedPrefsStore(prefs, JvmPrefsCrypto())
        clock = MonotonicClock(prefs) { 0L }
        pinStore = PinStore(prefs, store, clock)
    }

    @Test
    fun `isPinSet returns false when no pin is configured`() {
        assertFalse(pinStore.isPinSet())
    }

    @Test
    fun `isPinSet returns true after savePin`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        pinStore.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertTrue(pinStore.isPinSet())
    }

    @Test
    fun `getPinData returns null when no pin is configured`() {
        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns pin data after savePin`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        val iterations = 50_000
        val algorithm = "PBKDF2WithHmacSHA256"
        pinStore.savePin(hash, salt, iterations, algorithm)

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(hash.toList(), pinData?.hash?.toList())
        assertEquals(salt.toList(), pinData?.salt?.toList())
        assertEquals(iterations, pinData?.iterations)
        assertEquals(algorithm, pinData?.algorithm)
    }

    @Test
    fun `savePin persists iterations and algorithm`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        val iterations = 100_000
        val algorithm = "PBKDF2WithHmacSHA512"
        pinStore.savePin(hash, salt, iterations, algorithm)

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(iterations, pinData?.iterations)
        assertEquals(algorithm, pinData?.algorithm)
    }

    @Test
    fun `savePin overwrites existing pin data`() {
        val hash1 = byteArrayOf(1, 2, 3, 4)
        val salt1 = byteArrayOf(5, 6, 7, 8)
        val hash2 = byteArrayOf(9, 10, 11, 12)
        val salt2 = byteArrayOf(13, 14, 15, 16)

        pinStore.savePin(hash1, salt1, 1000, PinManager.DEFAULT_ALGORITHM)
        pinStore.savePin(hash2, salt2, 2000, "PBKDF2WithHmacSHA512")

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(hash2.toList(), pinData?.hash?.toList())
        assertEquals(salt2.toList(), pinData?.salt?.toList())
        assertEquals(2000, pinData?.iterations)
        assertEquals("PBKDF2WithHmacSHA512", pinData?.algorithm)
    }

    @Test
    fun `getPinData defaults iterations to 120k when not stored`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        // Simulate old storage without iterations/algorithm keys
        store.protectedPutString("pin_hash", java.util.Base64.getEncoder().encodeToString(hash))
        store.protectedPutString("pin_salt", java.util.Base64.getEncoder().encodeToString(salt))
        // Do NOT put pin_iterations or pin_algorithm

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }

    @Test
    fun `getPinData returns null for unreadable hash`() {
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for empty hash after decode`() {
        store.protectedPutString("pin_hash", "")
        store.protectedPutString("pin_salt", "")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `failed attempt counter starts at zero`() {
        assertEquals(0, pinStore.getPinFailedAttempts())
    }

    @Test
    fun `incrementPinFailedAttempts returns incremented count`() {
        assertEquals(1, pinStore.incrementPinFailedAttempts())
        assertEquals(2, pinStore.incrementPinFailedAttempts())
        assertEquals(3, pinStore.incrementPinFailedAttempts())
    }

    @Test
    fun `resetPinFailedAttempts clears counter and lockout`() {
        pinStore.incrementPinFailedAttempts()
        pinStore.incrementPinFailedAttempts()
        pinStore.setPinLockoutUntil(999L)

        pinStore.resetPinFailedAttempts()

        assertEquals(0, pinStore.getPinFailedAttempts())
        assertEquals(0L, pinStore.getPinLockoutUntil())
    }

    @Test
    fun `getPinLockoutRemainingMs returns zero when no lockout`() {
        assertEquals(0L, pinStore.getPinLockoutRemainingMs())
    }

    @Test
    fun `PinData equality`() {
        val data1 = PinData(byteArrayOf(1, 2), byteArrayOf(3, 4), 1000, "PBKDF2")
        val data2 = PinData(byteArrayOf(1, 2), byteArrayOf(3, 4), 1000, "PBKDF2")
        val data3 = PinData(byteArrayOf(1, 2), byteArrayOf(3, 4), 2000, "PBKDF2")

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
        assertTrue(data1 != data3)
    }

    @Test
    fun `PinData content equality for byte arrays`() {
        val data1 = PinData(byteArrayOf(1, 2), byteArrayOf(3, 4), 1000, "PBKDF2")
        val data2 = PinData(byteArrayOf(1, 2), byteArrayOf(3, 4), 1000, "PBKDF2")

        assertTrue(data1.hash.contentEquals(data2.hash))
        assertTrue(data1.salt.contentEquals(data2.salt))
    }

    @Test
    fun `savePin with default iterations and algorithm`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        pinStore.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }

    @Test
    fun `migration old pin without iterations and algorithm verifies with defaults`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        // Simulate old storage
        store.protectedPutString("pin_hash", java.util.Base64.getEncoder().encodeToString(hash))
        store.protectedPutString("pin_salt", java.util.Base64.getEncoder().encodeToString(salt))

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }
}
