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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of PinStore's PIN credential persistence. Proves that
 * iterations and algorithm are stored alongside the hash and salt, and that
 * old PINs without these fields default to the current values.
 */
@RunWith(AndroidJUnit4::class)
class PinStoreInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var pinStore: PinStore

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences(
            "pin_store_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val store = ProtectedPrefsStore(prefs)
        val clock = MonotonicClock(prefs)
        pinStore = PinStore(prefs, store, clock)
    }

    @Test
    fun savePin_persists_iterations_and_algorithm() {
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
    fun savePin_with_default_iterations_and_algorithm() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        pinStore.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }

    @Test
    fun getPinData_defaults_to_120k_iterations_when_not_stored() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        // Simulate old storage without iterations/algorithm keys
        val prefs = context.getSharedPreferences(
            "pin_store_old_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val store = ProtectedPrefsStore(prefs)
        val clock = MonotonicClock(prefs)
        val oldPinStore = PinStore(prefs, store, clock)

        store.protectedPutString("pin_hash", java.util.Base64.getEncoder().encodeToString(hash))
        store.protectedPutString("pin_salt", java.util.Base64.getEncoder().encodeToString(salt))
        // Do NOT put pin_iterations or pin_algorithm

        val pinData = oldPinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }

    @Test
    fun savePin_overwrites_existing_pin_data() {
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
    fun getPinData_returns_null_for_unreadable_hash() {
        val prefs = context.getSharedPreferences(
            "pin_store_unreadable_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val store = ProtectedPrefsStore(prefs)
        val clock = MonotonicClock(prefs)
        val unreadableStore = PinStore(prefs, store, clock)

        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        assertNull(unreadableStore.getPinData())
    }

    @Test
    fun isPinSet_reflects_savePin_state() {
        assertTrue(!pinStore.isPinSet())

        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        pinStore.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertTrue(pinStore.isPinSet())
    }

    @Test
    fun pin_store_roundtrip_with_real_pbkdf2() {
        val pinManager = PinManager(iterations = 1000)
        val pin = "123456"
        val salt = pinManager.generateSalt()
        val hash = pinManager.hashPin(pin, salt)

        pinStore.savePin(hash, salt, 1000, PinManager.DEFAULT_ALGORITHM)

        val pinData = pinStore.getPinData()
        assertNotNull(pinData)

        assertTrue(pinManager.verifyPin(pin, pinData!!.salt, pinData.hash, pinData.iterations, pinData.algorithm))
    }
}
