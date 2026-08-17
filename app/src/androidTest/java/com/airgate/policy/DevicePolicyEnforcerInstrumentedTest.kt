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

package com.airgate.policy

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.dhizuku.DhizukuManager
import com.airgate.dhizuku.DhizukuBinderWrapper
import com.airgate.domain.model.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of [DevicePolicyEnforcer] behavior, specifically
 * that airplane mode enforcement is decoupled from the debugging toggle.
 */
@RunWith(AndroidJUnit4::class)
class DevicePolicyEnforcerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class RecordingBinder : DhizukuBinderWrapper {
        val globalSettings = mutableMapOf<String, String>()
        val userRestrictions = mutableSetOf<String>()

        override fun isPermissionGranted(): Boolean = true

        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true

        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean {
            globalSettings[key] = value
            return true
        }

        override fun addUserRestriction(admin: ComponentName, key: String): Boolean {
            userRestrictions.add(key)
            return true
        }

        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean {
            userRestrictions.remove(key)
            return true
        }

        override fun wipeDevice(flags: Int): Boolean = true
    }

    private lateinit var recordingBinder: RecordingBinder
    private lateinit var dhizukuManager: DhizukuManager
    private lateinit var policyEnforcer: DevicePolicyEnforcer

    @Before
    fun setUp() {
        recordingBinder = RecordingBinder()
        dhizukuManager = DhizukuManager(context, recordingBinder)
        policyEnforcer = DevicePolicyEnforcer(context, dhizukuManager)
    }

    @Test
    fun enforceAllPolicies_doesNotSetAirplaneMode_onDevice() {
        val config = AppConfig(isEnabled = true, blockDebuggingFeatures = true)
        val results = policyEnforcer.enforceAllPolicies(config)

        assertTrue(results.values.all { it })
        // Airplane mode must NOT be set by enforceAllPolicies — it is managed
        // independently via enforceAirplaneMode().
        assertFalse(
            "enforceAllPolicies must not set airplane_mode_on",
            recordingBinder.globalSettings.containsKey("airplane_mode_on")
        )
        // Debugging restrictions are still enforced.
        assertEquals("0", recordingBinder.globalSettings["adb_enabled"])
        assertTrue(recordingBinder.userRestrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test
    fun enforceAirplaneMode_setsAirplaneModeWhenEnabled_onDevice() {
        val config = AppConfig(isEnabled = true)
        val result = policyEnforcer.enforceAirplaneMode(config)

        assertTrue(result)
        assertEquals("1", recordingBinder.globalSettings["airplane_mode_on"])
    }

    @Test
    fun enforceAirplaneMode_doesNotSetWhenDisabled_onDevice() {
        val config = AppConfig(isEnabled = false)
        val result = policyEnforcer.enforceAirplaneMode(config)

        assertFalse(result)
        assertFalse(
            "airplane_mode_on must not be set when watchdog is disabled",
            recordingBinder.globalSettings.containsKey("airplane_mode_on")
        )
    }

    @Test
    fun debuggingToggle_doesNotAffectAirplaneMode_onDevice() {
        // Simulate toggling blockDebuggingFeatures on
        val configWithBlock = AppConfig(isEnabled = true, blockDebuggingFeatures = true)
        policyEnforcer.enforceAllPolicies(configWithBlock)

        assertFalse(
            "toggling blockDebuggingFeatures on must not set airplane_mode_on",
            recordingBinder.globalSettings.containsKey("airplane_mode_on")
        )

        // Simulate toggling blockDebuggingFeatures off
        val configWithoutBlock = AppConfig(isEnabled = true, blockDebuggingFeatures = false)
        policyEnforcer.enforceAllPolicies(configWithoutBlock)

        assertFalse(
            "toggling blockDebuggingFeatures off must not set airplane_mode_on",
            recordingBinder.globalSettings.containsKey("airplane_mode_on")
        )
    }

    @Test
    fun airplaneModeAndDebuggingBlock_areIndependent_onDevice() {
        val config = AppConfig(isEnabled = true, blockDebuggingFeatures = true)

        // Step 1: enforceAllPolicies (debugging toggle)
        policyEnforcer.enforceAllPolicies(config)
        assertFalse(
            "enforceAllPolicies must not set airplane_mode_on",
            recordingBinder.globalSettings.containsKey("airplane_mode_on")
        )

        // Step 2: enforceAirplaneMode (posture audit)
        val airplaneResult = policyEnforcer.enforceAirplaneMode(config)
        assertTrue(airplaneResult)
        assertEquals("1", recordingBinder.globalSettings["airplane_mode_on"])

        // Debugging restrictions are still enforced
        assertTrue(recordingBinder.userRestrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES))
        assertEquals("0", recordingBinder.globalSettings["adb_enabled"])
    }

    @Test
    fun enforceAirplaneMode_worksInDryRunMode_onDevice() {
        val config = AppConfig(isEnabled = true, dryRunMode = true)
        val result = policyEnforcer.enforceAirplaneMode(config)

        assertTrue(result)
        assertEquals("1", recordingBinder.globalSettings["airplane_mode_on"])
    }
}
