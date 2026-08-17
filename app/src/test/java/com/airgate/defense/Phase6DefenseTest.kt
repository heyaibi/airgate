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

package com.airgate.defense

import android.content.ContextWrapper
import com.airgate.data.crypto.JvmPrefsCrypto
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.ViolationType
import com.airgate.domain.model.BreachEvent
import com.airgate.domain.model.SecurityState
import com.airgate.engine.ThreatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Phase6DefenseTest {

    private class DummyContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
        override fun getFilesDir(): File = baseDir
    }

    private class MockDhizukuBinder(var isGranted: Boolean = true) : DhizukuBinderWrapper {
        override fun isPermissionGranted(): Boolean = isGranted
        override fun bindUserService(componentName: android.content.ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: android.content.ComponentName, key: String, value: String): Boolean = true
        override fun addUserRestriction(admin: android.content.ComponentName, key: String): Boolean = true
        override fun clearUserRestriction(admin: android.content.ComponentName, key: String): Boolean = true
        override fun wipeDevice(flags: Int): Boolean = true
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
    private lateinit var selfDefenseManager: SelfDefenseManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "ag_phase6_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        dummyContext = DummyContext(tempDir)
        val prefs = MockSharedPreferences()
        repository = SecurityStateRepository(prefs, JvmPrefsCrypto())
        // The watchdog may only be armed after a PIN is configured.
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repository.saveConfig(
            AppConfig(
                isEnabled = true,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = true
            )
        )

        mockBinder = MockDhizukuBinder(isGranted = true)
        dhizukuManager = DhizukuManager(dummyContext, mockBinder)
        threatEngine = ThreatEngine(dummyContext, repository, dhizukuManager)
        selfDefenseManager = SelfDefenseManager(dummyContext, dhizukuManager, threatEngine, repository = repository)
    }

    @Test
    fun testSelfDefense_DOStatusLost_TriggersBreachAndWipe() {
        repository.saveConfig(
            AppConfig(
                isEnabled = false,
                dryRunMode = true,
                graceWindowSeconds = 0,
                deviceProtectionAlarmEnabled = false
            )
        )
        val revokedBinder = MockDhizukuBinder(isGranted = false)
        val revokedDhizuku = DhizukuManager(dummyContext, revokedBinder)
        val testDefenseManager = SelfDefenseManager(dummyContext, revokedDhizuku, threatEngine, repository = repository)

        // A single transient failure (e.g. binder flake at boot/wake) must NOT wipe.
        testDefenseManager.checkDeviceOwnerStatus()
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())

        // A sustained loss across the consecutive-failure threshold does.
        repeat(SelfDefenseManager.DHIzuku_FAILURE_THRESHOLD - 1) {
            testDefenseManager.checkDeviceOwnerStatus()
        }
        val result = testDefenseManager.checkDeviceOwnerStatus()
        assertFalse(result)
        // Self-defense breaches route through the configured selfTamperTier
        // (default INSTANT_WIPE), so the device enters WIPING — not ALARM_ACTIVE.
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun testSelfDefense_DOStatusRecovered_ResetsFailureCount() {
        val flakyBinder = MockDhizukuBinder(isGranted = false)
        val flakyDhizuku = DhizukuManager(dummyContext, flakyBinder)
        val testDefenseManager = SelfDefenseManager(dummyContext, flakyDhizuku, threatEngine, repository = repository)

        repeat(2) { testDefenseManager.checkDeviceOwnerStatus() }
        assertEquals(2, repository.getDhizukuConsecutiveFailures())

        flakyBinder.isGranted = true
        testDefenseManager.checkDeviceOwnerStatus()
        assertEquals(0, repository.getDhizukuConsecutiveFailures())
        // Recovery before the threshold means no breach was ever raised.
        assertEquals(SecurityState.ARMED_COMPLIANT, repository.getSecurityState())
    }

    @Test
    fun testSelfDefense_SignatureTamper_TriggersBreach() {
        val testSelfDefense = object : SelfDefenseManager(
            dummyContext, dhizukuManager, threatEngine,
            expectedSignatureHash = "abcd1234expected", repository = repository
        ) {
            override fun getPackageSignatureHash(): String {
                return "1234badhash"
            }
        }

        val result = testSelfDefense.verifyAppSignature()
        assertFalse(result)
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }
}
