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
import com.airgate.data.crypto.KeystoreManager
import com.airgate.data.crypto.PinManager
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.domain.model.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Rule

/**
 * JVM verification (Robolectric) of the protected-prefs keyed-MAC and the fail-closed
 * security state, against the simulated Android Keystore.
 *
 *  - a value written through the store round-trips (encrypt + keyed-MAC);
 *  - modifying any part of a stored blob — ciphertext, MAC, or IV — fails the
 *    read and latches the tamper flag;
 *  - a corrupted security state reads as an alarm, never "compliant";
 *  - a corrupted enabled flag reads as disabled while still latching the tamper
 *    flag that the periodic audit consumes.
 *
 * All tests use a throwaway SharedPreferences store so no real app state is
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class ProtectedPrefsStoreStorageTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun throwawayRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "protected_prefs_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return SecurityStateRepository(prefs)
    }

    private fun throwawayPrefs(): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(
            "protected_prefs_raw_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }

    /**
     * A repository and its backing prefs sharing one throwaway file, so a test
     * can corrupt a stored value by writing the raw prefs directly.
     */
    private fun repositoryAndPrefs(): Pair<SecurityStateRepository, android.content.SharedPreferences> {
        val prefs = throwawayPrefs()
        return SecurityStateRepository(prefs) to prefs
    }

    private fun flipFirstByte(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    // --- Round-trip through the simulated Android Keystore ---

    @Test
    fun protectedValue_roundTripsThroughTheAndroidKeystore() {
        val repository = throwawayRepository()

        repository.setStreak(7)
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        assertEquals(7, repository.getStreak())
        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertFalse("a healthy round-trip must not set the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun freshInstall_stateIsCompliant_andNoTamperFlag() {
        val repository = throwawayRepository()

        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun storedBlob_carriesTheKeyedMac() {
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)

        val stored = prefs.getString("security_state", null)
        assertTrue("a real protected value must be encrypted", stored != null && stored.startsWith("enc:"))
        val parts = stored!!.removePrefix("enc:").split(":")
        assertEquals("stored value must be enc:<iv>:<cipher>:<mac>", 3, parts.size)
        parts.forEach { Base64.getDecoder().decode(it) }
    }

    // --- Tamper detection on the JVM ---

    @Test
    fun tamperedCiphertext_failsClosed_onDevice() {
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")

        prefs.edit()
            .putString("security_state", "enc:" + parts[0] + ":" + flipFirstByte(parts[1]) + ":" + parts[2])
            .commit()

        assertEquals(
            "a modified compliant state must surface an alarm, never compliant",
            SecurityState.ALARM_ACTIVE,
            repository.getSecurityState()
        )
        assertTrue("ciphertext tampering must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun tamperedMac_failsClosed_onDevice() {
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")

        prefs.edit()
            .putString("security_state", "enc:" + parts[0] + ":" + parts[1] + ":" + flipFirstByte(parts[2]))
            .commit()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("a MAC mismatch must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun tamperedIv_failsClosed_onDevice() {
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")

        prefs.edit()
            .putString("security_state", "enc:" + flipFirstByte(parts[0]) + ":" + parts[1] + ":" + parts[2])
            .commit()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("a swapped IV must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun malformedProtectedBlob_failsClosed_onDevice() {
        val (repository, prefs) = repositoryAndPrefs()

        prefs.edit().putString("security_state", "enc:broken").commit()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue(repository.consumeStateTamperFlag())
    }

    @Test
    fun corruptEnabledFlag_readsDisabled_andLatchesTamper_onDevice() {
        // The audit-ordering scenario: config_is_enabled is corrupted, so the
        // app reads it as disabled (its decrypt default) — and that read itself
        // must latch the tamper flag the audit then escalates.
        val (repository, prefs) = repositoryAndPrefs()
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(
            com.airgate.domain.model.AppConfig(isEnabled = true, dryRunMode = true)
        )
        assertTrue(repository.getConfig().isEnabled)

        prefs.edit().putString("config_is_enabled", "enc:broken").commit()

        assertFalse("a corrupted enabled flag must read as disabled", repository.getConfig().isEnabled)
        assertTrue("the failed config read must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun tamperFlag_isSingleShot_onDevice() {
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        prefs.edit()
            .putString("security_state", "enc:" + parts[0] + ":" + flipFirstByte(parts[1]) + ":" + parts[2])
            .commit()
        repository.getSecurityState()

        assertTrue(repository.consumeStateTamperFlag())
        assertFalse("the flag must be cleared by consumption", repository.consumeStateTamperFlag())
    }

    @Test
    fun swappedValuesBetweenKeys_isDetected_onDevice() {
        // The ciphertext swap: each blob decrypts fine under its original key,
        // but the keyed-MAC binds each value to its pref key, so a value moved
        // into another key must be rejected as tampering.
        val (repository, prefs) = repositoryAndPrefs()
        repository.setStreak(5)
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val streakBlob = prefs.getString("streak", null)!!
        val stateBlob = prefs.getString("security_state", null)!!

        prefs.edit()
            .putString("streak", stateBlob)
            .putString("security_state", streakBlob)
            .commit()

        assertEquals("a swapped streak must fail closed to 0", 0, repository.getStreak())
        assertTrue("a swapped value must set the tamper flag", repository.consumeStateTamperFlag())
        assertEquals(
            "a swapped state must fail closed to an alarm, never compliant",
            SecurityState.ALARM_ACTIVE,
            repository.getSecurityState()
        )
        assertTrue(repository.consumeStateTamperFlag())
    }

    // --- AAD (associated-data) key binding on the JVM ---

    @Test
    fun aadMismatch_betweenKeyNames_failsDecrypt_onDevice() {
        // The crypto-layer binding: a ciphertext encrypted under one key name
        // must not decrypt under another, even before the keyed-MAC is consulted.
        // This is the ciphertext-swap guarantee enforced by the real keystore.
        val crypto = KeystoreManager()
        val aadA = "config_is_enabled".toByteArray(Charsets.UTF_8)
        val aadB = "config_dry_run_mode".toByteArray(Charsets.UTF_8)
        val (ciphertext, iv) = crypto.encrypt("true".toByteArray(Charsets.UTF_8), aadA)

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(ciphertext, iv, aadB)
        }
    }

    @Test
    fun aadMismatch_emptyVersusKeyName_failsDecrypt_onDevice() {
        // A blob bound to no key name (legacy-style encryption) must not decrypt
        // under the key-name AAD the store always supplies.
        val crypto = KeystoreManager()
        val (ciphertext, iv) = crypto.encrypt(
            SecurityState.ARMED_COMPLIANT.name.toByteArray(Charsets.UTF_8),
            ByteArray(0)
        )

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(ciphertext, iv, "security_state".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun aadBoundBlob_roundTrips_underSameKeyName_onDevice() {
        val crypto = KeystoreManager()
        val aad = "security_state".toByteArray(Charsets.UTF_8)
        val (ciphertext, iv) = crypto.encrypt(
            SecurityState.ARMED_COMPLIANT.name.toByteArray(Charsets.UTF_8),
            aad
        )

        assertEquals(
            SecurityState.ARMED_COMPLIANT.name,
            String(crypto.decrypt(ciphertext, iv, aad), Charsets.UTF_8)
        )
    }

    @Test
    fun legacyTwoPartBlob_failsClosed_onDevice() {
        // A pre-keyed-MAC / pre-AAD build stored enc:<iv>:<cipher>. That form
        // carries no key binding, so it must fail closed like any tamper.
        val (repository, prefs) = repositoryAndPrefs()
        val crypto = KeystoreManager()
        val (ciphertext, iv) = crypto.encrypt(
            SecurityState.ARMED_COMPLIANT.name.toByteArray(Charsets.UTF_8),
            ByteArray(0)
        )
        prefs.edit()
            .putString(
                "security_state",
                "enc:" + Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(ciphertext)
            )
            .commit()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("a legacy blob must set the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun macStrippedFromProtectedBlob_failsClosed_onDevice() {
        // Stripping the keyed-MAC segment leaves enc:<iv>:<cipher>, the legacy
        // form with no key binding. The downgrade must fail closed, not decrypt.
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        prefs.edit()
            .putString("security_state", "enc:" + parts[0] + ":" + parts[1])
            .commit()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("a MAC-stripped blob must set the tamper flag", repository.consumeStateTamperFlag())
    }

    // --- Store → crypto AAD wiring on the JVM (the blob must be GCM-bound to its key name) ---

    @Test
    fun storeWrittenBlob_decryptsUnderItsOwnKeyNameAad_onDevice() {
        // The store's protect path must feed the pref key name into GCM as AAD:
        // a real stored blob has to decrypt at the crypto layer under that exact
        // key-name AAD, or the key binding never reached the keystore cipher.
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val crypto = KeystoreManager()

        val decrypted = crypto.decrypt(
            Base64.getDecoder().decode(parts[1]),
            Base64.getDecoder().decode(parts[0]),
            "security_state".toByteArray(Charsets.UTF_8)
        )

        assertEquals(SecurityState.ALARM_ACTIVE.name, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun storeWrittenBlob_doesNotDecryptUnderAnotherKeyNameAad_onDevice() {
        // And the blob must NOT decrypt under a foreign key name, proving the
        // keystore cipher is bound to the owner key, not to empty AAD.
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val crypto = KeystoreManager()

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(
                Base64.getDecoder().decode(parts[1]),
                Base64.getDecoder().decode(parts[0]),
                "streak".toByteArray(Charsets.UTF_8)
            )
        }
    }

    // --- Plaintext never trusted, on the JVM ---

    @Test
    fun plaintextSecurityState_failsClosed_onDevice() {
        // A plaintext value under a protected key carries no integrity binding:
        // it is indistinguishable from tampering and must fail closed to an
        // alarm, never be honored as a real state.
        val prefs = throwawayPrefs()
        prefs.edit().putString("security_state", "ARMED_COMPLIANT").commit()
        val repository = SecurityStateRepository(prefs)

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("a plaintext state must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun plaintextEnabledConfig_readsDefault_andLatchesTamper_onDevice() {
        // A plaintext "enabled" flag is equally untrustworthy: the app must read
        // the default (disabled) and surface the tamper rather than trust it.
        val prefs = throwawayPrefs()
        prefs.edit().putString("config_is_enabled", "true").commit()
        val repository = SecurityStateRepository(prefs)

        assertFalse("a plaintext enabled flag must fail closed to disabled", repository.getConfig().isEnabled)
        assertTrue("a plaintext config value must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun plaintextPinHash_failsClosed_toUnusablePin_onDevice() {
        // The PIN hash/salt are the crown jewels of the store: a plaintext PIN
        // hash must be treated as tampering, so the PIN is unusable and the
        // device cannot be armed with it.
        val prefs = throwawayPrefs()
        prefs.edit()
            .putString("pin_hash", "cGxhaW50ZXh0LWhhc2g=")
            .putString("pin_salt", "c2FsdA==")
            .commit()
        val repository = SecurityStateRepository(prefs)

        assertTrue(repository.isPinSet())
        assertFalse("a plaintext PIN must not be usable", repository.isPinUsable())
        assertTrue(repository.consumeStateTamperFlag())
    }

    // --- Write failures refuse plaintext persistence, on the JVM ---

    @Test
    fun writeWithFailingCrypto_refusesPlaintextState_onDevice() {
        // When encryption throws, the store must refuse to persist rather than
        // degrade to plaintext: nothing is written and the tamper flag is set.
        val prefs = throwawayPrefs()
        val repository = SecurityStateRepository(prefs, ThrowingPrefsCrypto())

        repository.setSecurityState(SecurityState.WIPING)

        assertNull("a failed write must never persist plaintext", prefs.getString("security_state", null))
        assertTrue("a failed write must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun writeWithFailingCrypto_refusesPlaintextPin_onDevice() {
        val prefs = throwawayPrefs()
        val repository = SecurityStateRepository(prefs, ThrowingPrefsCrypto())

        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertNull("a failed PIN write must never persist the hash in plaintext", prefs.getString("pin_hash", null))
        assertNull("a failed PIN write must never persist the salt in plaintext", prefs.getString("pin_salt", null))
        assertTrue("a failed PIN write must latch the tamper flag", repository.consumeStateTamperFlag())
        assertFalse("with nothing persisted the PIN cannot be usable", repository.isPinUsable())
    }

    @Test
    fun failedWrite_leavesPriorEncryptedValueUntouched_onDevice() {
        // The refused-write contract on the JVM: a working keystore writes
        // an encrypted state, then a broken crypto refuses to overwrite it — the
        // prior encrypted value survives, nothing is persisted in plaintext, and
        // a fresh repository reads the prior value, not the refused one.
        val (repository, prefs) = repositoryAndPrefs()
        repository.setSecurityState(SecurityState.ALARM_ACTIVE)
        val before = prefs.getString("security_state", null)!!
        assertTrue(before.startsWith("enc:"))

        val broken = SecurityStateRepository(prefs, ThrowingPrefsCrypto())
        broken.setSecurityState(SecurityState.WIPING)

        assertEquals("the prior encrypted value must survive the refused write", before, prefs.getString("security_state", null))
        assertTrue("the refused write must latch the tamper flag", broken.consumeStateTamperFlag())

        val reloaded = SecurityStateRepository(prefs)
        assertEquals(
            "a fresh repository must read the persisted value, not the refused one",
            SecurityState.ALARM_ACTIVE,
            reloaded.getSecurityState()
        )
        assertFalse(reloaded.consumeStateTamperFlag())
    }

    // --- Key loss / regeneration recovery, on the JVM ---

    @Test
    fun keystoreKeyLoss_recoversByRegeneration_onDevice() {
        // The recovery contract: when keystore keys are lost, a fresh manager
        // over the same aliases regenerates them so protected writes keep
        // working, and values encrypted under the lost key fail closed.
        val masterAlias = "ag_test_master_${System.currentTimeMillis()}"
        val hmacAlias = "ag_test_hmac_${System.currentTimeMillis()}"
        val aad = "k".toByteArray(Charsets.UTF_8)

        val crypto = KeystoreManager(masterAlias, hmacAlias)
        val (ciphertext, iv) = crypto.encrypt("SECRET".toByteArray(Charsets.UTF_8), aad)

        // Lose the keys (standing in for corruption: the alias exists but the key
        // material is unusable).
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(masterAlias)
        ks.deleteEntry(hmacAlias)

        // A fresh manager self-heals: it regenerates the keys.
        val recovered = KeystoreManager(masterAlias, hmacAlias)
        val (ciphertext2, iv2) = recovered.encrypt("AGAIN".toByteArray(Charsets.UTF_8), aad)
        assertEquals("AGAIN", String(recovered.decrypt(ciphertext2, iv2, aad), Charsets.UTF_8))

        // The old ciphertext under the lost key is unrecoverable: it fails at the
        // crypto layer, which the store turns into a tamper, never a plaintext read.
        assertThrows(GeneralSecurityException::class.java) {
            recovered.decrypt(ciphertext, iv, aad)
        }
    }

    @Test
    fun blobWrittenUnderOneKey_failsClosed_underAnotherKey_onDevice() {
        // The re-provision recovery flow at the repository level: a value written
        // under one key set is unreadable after the keys are replaced (the
        // equivalent of a fresh install after key loss), so it fails closed.
        val prefs = throwawayPrefs()
        val writer = SecurityStateRepository(
            prefs,
            KeystoreManager("ag_testA_master_${System.currentTimeMillis()}", "ag_testA_hmac_${System.currentTimeMillis()}")
        )
        writer.setSecurityState(SecurityState.ARMED_COMPLIANT)
        assertTrue(prefs.getString("security_state", null)!!.startsWith("enc:"))

        val reader = SecurityStateRepository(
            prefs,
            KeystoreManager("ag_testB_master_${System.currentTimeMillis()}", "ag_testB_hmac_${System.currentTimeMillis()}")
        )

        assertEquals(SecurityState.ALARM_ACTIVE, reader.getSecurityState())
        assertTrue("a foreign-key blob must latch the tamper flag", reader.consumeStateTamperFlag())
    }

    /**
     * A [PrefsCrypto] whose every operation throws, standing in for a keystore
     * that is present but broken mid-operation.
     */
    private class ThrowingPrefsCrypto : PrefsCrypto {
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")

        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            throw IllegalStateException("decrypt failed")

        override fun hmac(data: ByteArray): ByteArray =
            throw IllegalStateException("hmac failed")
    }
}
