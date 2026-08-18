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
import com.airgate.data.crypto.PrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.domain.model.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the config store's cache gate holds against the
 * real Android Keystore: a fully successful save is readable by a fresh
 * repository (process-restart simulation) and never latches the tamper flag,
 * while a write that fails mid-save must NOT prime the cache — the field whose
 * write failed comes back as its default, never the requested value.
 */
@RunWith(AndroidJUnit4::class)
class AppConfigStoreInstrumentedTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetProcessTamperFlag() {
        // The tamper flag is process-wide; instrumented tests share the app
        // process, so each test starts from a clean latch.
        ProtectedPrefsStore.consumeProcessTamperFlag()
    }

    @Test
    fun healthySave_isReadableByAnotherInstance_andNoTamperFlag() {
        val prefs = newPrefs()
        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(AppConfig(wipeThreshold = 21))

        val reloaded = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })

        assertEquals(21, reloaded.getConfig().wipeThreshold)
        assertFalse("a healthy save must not latch the tamper flag", reloaded.consumeStateTamperFlag())
    }

    @Test
    fun failedSave_doesNotCacheRequestedConfig() {
        val prefs = newPrefs()
        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(AppConfig(wipeThreshold = 13))
        assertEquals(13, repository.getConfig().wipeThreshold)

        // A second repository whose writes always fail but whose reads delegate
        // to the real keystore (same aliases), so a cache miss falls through to
        // the persisted value.
        val failingRepository = SecurityStateRepository(
            prefs,
            EncryptThrowingButReadableCrypto(),
            notificationsAllowedProvider = { true }
        )

        failingRepository.saveConfig(AppConfig(wipeThreshold = 99))

        assertEquals(
            "a failed save must not surface the refused value",
            13,
            failingRepository.getConfig().wipeThreshold
        )
        assertTrue("a refused write must latch the tamper flag", failingRepository.consumeStateTamperFlag())
    }

    @Test
    fun partialFailureSave_refusesWholeBatch_andDoesNotCache() {
        // Crypto that fails on the third write. The config is persisted as one
        // atomic batch, so a failure on any field refuses the whole batch: even
        // the fields protected before the failure must not land on disk, and the
        // cache must not be primed.
        var writes = 0
        val delegate = KeystoreManager()
        val partialCrypto = object : PrefsCrypto {
            override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
                writes++
                if (writes == 3) throw IllegalStateException("injected failure")
                return delegate.encrypt(data, aad)
            }
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
                delegate.decrypt(ciphertext, iv, aad)
            override fun hmac(data: ByteArray): ByteArray = delegate.hmac(data)
        }
        val prefs = newPrefs()
        val repository = SecurityStateRepository(prefs, partialCrypto, notificationsAllowedProvider = { true })

        // isEnabled stays false, so the arming guards do not apply and no PIN is
        // needed — the encrypt counter counts only the config writes.
        repository.saveConfig(AppConfig(wipeThreshold = 99, notificationsPerBreach = 77))

        val config = repository.getConfig()
        assertEquals(
            "the field whose write failed must come back as its default, never the cached request",
            AppConfig().notificationsPerBreach,
            config.notificationsPerBreach
        )
        assertEquals(
            "a field protected before the failure must not persist either — the batch is atomic",
            AppConfig().wipeThreshold,
            config.wipeThreshold
        )
        assertTrue("the refused batch must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun writeThatDoesNotReadBack_isNotCached() {
        // Crypto that encrypts successfully (the batch commits) but stores a
        // wrong plaintext. The commit alone "succeeds", but the write-and-read
        // verification must refuse to prime the cache: getConfig() returns the
        // actual on-disk (corrupt) values, never the requested ones.
        val delegate = KeystoreManager()
        val encryptWrongCrypto = object : PrefsCrypto {
            override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
                delegate.encrypt("WRONG".toByteArray(Charsets.UTF_8), aad)
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
                delegate.decrypt(ciphertext, iv, aad)
            override fun hmac(data: ByteArray): ByteArray = delegate.hmac(data)
        }
        val prefs = newPrefs()
        val repository = SecurityStateRepository(prefs, encryptWrongCrypto, notificationsAllowedProvider = { true })

        repository.saveConfig(AppConfig(wipeThreshold = 99))

        val config = repository.getConfig()
        assertEquals(
            "a write that does not read back must not be cached",
            AppConfig().wipeThreshold,
            config.wipeThreshold
        )
    }

    private fun newPrefs(): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(
            "app_config_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }

    /**
     * A [PrefsCrypto] whose reads delegate to the real keystore-backed manager
     * (so existing blobs decrypt) but whose encrypt always throws.
     */
    private class EncryptThrowingButReadableCrypto : PrefsCrypto {
        private val delegate = KeystoreManager()
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            delegate.decrypt(ciphertext, iv, aad)
        override fun hmac(data: ByteArray): ByteArray = delegate.hmac(data)
    }
}
