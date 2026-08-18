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
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ResponseTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence coverage for the config store after the "reset streak on unlock"
 * setting was removed: saving config must never (re)write the removed setting's
 * key, and must purge any value an older install left under that key, while every
 * remaining config field still round-trips exactly.
 */
class AppConfigStoreTest {

    private class MockSharedPreferences(private val commitSucceeds: Boolean = true) : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(map, commitSucceeds)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(
            private val map: MutableMap<String, Any?>,
            private val commitSucceeds: Boolean
        ) : android.content.SharedPreferences.Editor {
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
            override fun commit(): Boolean {
                if (!commitSucceeds) return false
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) map.clear()
                tempMap.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private lateinit var prefs: MockSharedPreferences
    private lateinit var store: ProtectedPrefsStore
    private lateinit var configStore: AppConfigStore

    /** The removed setting's persisted key, as an older install would have stored it. */
    private val legacyKey = "config_user_unlock_resets"

    @Before
    fun setUp() {
        ProtectedPrefsStore.consumeProcessTamperFlag()
        prefs = MockSharedPreferences()
        store = ProtectedPrefsStore(prefs, JvmPrefsCrypto())
        configStore = AppConfigStore(prefs, store)
    }

    @Test
    fun `saving config never writes the removed setting key`() {
        configStore.saveConfig(AppConfig())

        assertFalse(
            "the removed setting's key must not be recreated on save",
            prefs.contains(legacyKey)
        )
    }

    @Test
    fun `saving config writes exactly the active config keys`() {
        configStore.saveConfig(AppConfig())

        val activeKeys = setOf(
            "config_is_enabled",
            "config_wipe_threshold",
            "config_notif_per_breach",
            "config_notif_tail_mins",
            "config_grace_window_secs",
            "config_safety_net_mins",
            "config_clock_skew_mins",
            "config_include_frp",
            "config_aggressive_mode",
            "config_dry_run_mode",
            "config_self_tamper_tier",
            "config_device_protection_alarm",
            "config_block_debugging_features"
        )

        assertEquals("only the active config keys may be persisted", activeKeys, prefs.all.keys.toSet())
    }

    @Test
    fun `saving config purges a legacy protected value under the removed key`() {
        store.protectedPutBoolean(legacyKey, true)
        assertTrue("the legacy value must be present before the save", prefs.contains(legacyKey))

        configStore.saveConfig(AppConfig())

        assertFalse("saving config must purge the legacy value", prefs.contains(legacyKey))
    }

    @Test
    fun `saving config purges a legacy plaintext value under the removed key`() {
        // Pre-encryption installs stored this setting as a plain boolean.
        prefs.edit().putBoolean(legacyKey, true).commit()
        assertTrue("the legacy plaintext value must be present before the save", prefs.contains(legacyKey))

        configStore.saveConfig(AppConfig())

        assertFalse("saving config must purge the legacy plaintext value", prefs.contains(legacyKey))
    }

    @Test
    fun `saving config purges a corrupted legacy value without reading it`() {
        // A tampered/corrupt blob under the removed key must not block the purge:
        // removal never touches the value's contents.
        prefs.edit().putString(legacyKey, "enc:broken").commit()

        configStore.saveConfig(AppConfig())

        assertFalse("saving config must purge the corrupt legacy value", prefs.contains(legacyKey))
        assertFalse(
            "removing an orphaned key must not latch a tamper flag",
            store.consumeTamperFlag()
        )
    }

    @Test
    fun `a legacy value does not disturb reads before the next save`() {
        configStore.saveConfig(AppConfig(wipeThreshold = 5))
        store.protectedPutBoolean(legacyKey, true)

        val config = configStore.getConfig()

        assertEquals("the legacy key must not affect loaded config", 5, config.wipeThreshold)
        assertTrue("the legacy key must still be present until the next save", prefs.contains(legacyKey))
    }

    @Test
    fun `every active config field round-trips through a fresh store`() {
        val original = AppConfig(
            isEnabled = true,
            wipeThreshold = 7,
            notificationsPerBreach = 9,
            notificationTailMinutes = 2000,
            graceWindowSeconds = 42,
            safetyNetIntervalMinutes = 23,
            clockSkewToleranceMinutes = 11,
            selfTamperTier = ResponseTier.ALARM_STREAK,
            includeFRPData = true,
            aggressiveMode = true,
            deviceProtectionAlarmEnabled = true,
            blockDebuggingFeatures = false,
            dryRunMode = false
        )
        configStore.saveConfig(original)

        // A fresh store instance (empty cache) simulates a process restart and
        // forces the config to be rebuilt from the persisted values.
        val reloaded = AppConfigStore(prefs, store).getConfig()

        assertEquals(original.isEnabled, reloaded.isEnabled)
        assertEquals(original.wipeThreshold, reloaded.wipeThreshold)
        assertEquals(original.notificationsPerBreach, reloaded.notificationsPerBreach)
        assertEquals(original.notificationTailMinutes, reloaded.notificationTailMinutes)
        assertEquals(original.graceWindowSeconds, reloaded.graceWindowSeconds)
        assertEquals(original.safetyNetIntervalMinutes, reloaded.safetyNetIntervalMinutes)
        assertEquals(original.clockSkewToleranceMinutes, reloaded.clockSkewToleranceMinutes)
        assertEquals(original.selfTamperTier, reloaded.selfTamperTier)
        assertEquals(original.includeFRPData, reloaded.includeFRPData)
        assertEquals(original.aggressiveMode, reloaded.aggressiveMode)
        assertEquals(original.deviceProtectionAlarmEnabled, reloaded.deviceProtectionAlarmEnabled)
        assertEquals(original.blockDebuggingFeatures, reloaded.blockDebuggingFeatures)
        assertEquals(original.dryRunMode, reloaded.dryRunMode)
    }

    @Test
    fun `the aggressive preset saves without the removed setting key`() {
        configStore.saveConfig(AppConfig.aggressivePreset())

        val reloaded = AppConfigStore(prefs, store).getConfig()
        assertEquals(1, reloaded.wipeThreshold)
        assertEquals(1, reloaded.safetyNetIntervalMinutes)
        assertTrue(reloaded.aggressiveMode)
        assertTrue("the preset leaves dry-run simulation untouched", reloaded.dryRunMode)
        assertFalse(
            "the aggressive preset must not write the removed setting's key",
            prefs.contains(legacyKey)
        )
    }

    @Test
    fun `the cache fingerprint ignores the removed key`() {
        configStore.saveConfig(AppConfig(wipeThreshold = 5))
        val before = configStore.getConfig()

        // Re-introducing the legacy key must not invalidate the cached config,
        // because the fingerprint is built only from the active config keys.
        store.protectedPutBoolean(legacyKey, true)
        val after = configStore.getConfig()

        assertEquals(before, after)
        assertEquals(5, after.wipeThreshold)
    }

    // --- Cache correctness under write failures ---

    @Test
    fun `saveConfig does not cache when keystore is unavailable`() {
        // A standalone store with no crypto: every write is refused, so the cache
        // must remain empty and getConfig() must rebuild from disk (returning
        // defaults when nothing is persisted).
        val noCryptoStore = ProtectedPrefsStore(prefs, cryptoFactory = { null })
        val failingConfigStore = AppConfigStore(prefs, noCryptoStore)

        failingConfigStore.saveConfig(AppConfig(wipeThreshold = 42))

        val config = failingConfigStore.getConfig()
        assertEquals(
            "a failed save must not cache the requested config",
            AppConfig().wipeThreshold,
            config.wipeThreshold
        )
    }

    @Test
    fun `saveConfig does not cache when encrypt throws mid-save`() {
        // Crypto that fails on the third write. The config is persisted as one
        // atomic batch, so a failure on any field refuses the whole batch: even
        // the fields protected before the failure must not land on disk, and the
        // cache must not be primed. getConfig() returns defaults.
        var writes = 0
        val partialCrypto = object : PrefsCrypto {
            private val delegate = JvmPrefsCrypto()
            override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
                writes++
                if (writes == 3) throw IllegalStateException("injected failure")
                return delegate.encrypt(data, aad)
            }
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
                delegate.decrypt(ciphertext, iv, aad)
            override fun hmac(data: ByteArray): ByteArray = delegate.hmac(data)
        }
        val partialPrefs = MockSharedPreferences()
        val partialStore = ProtectedPrefsStore(partialPrefs, partialCrypto)
        val partialConfigStore = AppConfigStore(partialPrefs, partialStore)

        partialConfigStore.saveConfig(AppConfig(wipeThreshold = 99, notificationsPerBreach = 77))

        val config = partialConfigStore.getConfig()
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
        assertTrue("the refused batch must latch the tamper flag", partialStore.consumeTamperFlag())
    }

