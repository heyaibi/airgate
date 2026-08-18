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
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.PendingAlarm
import com.airgate.domain.model.ScoringGroup
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStateRepositoryTest {
    // InMemorySharedPreferences helper for pure JVM test without Android runtime framework
    private class InMemorySharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? {
            // Mirror real SharedPreferencesImpl: reading a non-string value as a
            // string throws ClassCastException rather than returning the default.
            val v = map[key] ?: return defValue
            return v as? String ?: throw ClassCastException("$v cannot be cast to String")
        }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
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

    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var repository: SecurityStateRepository

    @org.junit.Before
    fun setUp() {
        ProtectedPrefsStore.consumeProcessTamperFlag()
        prefs = InMemorySharedPreferences()
        repository = SecurityStateRepository(prefs, JvmPrefsCrypto()) { 0L }
    }

    @Test
    fun `default values are correct`() {
        assertFalse(repository.isPinSet())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertEquals(0, repository.getStreak())
        
        val config = repository.getConfig()
        assertEquals(3, config.wipeThreshold)
        assertTrue(config.dryRunMode)
    }

    @Test
    fun `save and verify pin metadata`() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val salt = byteArrayOf(5, 6, 7, 8)
        repository.savePin(hash, salt, PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertTrue(repository.isPinSet())
        val pinData = repository.getPinData()
        assertEquals(hash.toList(), pinData?.hash?.toList())
        assertEquals(salt.toList(), pinData?.salt?.toList())
    }

    @Test
    fun `streak accumulation and reset`() {
        repository.setStreak(1)
        assertEquals(1, repository.getStreak())

        val newStreak = repository.incrementStreak(2)
        assertEquals(3, newStreak)
        assertEquals(3, repository.getStreak())

        repository.resetStreak()
        assertEquals(0, repository.getStreak())
    }

    @Test
    fun `save and load config update`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val updatedConfig = AppConfig.aggressivePreset()
        repository.saveConfig(updatedConfig)

        val config = repository.getConfig()
        assertEquals(1, config.wipeThreshold)
        assertEquals(1, config.safetyNetIntervalMinutes)
        // The paranoid preset never touches dry-run simulation, so it keeps the default.
        assertTrue(config.dryRunMode)
        assertTrue(config.aggressiveMode)
    }

    @Test
    fun `aggressive preset save leaves no removed-setting key persisted`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(AppConfig.aggressivePreset())

        assertFalse(
            "saving the preset must not write the removed setting's key",
            prefs.all.containsKey("config_user_unlock_resets")
        )
    }

    @Test
    fun `saving config purges a legacy removed-setting value`() {
        // An older install stored the removed setting as a protected value; a
        // standalone store on the same prefs stands in for that write.
        val legacyStore = ProtectedPrefsStore(prefs, JvmPrefsCrypto())
        legacyStore.protectedPutBoolean("config_user_unlock_resets", true)
        assertTrue(prefs.contains("config_user_unlock_resets"))

        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(AppConfig(wipeThreshold = 5))

        assertFalse(
            "saving config must purge the legacy value from an older install",
            prefs.contains("config_user_unlock_resets")
        )
        assertEquals(5, repository.getConfig().wipeThreshold)
    }

    @Test
    fun `watchdog cannot be enabled without a configured PIN`() {
        assertFalse(repository.isPinSet())
        assertFalse(repository.isPinUsable())

        val requested = repository.saveConfig(AppConfig(isEnabled = true))
        // The enable request is coerced back to disabled.
        assertFalse(requested.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    @Test
    fun `watchdog can be enabled once a PIN is configured`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        val requested = repository.saveConfig(AppConfig(isEnabled = true))
        assertTrue(requested.isEnabled)
        assertTrue(repository.getConfig().isEnabled)
    }

    @Test
    fun `isPinUsable is false when no PIN is configured`() {
        assertFalse(repository.isPinSet())
        assertFalse(repository.isPinUsable())
    }

    @Test
    fun `isPinUsable is true when PIN material is readable`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertTrue(repository.isPinSet())
        assertTrue(repository.isPinUsable())
    }

    @Test
    fun `isPinUsable is false when PIN material cannot be decoded`() {
        // PIN keys exist (so the PIN is "configured") but the protected blobs are
        // undecodable/undecryptable — the tamper/corruption case.
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())
    }

    @Test
    fun `isPinUsable is false when PIN material is plaintext`() {
        // A plaintext (non-"enc:") value under a protected key carries no
        // integrity binding: it is treated as tampering and fails closed, so the
        // PIN material is unusable.
        prefs.edit()
            .putString("pin_hash", "not-valid-base64!!")
            .putString("pin_salt", "also-not-valid!!")
            .apply()

        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())
    }

    @Test
    fun `watchdog cannot be enabled when the configured PIN is unreadable`() {
        // The key regression guard: arming must require a *usable* PIN, not merely
        // a present one. A present-but-unreadable PIN would otherwise let the owner
        // arm a device they could never disarm.
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        assertTrue(repository.isPinSet())
        assertFalse(repository.isPinUsable())

        val requested = repository.saveConfig(AppConfig(isEnabled = true))
        assertFalse(requested.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    @Test
    fun `an already-armed device with an unreadable PIN is disarmed on a later config save`() {
        // The PIN gate is always-on, not transition-only: if the PIN material
        // becomes unreadable after arming (tamper/corruption), a later config save
        // must coerce the device back to disabled so the owner is never left
        // armed-but-unable-to-disarm.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        assertTrue(repository.saveConfig(AppConfig(isEnabled = true)).isEnabled)
        assertTrue(repository.getConfig().isEnabled)

        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()
        assertFalse(repository.isPinUsable())

        // Toggling an unrelated setting with isEnabled still true must disarm.
        val updated = repository.saveConfig(AppConfig(isEnabled = true, wipeThreshold = 5))
        assertFalse(updated.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    @Test
    fun `disabling the watchdog never requires a PIN`() {
        // Turning protection OFF must always be allowed, even with no PIN or an
        // unreadable one — disabling cannot lock the owner out.
        assertFalse(repository.isPinUsable())
        val requested = repository.saveConfig(AppConfig(isEnabled = false))
        assertFalse(requested.isEnabled)
    }

    @Test
    fun `disabling is allowed when the PIN is unreadable`() {
        prefs.edit()
            .putString("pin_hash", "enc:broken")
            .putString("pin_salt", "enc:broken")
            .apply()

        val requested = repository.saveConfig(AppConfig(isEnabled = false))
        assertFalse(requested.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    @Test
    fun `enabling with a usable PIN preserves the rest of the config`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val requested = repository.saveConfig(
            AppConfig(isEnabled = true, wipeThreshold = 5, dryRunMode = false)
        )

        assertTrue(requested.isEnabled)
        assertEquals(5, requested.wipeThreshold)
        assertFalse(requested.dryRunMode)
        val persisted = repository.getConfig()
        assertTrue(persisted.isEnabled)
        assertEquals(5, persisted.wipeThreshold)
        assertFalse(persisted.dryRunMode)
    }

    @Test
    fun `legacy plaintext primitives are read without crashing and fail closed`() {
        // Pre-encryption builds stored these keys with putInt/putBoolean. Reading
        // them via getString throws ClassCastException on a real device; the
        // repository must coerce the legacy typed value instead of crashing. But
        // a plaintext value carries no integrity or key binding and is
        // indistinguishable from tampering, so it must fail closed to the
        // defaults and latch the tamper flag rather than be trusted.
        prefs.edit()
            .putInt("streak", 1)
            .putInt("config_wipe_threshold", 5)
            .putBoolean("config_dry_run_mode", false)
            .apply()

        assertEquals(0, repository.getStreak())
        val config = repository.getConfig()
        assertEquals(3, config.wipeThreshold)
        assertTrue(config.dryRunMode)
        assertTrue("legacy plaintext must be treated as tampering", repository.consumeStateTamperFlag())
    }

    @Test
    fun `vt counters saturate instead of overflowing`() {
        // Counters must clamp at Int.MAX_VALUE, never wrap negative.
        val vt = com.airgate.domain.model.ViolationType.WIFI_TRANSCEIVER_ENABLED
        val dayMs = 86_400_000L
        repository.recordVtBreach(vt, dayMs)
        repeat(100) { repository.recordVtBreach(vt, dayMs) }
        assertEquals(101, repository.getVtCount(vt))

        repository.setVtCount(vt, Int.MAX_VALUE - 1)
        repository.recordVtBreach(vt, dayMs)
        assertEquals(Int.MAX_VALUE, repository.getVtCount(vt))
    }

    @Test
    fun `vt counters reset on new scoring window`() {
        // A breach in a new window restarts the counter at 1.
        val vt = com.airgate.domain.model.ViolationType.BLUETOOTH_ACTIVITY
        val dayMs = 86_400_000L
        repository.recordVtBreach(vt, dayMs)
        repository.recordVtBreach(vt, dayMs)
        assertEquals(2, repository.getVtCount(vt))

        repository.recordVtBreach(vt, 0L)
        assertEquals(1, repository.getVtCount(vt))
    }

    @Test
    fun `recordVtBreach records occurrence counts without claiming the group point`() {
        // A record-only event must never spend the scoring group's daily point,
        // so the point that drives the wipe streak stays available.
        val vt = ViolationType.WIFI_TRANSCEIVER_ENABLED
        val dayMs = 86_400_000L
        repository.setStreak(1)

        repository.recordVtBreach(vt, dayMs)
        repository.recordVtBreach(vt, dayMs)

        assertEquals(2, repository.getVtCount(vt))
        assertFalse(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))
        assertTrue(repository.claimScoringGroupPoint(vt, dayMs))
    }

    @Test
    fun `claimScoringGroupPoint claims the point on the first call of a window`() {
        val vt = ViolationType.VALIDATED_NETWORK
        val dayMs = 86_400_000L
        repository.setStreak(1)

        assertTrue(repository.claimScoringGroupPoint(vt, dayMs))
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))
    }

    @Test
    fun `claimScoringGroupPoint is debounced within the same window`() {
        // The daily point is a scarce escalation resource: repeated claims from the
        // same group in one window must not farm multiple points.
        val vt = ViolationType.VALIDATED_NETWORK
        val dayMs = 86_400_000L

        assertTrue(repository.claimScoringGroupPoint(vt, dayMs))
        assertFalse(repository.claimScoringGroupPoint(vt, dayMs))
    }

    @Test
    fun `claimScoringGroupPoint re-claims once the window elapses`() {
        // A claim on a subsequent day (simulated by an elapsed window) earns a
        // fresh point for the group.
        val vt = ViolationType.VALIDATED_NETWORK
        val dayMs = 86_400_000L

        assertTrue(repository.claimScoringGroupPoint(vt, dayMs))
        assertTrue(repository.claimScoringGroupPoint(vt, 0L))
    }

    @Test
    fun `claimScoringGroupPoint is independent per scoring group`() {
        // Each group carries its own daily budget: scoring Wireless must not
        // block USB (or System Tamper) from claiming their own point.
        val wireless = ViolationType.VALIDATED_NETWORK
        val usb = ViolationType.USB_HOST_LINK
        val dayMs = 86_400_000L
        repository.setStreak(1)

        assertTrue(repository.claimScoringGroupPoint(wireless, dayMs))
        assertTrue(repository.claimScoringGroupPoint(usb, dayMs))
        assertFalse(repository.claimScoringGroupPoint(wireless, dayMs))
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.USB, dayMs))
        assertFalse(repository.isScoringGroupClaimedToday(ScoringGroup.SYSTEM_TAMPER, dayMs))
    }

    @Test
    fun `isScoringGroupClaimedToday reports claimed only when the streak is nonzero`() {
        // The guard is a UI concern: with no accumulated streak no group can be
        // "active today", even if a point claim was recorded before a streak reset.
        val vt = ViolationType.VALIDATED_NETWORK
        val dayMs = 86_400_000L
        repository.setStreak(1)
        repository.claimScoringGroupPoint(vt, dayMs)
        assertTrue(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))

        repository.setStreak(0)
        assertFalse(repository.isScoringGroupClaimedToday(ScoringGroup.WIRELESS, dayMs))
    }

    @Test
    fun `pin lockout state round-trips`() {
        assertEquals(0, repository.getPinFailedAttempts())

        assertEquals(1, repository.incrementPinFailedAttempts())
        assertEquals(2, repository.incrementPinFailedAttempts())

        repository.setPinLockoutUntil(123456789L)
        assertEquals(123456789L, repository.getPinLockoutUntil())

        repository.resetPinFailedAttempts()
        repository.setPinLockoutUntil(0L)
        assertEquals(0, repository.getPinFailedAttempts())
        assertEquals(0L, repository.getPinLockoutUntil())
    }

    @Test
    fun `default grace window is 60 seconds`() {
        // The pre-wipe countdown banner is the owner's window to cancel a pending
        // wipe with the PIN; a fresh install gets a real window, not an immediate wipe.
        assertEquals(60, repository.getConfig().graceWindowSeconds)
    }

    @Test
    fun `areNotificationsAllowed reflects the provider`() {
        val allowed = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { true })
        val denied = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { false })

        assertTrue(allowed.areNotificationsAllowed())
        assertFalse(denied.areNotificationsAllowed())
    }

    @Test
    fun `watchdog cannot be newly enabled when notifications are not allowed`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val denied = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { false })

        val requested = denied.saveConfig(AppConfig(isEnabled = true))
        // The enable request is coerced back to disabled: an armed device whose
        // alarm could be entirely silent is a broken enforcement state.
        assertFalse(requested.isEnabled)
        assertFalse(denied.getConfig().isEnabled)
    }

    @Test
    fun `watchdog can be newly enabled when the PIN is set and notifications are allowed`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val allowed = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { true })

        val requested = allowed.saveConfig(AppConfig(isEnabled = true))
        assertTrue(requested.isEnabled)
        assertTrue(allowed.getConfig().isEnabled)
    }

    @Test
    fun `disabling the watchdog never requires notifications`() {
        val denied = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { false })

        val requested = denied.saveConfig(AppConfig(isEnabled = false))
        assertFalse(requested.isEnabled)
    }

    @Test
    fun `an already-armed device stays armed when notifications are later revoked`() {
        // The notification gate guards the act of arming, not continued operation:
        // revoking notifications must not silently disarm a live device.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val allowed = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { true })
        assertTrue(allowed.saveConfig(AppConfig(isEnabled = true)).isEnabled)

        val revoked = SecurityStateRepository(prefs, JvmPrefsCrypto(), notificationsAllowedProvider = { false })
        // Toggling an unrelated setting with isEnabled still true does not disarm.
        val updated = revoked.saveConfig(AppConfig(isEnabled = true, wipeThreshold = 5))
        assertTrue(updated.isEnabled)
        assertEquals(5, updated.wipeThreshold)
        assertTrue(revoked.getConfig().isEnabled)
    }

    @Test
    fun `isBluetoothConnectAllowed reflects the provider`() {
        val allowed = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { true })
        val denied = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { false })

        assertTrue(allowed.isBluetoothConnectAllowed())
        assertFalse(denied.isBluetoothConnectAllowed())
    }

    @Test
    fun `watchdog cannot be newly enabled when bluetooth connect is not allowed`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val denied = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { false })

        val requested = denied.saveConfig(AppConfig(isEnabled = true))
        // The enable request is coerced back to disabled: a device armed while
        // Bluetooth state cannot be read would be silently blind to a core
        // air-gap signal.
        assertFalse(requested.isEnabled)
        assertFalse(denied.getConfig().isEnabled)
    }

    @Test
    fun `watchdog can be newly enabled when the PIN is set and bluetooth connect is allowed`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val allowed = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { true })

        val requested = allowed.saveConfig(AppConfig(isEnabled = true))
        assertTrue(requested.isEnabled)
        assertTrue(allowed.getConfig().isEnabled)
    }

    @Test
    fun `arming requires both notifications and bluetooth connect`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val notificationsAllowed = SecurityStateRepository(
            prefs, JvmPrefsCrypto(),
            notificationsAllowedProvider = { true },
            bluetoothConnectAllowedProvider = { false }
        )
        val bluetoothAllowed = SecurityStateRepository(
            prefs, JvmPrefsCrypto(),
            notificationsAllowedProvider = { false },
            bluetoothConnectAllowedProvider = { true }
        )

        assertFalse("notifications alone must not arm", notificationsAllowed.saveConfig(AppConfig(isEnabled = true)).isEnabled)
        assertFalse("bluetooth connect alone must not arm", bluetoothAllowed.saveConfig(AppConfig(isEnabled = true)).isEnabled)
    }

    @Test
    fun `disabling the watchdog never requires bluetooth connect`() {
        val denied = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { false })

        val requested = denied.saveConfig(AppConfig(isEnabled = false))
        assertFalse(requested.isEnabled)
    }

    @Test
    fun `an already-armed device stays armed when bluetooth connect is later revoked`() {
        // The bluetooth gate guards the act of arming, not continued operation:
        // revoking the grant must not silently disarm a live device.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        val allowed = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { true })
        assertTrue(allowed.saveConfig(AppConfig(isEnabled = true)).isEnabled)

        val revoked = SecurityStateRepository(prefs, JvmPrefsCrypto(), bluetoothConnectAllowedProvider = { false })
        // Toggling an unrelated setting with isEnabled still true does not disarm.
        val updated = revoked.saveConfig(AppConfig(isEnabled = true, wipeThreshold = 5))
        assertTrue(updated.isEnabled)
        assertEquals(5, updated.wipeThreshold)
        assertTrue(revoked.getConfig().isEnabled)
    }

    @Test
    fun `pending alarm round-trips through the repository`() {
        assertNull(repository.getPendingAlarm())

        repository.setPendingAlarm(
            PendingAlarm(
                category = "Wireless",
                description = "Network connection detected",
                timestamp = 123456789L,
                isCountdown = false
            )
        )

        val pending = repository.getPendingAlarm()
        assertNotNull(pending)
        assertEquals("Wireless", pending?.category)
        assertEquals("Network connection detected", pending?.description)
        assertEquals(123456789L, pending?.timestamp)
        assertFalse(pending?.isCountdown == true)

        // A new repository instance over the same prefs must still see it: the
        // marker is persisted, not held in memory.
        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())
        assertEquals("Wireless", reloaded.getPendingAlarm()?.category)
    }

    @Test
    fun `pending alarm countdown flag round-trips`() {
        repository.setPendingAlarm(
            PendingAlarm("WIPE COUNTDOWN", "A wipe is scheduled.", 5L, isCountdown = true)
        )
        assertTrue(repository.getPendingAlarm()?.isCountdown == true)
    }

    @Test
    fun `clearPendingAlarm removes the marker`() {
        repository.setPendingAlarm(PendingAlarm("USB", "USB device connected", 5L, isCountdown = false))
        assertNotNull(repository.getPendingAlarm())

        repository.clearPendingAlarm()

        assertNull(repository.getPendingAlarm())
        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())
        assertNull(reloaded.getPendingAlarm())
    }

    @Test
    fun `a new pending alarm overwrites the previous one`() {
        repository.setPendingAlarm(PendingAlarm("USB", "USB device connected", 1L, isCountdown = false))
        repository.setPendingAlarm(PendingAlarm("Wireless", "Airplane mode off", 2L, isCountdown = false))

        val pending = repository.getPendingAlarm()
        assertEquals("Wireless", pending?.category)
        assertEquals("Airplane mode off", pending?.description)
        assertEquals(2L, pending?.timestamp)
    }

    @Test
    fun `resetStreak clears the pending alarm`() {
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.setPendingAlarm(PendingAlarm("USB", "USB device connected", 5L, isCountdown = false))
        assertNotNull(repository.getPendingAlarm())

        // resetStreak is only reachable through PIN-gated owner actions, so the
        // owner's reset doubles as an acknowledgment of the alarm.
        repository.resetStreak()

        assertNull(repository.getPendingAlarm())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    // --- Fail-closed security state ---

    @Test
    fun `absent security state is a fresh install and reads compliant`() {
        // A key that was never written is the fresh-install case; only a PRESENT
        // but unreadable value is treated as tampering.
        assertNull(prefs.getString("security_state", null))
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun `corrupt protected security state fails closed to an alarm`() {
        // The core fail-open regression: a stored state that cannot be verified or
        // decrypted must never surface as "armed and compliant".
        prefs.edit()
            .putString("security_state", "enc:broken")
            .apply()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("the failed read must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    @Test
    fun `unparseable security state fails closed to an alarm`() {
        // A value that decrypts but is not a valid SecurityState is equally
        // untrustworthy and must fail closed rather than coerce to compliant.
        prefs.edit()
            .putString("security_state", "NOT_A_STATE")
            .apply()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun `valid security state round-trips`() {
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

        assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
        assertFalse(repository.consumeStateTamperFlag())
    }

    @Test
    fun `corrupt state fails closed on every read until rewritten`() {
        prefs.edit()
            .putString("security_state", "enc:broken")
            .apply()
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())

        // An owner/audit rewriting a valid state clears the fail-closed read.
        repository.setSecurityState(SecurityState.ARMED_COMPLIANT)

        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun `legacy plaintext security state fails closed to an alarm`() {
        // A pre-encryption build stored the state as a raw string. It carries no
        // integrity or key binding and is indistinguishable from tampering, so it
        // must fail closed to an alarm rather than be honored.
        prefs.edit()
            .putString("security_state", "WIPING")
            .apply()

        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertTrue("a legacy plaintext state must latch the tamper flag", repository.consumeStateTamperFlag())
    }

    // --- Encrypted-path fail-closed coverage (injected JVM crypto) ---

    @Test
    fun `encrypted security state round-trips`() {
        val encrypted = SecurityStateRepository(prefs, JvmPrefsCrypto())
        encrypted.setSecurityState(SecurityState.WIPING)

        assertEquals(SecurityState.WIPING, encrypted.getSecurityState())
        assertFalse(encrypted.consumeStateTamperFlag())
    }

    @Test
    fun `a tampered encrypted compliant state fails closed to an alarm`() {
        // The exploit scenario: a stored "compliant" state is modified in place.
        // The keyed-MAC must reject it and the read must surface an alarm, never
        // the tampered (or the fail-open default) value.
        val encrypted = SecurityStateRepository(prefs, JvmPrefsCrypto())
        encrypted.setSecurityState(SecurityState.ARMED_COMPLIANT)
        val stored = prefs.getString("security_state", null)!!
        val parts = stored.removePrefix("enc:").split(":")
        val cipher = java.util.Base64.getDecoder().decode(parts[1]).apply {
            this[0] = (this[0].toInt() xor 0x01).toByte()
        }
        prefs.edit()
            .putString(
                "security_state",
                "enc:" + parts[0] + ":" + java.util.Base64.getEncoder().encodeToString(cipher) + ":" + parts[2]
            )
            .apply()

        assertEquals(SecurityState.ALARM_ACTIVE, encrypted.getSecurityState())
        assertTrue("in-place modification must set the tamper flag", encrypted.consumeStateTamperFlag())
    }

    @Test
    fun `a foreign encrypted blob fails closed`() {
        // A value encrypted under a different key (ciphertext swap) must be
        // rejected by the keyed-MAC, not silently accepted as the fail-open default.
        val encrypted = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val foreign = SecurityStateRepository(prefs, JvmPrefsCrypto("foreign-keys"))
        foreign.setSecurityState(SecurityState.ARMED_COMPLIANT)

        assertEquals(
            SecurityState.ALARM_ACTIVE,
            encrypted.getSecurityState()
        )
        assertTrue(encrypted.consumeStateTamperFlag())
    }

    // --- Refused writes leave the prior persisted value untouched ---

    @Test
    fun `a refused security-state write leaves the prior persisted state untouched while memory advances`() {
        // When the keystore is broken, a write is refused rather than persisted
        // in plaintext. The in-memory view advances for the current process, but
        // the refused write never reaches disk: a fresh repository over the same
        // prefs (e.g. after a restart) sees the prior persisted value, never the
        // in-memory one.
        val healthy = SecurityStateRepository(prefs, JvmPrefsCrypto())
        healthy.setSecurityState(SecurityState.ALARM_ACTIVE)

        val broken = SecurityStateRepository(prefs, FailingPrefsCrypto())
        broken.setSecurityState(SecurityState.WIPING)

        val stored = prefs.getString("security_state", null)
        assertNotNull(stored)
        assertTrue("the prior encrypted value must survive the refused write", stored!!.startsWith("enc:"))
        assertTrue("the refused write must latch the tamper flag", broken.consumeStateTamperFlag())
        assertEquals("the in-memory view advances for the current process", SecurityState.WIPING, broken.getSecurityState())

        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())
        assertEquals(
            "a fresh repository must read the persisted value, not the refused one",
            SecurityState.ALARM_ACTIVE,
            reloaded.getSecurityState()
        )
        assertFalse(reloaded.consumeStateTamperFlag())
    }

    @Test
    fun `a refused streak write leaves the prior persisted streak untouched`() {
        val healthy = SecurityStateRepository(prefs, JvmPrefsCrypto())
        healthy.setStreak(3)

        val broken = SecurityStateRepository(prefs, FailingPrefsCrypto())
        broken.setStreak(5)

        assertEquals("the in-memory streak advances for the current process", 5, broken.getStreak())
        assertTrue(broken.consumeStateTamperFlag())

        val reloaded = SecurityStateRepository(prefs, JvmPrefsCrypto())
        assertEquals("the refused streak write must not reach disk", 3, reloaded.getStreak())
        assertFalse(reloaded.consumeStateTamperFlag())
    }

    @Test
    fun `a refused pin write leaves the PIN unset`() {
        // The crown-jewel case: when the keystore is broken, savePin must not
        // persist anything — a plaintext PIN hash/salt would be the worst leak.
        val broken = SecurityStateRepository(prefs, FailingPrefsCrypto())

        broken.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)

        assertFalse("with nothing persisted the PIN cannot be set", broken.isPinSet())
        assertFalse(broken.isPinUsable())
        assertTrue("the refused PIN write must latch the tamper flag", broken.consumeStateTamperFlag())
    }

    @Test
    fun `tamper detected in one repository instance is visible across another repository instance`() {
        val repo1 = SecurityStateRepository(prefs, JvmPrefsCrypto())
        val repo2 = SecurityStateRepository(InMemorySharedPreferences(), JvmPrefsCrypto("other"))

        prefs.edit().putString("security_state", "enc:broken").apply()
        repo1.getSecurityState()

        assertTrue("repo2 must see tamper flag raised by repo1", repo2.consumeStateTamperFlag())
        assertFalse("repo1 must now see cleared flag", repo1.consumeStateTamperFlag())
        assertFalse("repo2 must also see cleared flag", repo2.consumeStateTamperFlag())
    }

    /**
     * A [PrefsCrypto] whose every operation throws, standing in for a keystore
     * that is present but broken mid-operation.
     */
    private class FailingPrefsCrypto : PrefsCrypto {
        override fun encrypt(data: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> =
            throw IllegalStateException("encrypt failed")

        override fun decrypt(ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
            throw IllegalStateException("decrypt failed")

        override fun hmac(data: ByteArray): ByteArray =
            throw IllegalStateException("hmac failed")
    }
}
