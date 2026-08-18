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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of PinStore's PIN credential persistence. The whole
 * credential is stored as one atomic, versioned record under a single key, so
 * a save either replaces the previous complete record or leaves it untouched —
 * never a torn hash/salt pair that could brick authentication. Also verifies
 * that an upgrade from the legacy multi-key format never bricks an existing PIN.
 */
@RunWith(AndroidJUnit4::class)
class PinStoreInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var pinStore: PinStore
    private lateinit var store: ProtectedPrefsStore
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        ProtectedPrefsStore.consumeProcessTamperFlag()
        prefs = newPrefs("pin_store_instrumented")
        store = ProtectedPrefsStore(prefs)
        val clock = MonotonicClock(prefs)
        pinStore = PinStore(prefs, store, clock)
    }

    private fun newPrefs(tag: String): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(
            "${tag}_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }

    @Test
    fun savePin_returnsTrueAndPersistsWholeCredentialUnderOneKey() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        val iterations = 50_000
        val algorithm = "PBKDF2WithHmacSHA256"

        val saved = pinStore.savePin(hash, salt, iterations, algorithm)

        assertTrue(saved)
        assertTrue("the record key must hold the credential", prefs.contains("pin_record"))
        assertFalse("no legacy hash key may be written", prefs.contains("pin_hash"))
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

        val saved = pinStore.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertTrue(saved)
        val pinData = pinStore.getPinData()
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

        assertTrue(pinStore.savePin(hash1, salt1, 1000, PinManager.DEFAULT_ALGORITHM))
        assertTrue(pinStore.savePin(hash2, salt2, 2000, "PBKDF2WithHmacSHA512"))

        val pinData = pinStore.getPinData()
        assertNotNull(pinData)
        assertEquals(hash2.toList(), pinData?.hash?.toList())
        assertEquals(salt2.toList(), pinData?.salt?.toList())
        assertEquals(2000, pinData?.iterations)
        assertEquals("PBKDF2WithHmacSHA512", pinData?.algorithm)
    }

    @Test
    fun isPinSet_reflects_savePin_state() {
        assertFalse(pinStore.isPinSet())

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

        val saved = pinStore.savePin(hash, salt, 1000, PinManager.DEFAULT_ALGORITHM)

        assertTrue(saved)
        val pinData = pinStore.getPinData()
        assertNotNull(pinData)
        assertTrue(pinManager.verifyPin(pin, pinData!!.salt, pinData.hash, pinData.iterations, pinData.algorithm))
    }

    @Test
    fun savePin_returnsFalseAndPersistsNothing_whenCryptoUnavailable() {
        val noCryptoStore = ProtectedPrefsStore(prefs, cryptoFactory = { null })
        val noCrypto = PinStore(prefs, noCryptoStore, MonotonicClock(prefs))

        val result = noCrypto.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)

        assertFalse(result)
        assertNull(prefs.getString("pin_record", null))
        assertFalse(pinStore.isPinSet())
        assertTrue(noCryptoStore.consumeTamperFlag())
    }

    @Test
    fun savePin_refusesInvalidCredential() {
        assertFalse(pinStore.savePin(ByteArray(0), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM))
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), ByteArray(0), 1000, PinManager.DEFAULT_ALGORITHM))
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 0, PinManager.DEFAULT_ALGORITHM))
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, ""))
        assertNull(prefs.getString("pin_record", null))
        assertFalse(pinStore.isPinSet())
    }

    @Test
    fun getPinData_returns_null_for_corrupted_record() {
        assertTrue(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM))
        prefs.edit().putString("pin_record", "enc:broken").commit()

        assertNull(pinStore.getPinData())
        assertTrue("an unreadable record blob must set the tamper flag", store.consumeTamperFlag())
        assertTrue("the corrupted record is still 'set' so the owner is routed to re-provision", pinStore.isPinSet())
    }

    @Test
    fun getPinData_failsClosed_onCorruptRecord_evenWithStaleLegacyCredential() {
        // The anti-downgrade guarantee on-device: a present-but-unreadable record
        // must NOT fall through to a stale legacy credential.
        assertTrue(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM))
        ProtectedPrefsStore(prefs).protectedPutString("pin_hash", java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9, 9)))
        ProtectedPrefsStore(prefs).protectedPutString("pin_salt", java.util.Base64.getEncoder().encodeToString(byteArrayOf(8, 8, 8, 8)))

        prefs.edit().putString("pin_record", "enc:broken").commit()

        assertNull("a corrupt record must fail closed, never fall through to the stale legacy credential", pinStore.getPinData())
        assertTrue(store.consumeTamperFlag())
        assertTrue(pinStore.isPinSet())
    }

    @Test
    fun getPinData_returns_null_for_nonNumericIterations() {
        ProtectedPrefsStore(prefs).protectedPutString("pin_record", "v1:QUJD:abc:QUJD:QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun getPinData_returns_null_for_invalidBase64Hash() {
        ProtectedPrefsStore(prefs).protectedPutString("pin_record", "v1:QUJD:1000:QUJD:not-base64")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun getPinData_returns_null_for_emptyAlgorithmAfterDecode() {
        ProtectedPrefsStore(prefs).protectedPutString("pin_record", "v1:QUJD:1000::QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun getPinData_prefers_record_over_legacy_keys() {
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)
        ProtectedPrefsStore(prefs).protectedPutString("pin_hash", java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9, 9)))
        ProtectedPrefsStore(prefs).protectedPutString("pin_salt", java.util.Base64.getEncoder().encodeToString(byteArrayOf(8, 8, 8, 8)))

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals("the record must win over the legacy keys", byteArrayOf(1, 2, 3, 4).toList(), pinData?.hash?.toList())
    }

    @Test
    fun getPinData_defaults_to_120k_iterations_when_not_stored() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        // Simulate old storage without iterations/algorithm keys: the legacy
        // multi-key format. An upgrade must read it with defaults, never brick.
        ProtectedPrefsStore(prefs).protectedPutString("pin_hash", java.util.Base64.getEncoder().encodeToString(hash))
        ProtectedPrefsStore(prefs).protectedPutString("pin_salt", java.util.Base64.getEncoder().encodeToString(salt))

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }

    @Test
    fun getPinData_returns_null_for_unreadable_legacy_hash() {
        val prefs = newPrefs("pin_store_unreadable")
        val store = ProtectedPrefsStore(prefs)
        val clock = MonotonicClock(prefs)
        val unreadableStore = PinStore(prefs, store, clock)

        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .commit()

        assertNull(unreadableStore.getPinData())
    }
}