    @Test
    fun `saveConfig updates cache only after all writes succeed`() {
        configStore.saveConfig(AppConfig(wipeThreshold = 13))

        val config = configStore.getConfig()
        assertEquals(13, config.wipeThreshold)
    }

    @Test
    fun `after a failed save getConfig reads from disk not cache`() {
        // First save succeeds with a working store (same crypto seed, so both
        // stores share keys). Then a second save fails on every write. The second
        // save must NOT persist anything and must NOT prime the cache: getConfig()
        // returns the value the first save durably persisted (13), never the
        // refused request (99).
        val healthyCrypto = JvmPrefsCrypto()
        val writeThrowingCrypto = object : PrefsCrypto {
            override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
                throw IllegalStateException("injected failure")
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
                healthyCrypto.decrypt(ciphertext, iv, aad)
            override fun hmac(data: ByteArray): ByteArray = healthyCrypto.hmac(data)
        }
        val workingStore = ProtectedPrefsStore(prefs, healthyCrypto)
        val workingConfigStore = AppConfigStore(prefs, workingStore)
        workingConfigStore.saveConfig(AppConfig(wipeThreshold = 13))

        val failingStore = ProtectedPrefsStore(prefs, writeThrowingCrypto)
        val failingConfigStore = AppConfigStore(prefs, failingStore)
        failingConfigStore.saveConfig(AppConfig(wipeThreshold = 99))

        val config = failingConfigStore.getConfig()
        assertEquals(
            "a failed save must leave the previously persisted value readable, not the refused one",
            13,
            config.wipeThreshold
        )
        assertTrue("the refused writes must latch the tamper flag", failingStore.consumeTamperFlag())
    }

