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
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class PinStoreTest {

    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var store: ProtectedPrefsStore
    private lateinit var clock: MonotonicClock
    private lateinit var pinStore: PinStore

    @Before
    fun setUp() {
        // The tamper flag is process-wide; each test must start with a clean
        // latch so a latched flag from one test never leaks into another.
        ProtectedPrefsStore.consumeProcessTamperFlag()
        prefs = InMemorySharedPreferences()
        store = ProtectedPrefsStore(prefs, JvmPrefsCrypto())
        clock = MonotonicClock(prefs) { 0L }
        pinStore = PinStore(prefs, store, clock)
    }

    // A store whose keystore-backed crypto acquisition deterministically yields
    // nothing, standing in for "AndroidKeyStore unavailable".
    private fun storeWithoutCrypto(): ProtectedPrefsStore =
        ProtectedPrefsStore(prefs, cryptoFactory = { null })

    /**
     * A [PrefsCrypto] that can read existing blobs (delegates to the healthy
     * JVM crypto) but whose encrypt always throws — standing in for a keystore
     * that can read but cannot write new values.
     */
    private class EncryptThrowingButReadableCrypto : PrefsCrypto {
        private val delegate = JvmPrefsCrypto()
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            delegate.decrypt(ciphertext, iv, aad)
        override fun hmac(data: ByteArray): ByteArray = delegate.hmac(data)
    }

    // --- isPinSet ---

    @Test
    fun `isPinSet returns false when no pin is configured`() {
        assertFalse(pinStore.isPinSet())
    }

    @Test
    fun `isPinSet returns true after savePin`() {
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)

        assertTrue(pinStore.isPinSet())
    }

    @Test
    fun `isPinSet is true when only the single record exists`() {
        store.protectedPutString("pin_record", "v1:AAAA:1000:QUJD:QUJD")

        assertTrue(pinStore.isPinSet())
    }

    @Test
    fun `isPinSet is true when only the legacy keys exist`() {
        // Pre-single-record install: hash and salt under their own keys. An
        // upgrade must still see the PIN as set.
        store.protectedPutString("pin_hash", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)))
        store.protectedPutString("pin_salt", Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8)))

        assertTrue(pinStore.isPinSet())
    }

    @Test
    fun `isPinSet is false when only one legacy key exists`() {
        // A torn legacy install (the old bug's residue) must not look like a
        // usable PIN: one key alone cannot authenticate.
        store.protectedPutString("pin_hash", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)))

        assertFalse(pinStore.isPinSet())
    }

    // --- savePin success paths ---

    @Test
    fun `savePin returns true on success`() {
        assertTrue(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM))
    }

    @Test
    fun `savePin stores the whole credential under the single record key`() {
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)

        assertTrue("the record key must hold the credential", prefs.contains("pin_record"))
        assertFalse("no legacy hash key may be written", prefs.contains("pin_hash"))
        assertFalse("no legacy salt key may be written", prefs.contains("pin_salt"))
        assertFalse("no legacy iterations key may be written", prefs.contains("pin_iterations"))
        assertFalse("no legacy algorithm key may be written", prefs.contains("pin_algorithm"))
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
    fun `a saved record is readable by a fresh store instance`() {
        // A new PinStore over the same prefs stands in for a process restart:
        // the credential must survive without any in-memory carry-over.
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        pinStore.savePin(hash, salt, 1000, PinManager.DEFAULT_ALGORITHM)

        val reloaded = PinStore(prefs, ProtectedPrefsStore(prefs, JvmPrefsCrypto()), clock)

        assertTrue(reloaded.isPinSet())
        val pinData = reloaded.getPinData()
        assertEquals(hash.toList(), pinData?.hash?.toList())
        assertEquals(salt.toList(), pinData?.salt?.toList())
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

    // --- savePin refusal: invalid credentials ---

    @Test
    fun `savePin refuses an empty hash`() {
        assertFalse(pinStore.savePin(ByteArray(0), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM))
        assertNull("a refused save must persist nothing", pinStore.getPinData())
        assertFalse(pinStore.isPinSet())
        assertNull("no record key may be written", prefs.getString("pin_record", null))
        assertFalse("no legacy hash key may be written", prefs.contains("pin_hash"))
        assertFalse("no legacy salt key may be written", prefs.contains("pin_salt"))
    }

    @Test
    fun `savePin refuses an empty salt`() {
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), ByteArray(0), 1000, PinManager.DEFAULT_ALGORITHM))
        assertNull(pinStore.getPinData())
        assertFalse(pinStore.isPinSet())
        assertNull("no record key may be written", prefs.getString("pin_record", null))
        assertFalse("no legacy hash key may be written", prefs.contains("pin_hash"))
        assertFalse("no legacy salt key may be written", prefs.contains("pin_salt"))
    }

    @Test
    fun `savePin refuses a non-positive iteration count`() {
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 0, PinManager.DEFAULT_ALGORITHM))
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), -5, PinManager.DEFAULT_ALGORITHM))
        assertNull(pinStore.getPinData())
        assertFalse(pinStore.isPinSet())
        assertNull("no record key may be written", prefs.getString("pin_record", null))
        assertFalse("no legacy hash key may be written", prefs.contains("pin_hash"))
        assertFalse("no legacy salt key may be written", prefs.contains("pin_salt"))
    }

    @Test
    fun `savePin refuses an empty algorithm`() {
        assertFalse(pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, ""))
        assertNull(pinStore.getPinData())
        assertFalse(pinStore.isPinSet())
        assertNull("no record key may be written", prefs.getString("pin_record", null))
        assertFalse("no legacy hash key may be written", prefs.contains("pin_hash"))
        assertFalse("no legacy salt key may be written", prefs.contains("pin_salt"))
    }

    @Test
    fun `a refused save leaves a prior credential fully usable`() {
        // The crown-jewel case: a healthy credential is saved, then a save over
        // a keystore that cannot write is refused. The old credential must stay
        // readable and set — the owner is never left unarmed or locked out.
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)

        val failingStore = PinStore(prefs, ProtectedPrefsStore(prefs, EncryptThrowingButReadableCrypto()), clock)
        val refused = failingStore.savePin(byteArrayOf(9, 9, 9, 9), byteArrayOf(8, 8, 8, 8), 2000, PinManager.DEFAULT_ALGORITHM)

        assertFalse("a refused write must report failure", refused)
        assertTrue("the prior credential must remain set", pinStore.isPinSet())
        val pinData = pinStore.getPinData()
        assertNotNull(pinData)
        assertEquals("the prior credential must still read back", byteArrayOf(1, 2, 3, 4).toList(), pinData?.hash?.toList())
    }

    // --- savePin refusal: storage layer failures ---

    @Test
    fun `savePin returns false when crypto is unavailable and persists nothing`() {
        val noCryptoStore = storeWithoutCrypto()
        val noCrypto = PinStore(prefs, noCryptoStore, clock)

        val result = noCrypto.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)

        assertFalse(result)
        assertNull("a refused save must never persist the credential", prefs.getString("pin_record", null))
        assertFalse(pinStore.isPinSet())
        assertTrue("a refused write must latch the tamper flag", noCryptoStore.consumeTamperFlag())
    }

    @Test
    fun `savePin returns false when the commit fails`() {
        val commitFailingPrefs = CommitFailingSharedPreferences()
        val protectedStore = ProtectedPrefsStore(commitFailingPrefs, JvmPrefsCrypto())
        val failingStore = PinStore(
            commitFailingPrefs,
            protectedStore,
            MonotonicClock(commitFailingPrefs) { 0L }
        )

        val result = failingStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)

        assertFalse("a failed commit must report failure", result)
        assertFalse("a failed commit must not persist the record", failingStore.isPinSet())
        assertTrue("a failed commit must latch the tamper flag", protectedStore.consumeTamperFlag())
    }

    // --- getPinData: record reading and fail-closed parsing ---

    @Test
    fun `getPinData prefers the single record over the legacy keys`() {
        // An upgrade that has both formats must trust the record, never a stale
        // legacy credential the record superseded.
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)
        store.protectedPutString("pin_hash", Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9, 9)))
        store.protectedPutString("pin_salt", Base64.getEncoder().encodeToString(byteArrayOf(8, 8, 8, 8)))

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals("the record must win over the legacy keys", byteArrayOf(1, 2, 3, 4).toList(), pinData?.hash?.toList())
    }

    @Test
    fun `getPinData fails closed on a corrupted record and latches the tamper flag`() {
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)
        val stored = prefs.getString("pin_record", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val cipher = Base64.getDecoder().decode(parts[1]).apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        prefs.edit()
            .putString("pin_record", "enc:" + parts[0] + ":" + Base64.getEncoder().encodeToString(cipher) + ":" + parts[2])
            .commit()

        assertNull("a corrupted record must fail closed", pinStore.getPinData())
        assertTrue("a corrupted record must set the tamper flag", store.consumeTamperFlag())
        assertTrue("the corrupted record is still 'set' so the owner is routed to re-provision", pinStore.isPinSet())
    }

    @Test
    fun `getPinData returns null for an unknown record version`() {
        // A record written by a newer app version is not readable by this one,
        // but it decrypted cleanly, so it is not tampering.
        store.protectedPutString("pin_record", "v2:QUJD:1000:QUJD:QUJD")

        assertNull(pinStore.getPinData())
        assertFalse("an unsupported version is not tampering", store.consumeTamperFlag())
    }

    @Test
    fun `getPinData returns null for a malformed record`() {
        store.protectedPutString("pin_record", "v1:not-base64:1000:QUJD:QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with too few fields`() {
        store.protectedPutString("pin_record", "v1:QUJD:1000:QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with an empty salt`() {
        store.protectedPutString("pin_record", "v1::1000:QUJD:QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with an empty hash`() {
        store.protectedPutString("pin_record", "v1:QUJD:1000:QUJD:")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with a non-positive iteration count`() {
        store.protectedPutString("pin_record", "v1:QUJD:0:QUJD:QUJD")
        assertNull(pinStore.getPinData())

        store.protectedPutString("pin_record", "v1:QUJD:-1:QUJD:QUJD")
        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with a malformed algorithm`() {
        store.protectedPutString("pin_record", "v1:QUJD:1000:not-base64:QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData fails closed when the record blob is unreadable`() {
        prefs.edit().putString("pin_record", "enc:broken").commit()

        assertNull(pinStore.getPinData())
        assertTrue("an unreadable record blob must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun `getPinData fails closed on a corrupt record even when a stale legacy credential exists`() {
        // The anti-downgrade guarantee: a present-but-unreadable record must NOT
        // fall through to a stale legacy credential. An attacker (or a stale
        // upgrade residue) must never be able to resurrect an old PIN by
        // corrupting the record that superseded it.
        pinStore.savePin(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), 1000, PinManager.DEFAULT_ALGORITHM)
        // Simulate a stale legacy credential that predates the record.
        store.protectedPutString("pin_hash", Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9, 9)))
        store.protectedPutString("pin_salt", Base64.getEncoder().encodeToString(byteArrayOf(8, 8, 8, 8)))

        // Now corrupt the record: it is present but unreadable.
        prefs.edit().putString("pin_record", "enc:broken").commit()

        assertNull("a corrupt record must fail closed, never fall through to the stale legacy credential", pinStore.getPinData())
        assertTrue("a corrupt record must set the tamper flag", store.consumeTamperFlag())
        assertTrue("the corrupt record is still 'set' so the owner is routed to re-provision", pinStore.isPinSet())
    }

    @Test
    fun `getPinData returns null for a record with a non-numeric iteration count`() {
        store.protectedPutString("pin_record", "v1:QUJD:abc:QUJD:QUJD")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with a hash that is not valid base64`() {
        // The hash field is not just empty, it fails to decode at all.
        store.protectedPutString("pin_record", "v1:QUJD:1000:QUJD:not-base64")

        assertNull(pinStore.getPinData())
    }

    @Test
    fun `getPinData returns null for a record with an empty algorithm after decode`() {
        // A base64 that decodes to an empty string (here the empty string itself).
        store.protectedPutString("pin_record", "v1:QUJD:1000::QUJD")

        assertNull(pinStore.getPinData())
    }

    // --- Legacy read path (upgrade compatibility) ---

    @Test
    fun `getPinData defaults iterations to 120k when not stored`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        store.protectedPutString("pin_hash", Base64.getEncoder().encodeToString(hash))
        store.protectedPutString("pin_salt", Base64.getEncoder().encodeToString(salt))

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
    fun `migration old pin without iterations and algorithm verifies with defaults`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)

        store.protectedPutString("pin_hash", Base64.getEncoder().encodeToString(hash))
        store.protectedPutString("pin_salt", Base64.getEncoder().encodeToString(salt))

        val pinData = pinStore.getPinData()

        assertNotNull(pinData)
        assertEquals(PinManager.DEFAULT_ITERATIONS, pinData?.iterations)
        assertEquals(PinManager.DEFAULT_ALGORITHM, pinData?.algorithm)
    }

    // --- Lockout state ---

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

    /**
     * A [android.content.SharedPreferences] whose commit always fails, standing
     * in for a disk write failure at the persistence layer.
     */
    private class CommitFailingSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val map: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { tempMap[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { tempMap.clear(); return this }
            override fun commit(): Boolean = false
            override fun apply() {
                tempMap.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }
}
