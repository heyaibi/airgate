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
import com.airgate.data.crypto.PrefsCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Keyed-MAC integrity coverage for the protected prefs store: a value is
 * encrypt-then-MAC'd under an injected JVM crypto (standing in for the Android
 * Keystore), and any tampering with the stored blob — ciphertext, MAC, or IV —
 * must fail the read and latch the tamper flag instead of returning a value.
 */
class ProtectedPrefsStoreTest {

    private class MockSharedPreferences : android.content.SharedPreferences {
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
            private var clearFlag = false
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { tempMap[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { tempMap[key!!] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { tempMap[key!!] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearFlag = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                tempMap.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private val prefs = MockSharedPreferences()
    private val crypto = JvmPrefsCrypto()
    private lateinit var store: ProtectedPrefsStore

    @Before
    fun setUp() {
        store = ProtectedPrefsStore(prefs, crypto)
    }

    // A store whose keystore-backed crypto acquisition deterministically yields
    // nothing, standing in for "AndroidKeyStore unavailable". The default factory
    // tries the real KeystoreManager, whose availability is environment-dependent
    // (Robolectric tests can install a fake AndroidKeyStore provider process-wide),
    // so the unavailable branch is pinned by injecting the failure explicitly.
    private fun storeWithFallback(): ProtectedPrefsStore = ProtectedPrefsStore(prefs, cryptoFactory = { null })

    // --- protect/unprotect round-trip ---

    @Test
    fun protectThenUnprotect_roundTripsTheOriginalValue() {
        val protected = store.protectString("k", "ALARM_ACTIVE")

        assertEquals("ALARM_ACTIVE", store.unprotectString("k", protected, "DEFAULT"))
    }

    @Test
    fun protect_emitsAnEncryptedThreePartMacBlob() {
        val protected = store.protectString("k", "ALARM_ACTIVE")

        assertTrue(protected.startsWith("enc:"))
        val parts = protected.removePrefix("enc:").split(":")
        assertEquals("stored value must be enc:<iv>:<cipher>:<mac>", 3, parts.size)
        // Every segment is valid base64.
        parts.forEach { Base64.getDecoder().decode(it) }
    }

    @Test
    fun protectTwoValues_samePlaintext_differentCiphertexts() {
        // Encryption must be randomized (fresh IV per write): two protects of the
        // same value must not produce identical blobs, else a replay becomes trivial.
        val a = store.protectString("k", "ALARM_ACTIVE")
        val b = store.protectString("k", "ALARM_ACTIVE")

        assertTrue(a != b)
        assertEquals("ALARM_ACTIVE", store.unprotectString("k", a, "DEFAULT"))
        assertEquals("ALARM_ACTIVE", store.unprotectString("k", b, "DEFAULT"))
    }

    @Test
    fun protectedGetString_roundTripsViaPrefs() {
        store.protectedPutString("k", "VALUE")

        assertEquals("VALUE", store.protectedGetString("k", "DEFAULT"))
    }

    // --- Key binding (the MAC covers the pref key) ---

    @Test
    fun sameValue_protectedForDifferentKeys_doesNotVerifyCrossKey() {
        // The keyed-MAC must bind each value to its pref key: a blob written for
        // key A must never verify under key B, even for identical plaintext.
        val blobA = store.protectString("key_a", "SAME")
        val blobB = store.protectString("key_b", "SAME")

        assertEquals("SAME", store.unprotectString("key_a", blobA, "DEFAULT"))
        assertEquals("SAME", store.unprotectString("key_b", blobB, "DEFAULT"))
        assertEquals("DEFAULT", store.unprotectString("key_b", blobA, "DEFAULT"))
        assertTrue("a cross-key read must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun swappedValuesBetweenKeys_failsClosed_andLatchesTamperFlag() {
        // The ciphertext swap from the threat model: an attacker moves the stored
        // blob of key A into key B. Each blob decrypts fine under its original
        // key, but the MAC under the wrong key must reject the read.
        store.protectedPutString("key_a", "VALUE_A")
        store.protectedPutString("key_b", "VALUE_B")
        val blobA = prefs.getString("key_a", null)!!
        val blobB = prefs.getString("key_b", null)!!

        prefs.edit().putString("key_a", blobB).putString("key_b", blobA).commit()

        assertEquals("DEFAULT", store.protectedGetString("key_a", "DEFAULT"))
        assertTrue("the swapped value must set the tamper flag", store.consumeTamperFlag())
        assertEquals("DEFAULT", store.protectedGetString("key_b", "DEFAULT"))
        assertTrue(store.consumeTamperFlag())
    }

    // --- Tamper detection ---

    @Test
    fun tamperedCiphertext_failsClosed_andLatchesTamperFlag() {
        store.protectedPutString("k", "VALUE")
        val stored = prefs.getString("k", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val cipher = Base64.getDecoder().decode(parts[1]).apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        prefs.edit().putString("k", "enc:" + parts[0] + ":" + Base64.getEncoder().encodeToString(cipher) + ":" + parts[2]).commit()

        val result = store.protectedGetString("k", "DEFAULT")

        // A modified ciphertext must never surface as a real (or defaulted) value.
        assertEquals("DEFAULT", result)
        assertTrue("tampering with the ciphertext must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun tamperedMac_failsClosed_andLatchesTamperFlag() {
        store.protectedPutString("k", "VALUE")
        val stored = prefs.getString("k", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val mac = Base64.getDecoder().decode(parts[2]).apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        prefs.edit().putString("k", "enc:" + parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(mac)).commit()

        val result = store.protectedGetString("k", "DEFAULT")

        assertEquals("DEFAULT", result)
        assertTrue("a MAC mismatch must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun tamperedIv_failsClosed_andLatchesTamperFlag() {
        store.protectedPutString("k", "VALUE")
        val stored = prefs.getString("k", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val iv = Base64.getDecoder().decode(parts[0]).apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        prefs.edit().putString("k", "enc:" + Base64.getEncoder().encodeToString(iv) + ":" + parts[1] + ":" + parts[2]).commit()

        val result = store.protectedGetString("k", "DEFAULT")

        assertEquals("DEFAULT", result)
        assertTrue("a swapped IV must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun replacedEntireBlob_anotherKeysValidBlob_failsClosed() {
        // Encrypt "VALUE" under a second, independent crypto instance; swapping it
        // in for the first store's blob must fail — this is the cross-key swap that
        // only a keyed-MAC (not GCM alone) can bind against.
        val otherStore = ProtectedPrefsStore(MockSharedPreferences(), JvmPrefsCrypto("foreign-keys"))
        val foreign = otherStore.protectString("k", "VALUE")
        prefs.edit().putString("k", foreign).commit()

        val result = store.protectedGetString("k", "DEFAULT")

        assertEquals("DEFAULT", result)
        assertTrue("a foreign-key blob must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun malformedEncBlob_failsClosed_andLatchesTamperFlag() {
        prefs.edit().putString("k", "enc:broken").commit()

        assertEquals("DEFAULT", store.protectedGetString("k", "DEFAULT"))
        assertTrue(store.consumeTamperFlag())
    }

    @Test
    fun consumeTamperFlag_isSingleShot() {
        prefs.edit().putString("k", "enc:broken").commit()
        store.protectedGetString("k", "DEFAULT")

        assertTrue("first consume must observe the tamper", store.consumeTamperFlag())
        assertFalse("the flag is cleared by consumption", store.consumeTamperFlag())
    }

    @Test
    fun healthyRead_doesNotLatchesTamperFlag() {
        store.protectedPutString("k", "VALUE")

        assertEquals("VALUE", store.protectedGetString("k", "DEFAULT"))
        assertFalse("a healthy read must not set the tamper flag", store.consumeTamperFlag())
    }

    // --- Legacy format (pre-keyed-MAC / pre-AAD) ---

    @Test
    fun legacyTwoPartBlob_failsClosed_andLatchesTamperFlag() {
        // Pre-MAC builds stored enc:<iv>:<cipher>. The AES-GCM tag alone cannot
        // bind a blob to its pref key (the ciphertext-swap hole), so the legacy
        // form is deliberately not readable: it fails closed like any tamper.
        val (cipher, iv) = crypto.encrypt("LEGACY".toByteArray(Charsets.UTF_8), ByteArray(0))
        val legacy = "enc:" +
            Base64.getEncoder().encodeToString(iv) + ":" +
            Base64.getEncoder().encodeToString(cipher)
        prefs.edit().putString("k", legacy).commit()

        assertEquals("DEFAULT", store.protectedGetString("k", "DEFAULT"))
        assertTrue("a legacy value must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun tamperedLegacyBlob_failsClosed_andLatchesTamperFlag() {
        val (cipher, iv) = crypto.encrypt("LEGACY".toByteArray(Charsets.UTF_8), ByteArray(0))
        val tamperedCipher = cipher.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val legacy = "enc:" +
            Base64.getEncoder().encodeToString(iv) + ":" +
            Base64.getEncoder().encodeToString(tamperedCipher)
        prefs.edit().putString("k", legacy).commit()

        assertEquals("DEFAULT", store.protectedGetString("k", "DEFAULT"))
        assertTrue("a tampered legacy blob must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun macStrippedFromProtectedBlob_failsClosed_andLatchesTamperFlag() {
        // An attacker strips the keyed-MAC segment from a current blob, leaving
        // enc:<iv>:<cipher>. The ciphertext was encrypted bound to the key, but
        // without the MAC there is no key binding at rest; the downgrade to the
        // legacy form must fail closed, not decrypt.
        store.protectedPutString("k", "VALUE")
        val stored = prefs.getString("k", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val downgraded = "enc:" + parts[0] + ":" + parts[1]
        prefs.edit().putString("k", downgraded).commit()

        assertEquals("DEFAULT", store.protectedGetString("k", "DEFAULT"))
        assertTrue("a MAC-stripped blob must set the tamper flag", store.consumeTamperFlag())
    }

    // --- Keystore-unavailable / encryption failure: fail closed, never plaintext ---

    @Test
    fun keystoreUnavailable_protectString_throws_andLatchesTamperFlag() {
        val fallback = storeWithFallback()

        val ex = assertThrows(IllegalStateException::class.java) {
            fallback.protectString("k", "PLAIN")
        }
        assertTrue("the refusal must be explicit", ex.message!!.contains("plaintext"))
        assertTrue("a refused write must set the tamper flag", fallback.consumeTamperFlag())
    }

    @Test
    fun keystoreUnavailable_protectedPutString_refusesToPersist_andLatchesTamperFlag() {
        val fallback = storeWithFallback()

        fallback.protectedPutString("k", "PLAIN")

        assertNull("a refused write must never persist the value", prefs.getString("k", null))
        assertTrue("a refused write must set the tamper flag", fallback.consumeTamperFlag())
    }

    @Test
    fun keystoreUnavailable_protectedPutInt_refusesToPersist_andLatchesTamperFlag() {
        val fallback = storeWithFallback()

        fallback.protectedPutInt("k", 7)

        assertNull("a refused write must never persist an int as plaintext", prefs.getString("k", null))
        assertTrue(fallback.consumeTamperFlag())
    }

    @Test
    fun keystoreUnavailable_protectedPutBoolean_refusesToPersist_andLatchesTamperFlag() {
        val fallback = storeWithFallback()

        fallback.protectedPutBoolean("k", true)

        assertNull("a refused write must never persist a boolean as plaintext", prefs.getString("k", null))
        assertTrue(fallback.consumeTamperFlag())
    }

    @Test
    fun keystoreUnavailable_protectedPut_keepsExistingValueUnchanged() {
        // A healthy value already written stays untouched when a later write is
        // refused: a fail-closed write never clobbers what is already protected.
        store.protectedPutString("k", "VALUE")
        val before = prefs.getString("k", null)

        storeWithFallback().protectedPutString("k", "NEW")

        assertEquals("the existing protected value must be untouched", before, prefs.getString("k", null))
    }

    // --- Fallback crypto acquisition: a transient failure must not permanently
    // poison writes for the rest of the process ---

    @Test
    fun transientCryptoFailure_recoversOnNextAccess() {
        // The keystore being briefly unavailable at the first write must not be
        // cached: the very next access re-attempts construction and succeeds.
        var calls = 0
        val factory = { if (calls++ == 0) null else JvmPrefsCrypto() }
        val flaky = ProtectedPrefsStore(prefs, null, factory)

        flaky.protectedPutString("k", "SECRET")
        assertNull("the first write must refuse while crypto is unavailable", prefs.getString("k", null))
        assertTrue("a refused write must set the tamper flag", flaky.consumeTamperFlag())

        flaky.protectedPutString("k", "SECRET")
        val stored = prefs.getString("k", null)
        assertTrue("once crypto recovers, the write must encrypt", stored != null && stored.startsWith("enc:"))
        assertFalse("a healthy write must not set the tamper flag", flaky.consumeTamperFlag())
    }

    @Test
    fun successfulCryptoConstruction_isCached() {
        // A successful construction is cached for the process: later reads and
        // writes must not reconstruct (each reconstruction is keystore binder work).
        var calls = 0
        val factory = { calls++; JvmPrefsCrypto() }
        val store = ProtectedPrefsStore(prefs, null, factory)

        store.protectedPutString("k", "VALUE")
        store.protectedGetString("k", "DEFAULT")

        assertEquals("a successful construction must be cached, not repeated", 1, calls)
    }

    @Test
    fun permanentlyFailingCrypto_isRetriedEveryAccess_notCached() {
        // The failure itself must not be cached: on a device whose keystore
        // recovers later, a subsequent access has a chance to construct. Each
        // access re-attempts (and keeps refusing) rather than permanently
        // freezing protected writes.
        var calls = 0
        val factory = { calls++; null }
        val store = ProtectedPrefsStore(prefs, null, factory)

        store.protectedPutString("k", "VALUE")
        store.protectedPutString("k", "VALUE2")

        assertEquals("each access must re-attempt rather than cache the failure", 2, calls)
        assertNull("every refused write must leave nothing persisted", prefs.getString("k", null))
        assertTrue("the refused writes must latch the tamper flag", store.consumeTamperFlag())
        assertFalse("the tamper flag is a latched boolean, not a counter", store.consumeTamperFlag())
    }

    @Test
    fun encryptFailure_protectString_throws_andLatchesTamperFlag() {
        val failing = ProtectedPrefsStore(MockSharedPreferences(), EncryptThrowingPrefsCrypto())

        assertThrows(IllegalStateException::class.java) {
            failing.protectString("k", "SECRET")
        }
        assertTrue(failing.consumeTamperFlag())
    }

    @Test
    fun hmacFailure_protectString_throws_andLatchesTamperFlag() {
        val failing = ProtectedPrefsStore(MockSharedPreferences(), HmacThrowingPrefsCrypto())

        assertThrows(IllegalStateException::class.java) {
            failing.protectString("k", "SECRET")
        }
        assertTrue(failing.consumeTamperFlag())
    }

    @Test
    fun encryptFailure_protectedPutString_refusesToPersist_andLatchesTamperFlag() {
        val prefsF = MockSharedPreferences()
        val failing = ProtectedPrefsStore(prefsF, EncryptThrowingPrefsCrypto())

        failing.protectedPutString("k", "SECRET")

        assertNull("an encrypt failure must never fall back to plaintext", prefsF.getString("k", null))
        assertTrue("an encrypt failure must set the tamper flag", failing.consumeTamperFlag())
    }

    @Test
    fun hmacFailure_protectedPutString_refusesToPersist_andLatchesTamperFlag() {
        val prefsF = MockSharedPreferences()
        val failing = ProtectedPrefsStore(prefsF, HmacThrowingPrefsCrypto())

        failing.protectedPutString("k", "SECRET")

        assertNull("an hmac failure must never fall back to plaintext", prefsF.getString("k", null))
        assertTrue("an hmac failure must set the tamper flag", failing.consumeTamperFlag())
    }

    @Test
    fun decryptFailure_unprotectString_failsClosed_andLatchesTamperFlag() {
        // A stored blob that throws while decrypting must fail closed to the
        // default and latch the tamper flag, never surface partial/plaintext.
        val healthy = ProtectedPrefsStore(MockSharedPreferences(), JvmPrefsCrypto())
        val blob = healthy.protectString("k", "SECRET")
        val failing = ProtectedPrefsStore(MockSharedPreferences(), EncryptThrowingPrefsCrypto())

        assertEquals("DEFAULT", failing.unprotectString("k", blob, "DEFAULT"))
        assertTrue(failing.consumeTamperFlag())
    }

    @Test
    fun keystoreUnavailable_encPrefixedValue_failsClosed() {
        // No crypto to verify the blob: an encrypted-looking value is unreadable
        // and must fail closed rather than surface a default silently.
        val fallback = storeWithFallback()
        prefs.edit().putString("k", "enc:AAAA:BBBB:CCCC").commit()

        assertEquals("DEFAULT", fallback.protectedGetString("k", "DEFAULT"))
        assertTrue("an unverifiable blob must set the tamper flag", fallback.consumeTamperFlag())
    }

    @Test
    fun plaintextValue_failsClosed_andLatchesTamperFlag() {
        // A plaintext value under a protected key carries no integrity or key
        // binding: it is indistinguishable from tampering and must fail closed,
        // never be read through as a trusted value.
        prefs.edit().putString("k", "PLAIN").commit()

        assertEquals("DEFAULT", store.protectedGetString("k", "DEFAULT"))
        assertTrue("a plaintext value must set the tamper flag", store.consumeTamperFlag())
    }

    // --- readProtectedValueOrNull (fail-closed primitive) ---

    @Test
    fun readProtectedValueOrNull_missingKey_returnsNull() {
        assertNull(store.readProtectedValueOrNull("missing"))
        assertFalse(store.consumeTamperFlag())
    }

    @Test
    fun readProtectedValueOrNull_healthyValue_returnsIt() {
        store.protectedPutString("k", "COUNTDOWN_WIPE")

        assertEquals("COUNTDOWN_WIPE", store.readProtectedValueOrNull("k"))
        assertFalse(store.consumeTamperFlag())
    }

    @Test
    fun readProtectedValueOrNull_tamperedValue_returnsNull_andLatchesFlag() {
        store.protectedPutString("k", "COUNTDOWN_WIPE")
        val stored = prefs.getString("k", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val mac = Base64.getDecoder().decode(parts[2]).apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        prefs.edit().putString("k", "enc:" + parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(mac)).commit()

        assertNull(store.readProtectedValueOrNull("k"))
        assertTrue(store.consumeTamperFlag())
    }

    @Test
    fun readProtectedValueOrNull_plaintextValue_returnsNull_andLatchesFlag() {
        prefs.edit().putString("k", "PLAIN").commit()

        assertNull("a plaintext value must fail closed to null", store.readProtectedValueOrNull("k"))
        assertTrue("a plaintext value must set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun readProtectedValueOrNull_legacyBlob_returnsNull_andLatchesFlag() {
        val (cipher, iv) = crypto.encrypt("COUNTDOWN_WIPE".toByteArray(Charsets.UTF_8), ByteArray(0))
        val legacy = "enc:" +
            Base64.getEncoder().encodeToString(iv) + ":" +
            Base64.getEncoder().encodeToString(cipher)
        prefs.edit().putString("k", legacy).commit()

        assertNull("a legacy blob must fail closed to null", store.readProtectedValueOrNull("k"))
        assertTrue("a legacy blob must set the tamper flag", store.consumeTamperFlag())
    }

    // --- Store → crypto AAD wiring (the store must bind the blob to its key name) ---

    @Test
    fun storeProtectedBlob_decryptsAtCryptoLayer_underItsOwnKeyNameAad() {
        // The store's protect path must feed the pref key name into GCM as AAD:
        // the stored blob has to decrypt at the crypto layer under that exact
        // key-name AAD, or the key binding never reached the cipher.
        val blob = store.protectString("config_is_enabled", "true")
        val parts = blob.removePrefix("enc:").split(":")
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])

        val decrypted = crypto.decrypt(
            ciphertext,
            iv,
            "config_is_enabled".toByteArray(Charsets.UTF_8)
        )

        assertArrayEquals("true".toByteArray(Charsets.UTF_8), decrypted)
    }

    @Test
    fun storeProtectedBlob_doesNotDecryptAtCryptoLayer_underAnotherKeyNameAad() {
        // And the blob must NOT decrypt under a foreign key name, proving the
        // cipher is actually bound to the owner key rather than to empty AAD.
        val blob = store.protectString("config_is_enabled", "true")
        val parts = blob.removePrefix("enc:").split(":")
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(
                ciphertext,
                iv,
                "config_dry_run_mode".toByteArray(Charsets.UTF_8)
            )
        }
    }

    @Test
    fun storeWrittenValue_roundTripsThroughCrypto_underItsOwnKeyNameAad() {
        // The full app-facing path: protectedPutString writes, protectedGetString
        // reads, and the intermediate blob is GCM-bound to the key name.
        store.protectedPutString("streak", "7")
        val stored = prefs.getString("streak", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])

        val decrypted = crypto.decrypt(ciphertext, iv, "streak".toByteArray(Charsets.UTF_8))

        assertArrayEquals("7".toByteArray(Charsets.UTF_8), decrypted)
        assertEquals("7", store.protectedGetString("streak", "0"))
        assertFalse("a healthy round-trip must not set the tamper flag", store.consumeTamperFlag())
    }

    @Test
    fun storeWrittenValue_failsToDecryptAtCryptoLayer_underAnotherKeyNameAad() {
        store.protectedPutString("streak", "7")
        val stored = prefs.getString("streak", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(ciphertext, iv, "security_state".toByteArray(Charsets.UTF_8))
        }
    }

    // --- Failing crypto doubles (encryption/decryption/HMAC throws) ---

    /**
     * A [PrefsCrypto] whose every operation throws, standing in for a keystore
     * that is present but broken mid-operation.
     */
    private class EncryptThrowingPrefsCrypto : PrefsCrypto {
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")

        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            throw IllegalStateException("decrypt failed")

        override fun hmac(data: ByteArray): ByteArray =
            throw IllegalStateException("hmac failed")
    }

    /**
     * A [PrefsCrypto] whose HMAC throws, so the write path fails after a
     * successful encryption — the "stamp the MAC" failure mode.
     */
    private class HmacThrowingPrefsCrypto : PrefsCrypto {
        private val delegate = JvmPrefsCrypto("hmac-throwing")

        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> = delegate.encrypt(data, aad)

        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray = delegate.decrypt(ciphertext, iv, aad)

        override fun hmac(data: ByteArray): ByteArray = throw IllegalStateException("hmac failed")
    }
}
