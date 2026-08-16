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

    private lateinit var prefs: MockSharedPreferences
    private lateinit var store: ProtectedPrefsStore
    private lateinit var configStore: AppConfigStore

    /** The removed setting's persisted key, as an older install would have stored it. */
    private val legacyKey = "config_user_unlock_resets"

    @Before
    fun setUp() {
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
