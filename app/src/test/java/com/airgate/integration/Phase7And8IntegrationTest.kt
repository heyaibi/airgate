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

package com.airgate.integration

import android.content.ContextWrapper
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.crypto.PinManager
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ViolationType
import com.airgate.domain.model.SecurityState
import com.airgate.engine.ThreatEngine
import com.airgate.testing.DryRunHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Phase7And8IntegrationTest {

    private class DummyContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
        override fun getFilesDir(): File = baseDir
    }

    private class MockDhizukuBinder : DhizukuBinderWrapper {
        var isWiped = false
        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: android.content.ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: android.content.ComponentName, key: String, value: String): Boolean = true
        override fun addUserRestriction(admin: android.content.ComponentName, key: String): Boolean = true
        override fun clearUserRestriction(admin: android.content.ComponentName, key: String): Boolean = true
        override fun wipeDevice(flags: Int): Boolean {
            isWiped = true
            return true
        }
    }

    private class MockSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val map: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) map[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) map[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }

    private lateinit var tempDir: File
    private lateinit var dummyContext: DummyContext
    private lateinit var repository: SecurityStateRepository
    private lateinit var mockBinder: MockDhizukuBinder
    private lateinit var dhizukuManager: DhizukuManager
    private lateinit var threatEngine: ThreatEngine
    private lateinit var dryRunHarness: DryRunHarness

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "ag_phase7_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        dummyContext = DummyContext(tempDir)
        val prefs = MockSharedPreferences()
        repository = SecurityStateRepository(prefs, JvmPrefsCrypto())
        // The watchdog may only be armed after a PIN is configured.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, wipeThreshold = 3, graceWindowSeconds = 0))
        mockBinder = MockDhizukuBinder()

        dhizukuManager = DhizukuManager(dummyContext, mockBinder)
        threatEngine = ThreatEngine(dummyContext, repository, dhizukuManager, customWindowMs = 0L)
        dryRunHarness = DryRunHarness(dummyContext, repository, dhizukuManager, threatEngine)
    }

    @Test
    fun testDryRunHarness_SimulateThreatProgressionToThreshold() {
        // Initial streak is 0
        assertEquals(0, repository.getStreak())
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())

        // 1. Simulate Bluetooth breach (weight = 1, ALARM_STREAK) -> streak = 1.
        //    Wi-Fi on is LOG_ONLY, so it cannot drive streak progression;
        //    Bluetooth remains an alarming category.
        dryRunHarness.simulateBluetoothBreach()
        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())

        // 2. Simulate USB host attach -> consumes 1 point for USB scoring group -> streak = 2
        dryRunHarness.simulateUsbHostAttach()
        assertEquals(2, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())

        // 3. Simulate clock shift -> consumes 1 point for SYSTEM_TAMPER scoring group -> streak = 3 >= wipeThreshold (3)
        dryRunHarness.simulateClockShift()
        assertEquals(3, repository.getStreak())
        assertEquals(SecurityState.WIPING, repository.getSecurityState())

        // Ensure in dry-run mode, wipeDevice was NOT called on system/mock binder
        assertEquals(false, mockBinder.isWiped)
    }

    @Test
    fun testDryRunHarness_SimulateInstantWipeCategory() {
        dryRunHarness.simulateAdbEnabled()
        assertEquals(1, repository.getStreak())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
        assertEquals(false, mockBinder.isWiped)
    }
}