    @Test
    fun `saveConfig does not cache when the disk commit fails`() {
        // Encryption succeeds for every field, but the prefs commit() reports a
        // disk-write failure. The save must not be treated as persisted and the
        // cache must not be primed: getConfig() returns defaults, never the
        // requested value that only ever existed in memory.
        val failingPrefs = MockSharedPreferences(commitSucceeds = false)
        val failingStore = ProtectedPrefsStore(failingPrefs, JvmPrefsCrypto())
        val failingConfigStore = AppConfigStore(failingPrefs, failingStore)

        failingConfigStore.saveConfig(AppConfig(wipeThreshold = 99))

        val config = failingConfigStore.getConfig()
        assertEquals(
            "a failed disk commit must not be cached",
            AppConfig().wipeThreshold,
            config.wipeThreshold
        )
        assertTrue("a failed disk commit must latch the tamper flag", failingStore.consumeTamperFlag())
    }

    @Test
    fun `saveConfig does not cache when the write does not read back`() {
        // Crypto that encrypts successfully (so the batch commits) but whose
        // blobs cannot be read back as the requested values. The commit alone
        // "succeeds", but the write-and-read verification must refuse to prime
        // the cache: getConfig() returns the actual on-disk (corrupt) values,
        // never the requested ones.
        val encryptWrongCrypto = object : PrefsCrypto {
            private val delegate = JvmPrefsCrypto()
            override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
                delegate.encrypt("WRONG".toByteArray(Charsets.UTF_8), aad)
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
                delegate.decrypt(ciphertext, iv, aad)
            override fun hmac(data: ByteArray): ByteArray = delegate.hmac(data)
        }
        val prefsF = MockSharedPreferences()
        val corruptStore = ProtectedPrefsStore(prefsF, encryptWrongCrypto)
        val corruptConfigStore = AppConfigStore(prefsF, corruptStore)

        // The requested config is never readable back; each field reads as the
        // wrong value ("WRONG" → default), so the verification fails.
        corruptConfigStore.saveConfig(AppConfig(wipeThreshold = 99))

        val config = corruptConfigStore.getConfig()
        assertEquals(
            "a write that does not read back must not be cached",
            AppConfig().wipeThreshold,
            config.wipeThreshold
        )
    }

    @Test
    fun `AppConfig exposes exactly the active config fields`() {
        // Pins the full config surface so a dead setting can never silently
        // reappear as a bare field: any property added to (or removed from) the
        // config model without a deliberate test update fails here.
        val expected = setOf(
            "isEnabled",
            "wipeThreshold",
            "notificationsPerBreach",
            "notificationTailMinutes",
            "graceWindowSeconds",
            "safetyNetIntervalMinutes",
            "clockSkewToleranceMinutes",
            "selfTamperTier",
            "includeFRPData",
            "aggressiveMode",
            "deviceProtectionAlarmEnabled",
            "blockDebuggingFeatures",
            "dryRunMode"
        )
        val actual = AppConfig::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            "AppConfig must contain exactly the active settings and no dead ones",
            expected,
            actual
        )
    }
}
