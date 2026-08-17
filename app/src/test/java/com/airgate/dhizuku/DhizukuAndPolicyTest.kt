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

package com.airgate.dhizuku

import android.content.ComponentName
import android.content.Context
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult
import com.airgate.policy.DevicePolicyEnforcer
import com.airgate.policy.WipeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DhizukuAndPolicyTest {

    private class MockDhizukuBinder : DhizukuBinderWrapper {
        val globalSettings = mutableMapOf<String, String>()
        val userRestrictions = mutableSetOf<String>()
        var wipeCalled = false
        var wipeFlags = 0
        var wipeAccepted = true

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

        override fun wipeDevice(flags: Int): Boolean {
            wipeCalled = true
            wipeFlags = flags
            return wipeAccepted
        }
    }

    private lateinit var mockBinder: MockDhizukuBinder
    private lateinit var dhizukuManager: DhizukuManager
    private lateinit var policyEnforcer: DevicePolicyEnforcer
    private lateinit var wipeController: WipeController

    @Before
    fun setUp() {
        val dummyContext = DummyContext()
        mockBinder = MockDhizukuBinder()
        dhizukuManager = DhizukuManager(dummyContext, mockBinder)
        policyEnforcer = DevicePolicyEnforcer(dummyContext, dhizukuManager)
        wipeController = WipeController(dummyContext, dhizukuManager)
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"

        override fun getSystemService(name: String): Any? = null
    }

    @Test
    fun `dhizuku permission granted status`() {
        assertTrue(dhizukuManager.isDhizukuAvailable())
    }

    @Test
    fun `policy enforcement sets global settings and restrictions in non-dry-run`() {
        val liveConfig = AppConfig(dryRunMode = false)
        val results = policyEnforcer.enforceAllPolicies(liveConfig)

        assertTrue(results.values.all { it })
        // Airplane mode is NOT set by enforceAllPolicies — it is managed
        // independently via enforceAirplaneMode() to avoid coupling it to
        // the debugging toggle.
        assertFalse(mockBinder.globalSettings.containsKey("airplane_mode_on"))
        assertEquals("0", mockBinder.globalSettings["adb_enabled"])
        assertEquals(DevicePolicyEnforcer.REQUIRED_USER_RESTRICTIONS.size, mockBinder.userRestrictions.size)
        assertTrue(mockBinder.userRestrictions.contains(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test
    fun `blockDebuggingFeatures false clears the debugging restriction instead of adding it`() {
        // Simulate a device that currently has the restriction applied.
        mockBinder.userRestrictions.add(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)

        val liveConfig = AppConfig(dryRunMode = false, blockDebuggingFeatures = false)
        val results = policyEnforcer.enforceAllPolicies(liveConfig)

        assertTrue(results[android.os.UserManager.DISALLOW_DEBUGGING_FEATURES] ?: false)
        assertFalse(mockBinder.userRestrictions.contains(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
        // Every other required restriction is still enforced.
        assertEquals(
            DevicePolicyEnforcer.REQUIRED_USER_RESTRICTIONS.size - 1,
            mockBinder.userRestrictions.size
        )
    }

    @Test
    fun `blockDebuggingFeatures false restores adb_enabled instead of pinning it to 0`() {
        val liveConfig = AppConfig(dryRunMode = false, blockDebuggingFeatures = false)
        policyEnforcer.enforceAllPolicies(liveConfig)

        assertEquals("1", mockBinder.globalSettings["adb_enabled"])
    }

    @Test
    fun `blockDebuggingFeatures true pins adb_enabled to 0`() {
        val liveConfig = AppConfig(dryRunMode = false, blockDebuggingFeatures = true)
        policyEnforcer.enforceAllPolicies(liveConfig)

        assertEquals("0", mockBinder.globalSettings["adb_enabled"])
    }

    @Test
    fun `dryRunMode still really enforces the debugging block`() {
        val dryRunConfig = AppConfig(dryRunMode = true, blockDebuggingFeatures = true)
        val results = policyEnforcer.enforceAllPolicies(dryRunConfig)

        assertEquals("0", mockBinder.globalSettings["adb_enabled"])
        assertEquals("0", mockBinder.globalSettings["development_settings_enabled"])
        assertTrue(mockBinder.userRestrictions.contains(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
        assertTrue(results[android.os.UserManager.DISALLOW_DEBUGGING_FEATURES] ?: false)
    }

    @Test
    fun `dryRunMode still restores adb when debugging block is off`() {
        // Simulate a device that is currently blocked.
        mockBinder.userRestrictions.add(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
        mockBinder.globalSettings["adb_enabled"] = "0"
        mockBinder.globalSettings["development_settings_enabled"] = "0"

        val dryRunConfig = AppConfig(dryRunMode = true, blockDebuggingFeatures = false)
        policyEnforcer.enforceAllPolicies(dryRunConfig)

        assertEquals("1", mockBinder.globalSettings["adb_enabled"])
        assertEquals("1", mockBinder.globalSettings["development_settings_enabled"])
        assertFalse(mockBinder.userRestrictions.contains(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test
    fun `dryRunMode enforces all policy writes for real`() {
        // Dry-run gates only the destructive wipe; every policy write executes for real.
        val dryRunConfig = AppConfig(dryRunMode = true)
        policyEnforcer.enforceAllPolicies(dryRunConfig)

        // Airplane mode is NOT set by enforceAllPolicies — it is managed
        // independently via enforceAirplaneMode().
        assertFalse(mockBinder.globalSettings.containsKey("airplane_mode_on"))
        assertEquals("0", mockBinder.globalSettings["adb_enabled"])
        assertEquals(DevicePolicyEnforcer.REQUIRED_USER_RESTRICTIONS.size, mockBinder.userRestrictions.size)
        assertTrue(mockBinder.userRestrictions.contains(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test
    fun `verifyActiveRestrictions ignores debugging restriction when switch is off`() {
        val active = policyEnforcer.verifyActiveRestrictions(AppConfig(blockDebuggingFeatures = false))
        assertFalse(active.containsKey(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))

        val enforced = policyEnforcer.verifyActiveRestrictions(AppConfig(blockDebuggingFeatures = true))
        assertTrue(enforced.containsKey(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test
    fun `dryRunMode intercepts wipeDevice calls safely`() {
        // Dry-run: the wipe reports a truthful simulated result (so the factory-reset
        // simulation screen can be shown) without touching the destructive API.
        val dryRunConfig = AppConfig(dryRunMode = true)
        val result = wipeController.executeWipe(dryRunConfig)

        assertEquals(WipeResult.SIMULATED, result)
        assertEquals(false, mockBinder.wipeCalled) // Wipe must NOT be called on mock binder in dryRunMode
    }

    @Test
    fun `live wipeDevice passes correct flags to Dhizuku and reports acceptance`() {
        val liveConfig = AppConfig(dryRunMode = false, includeFRPData = true)
        val result = wipeController.executeWipe(liveConfig)

        assertEquals(WipeResult.ACCEPTED, result)
        assertTrue(mockBinder.wipeCalled)
        assertEquals(WipeController.WIPE_SILENTLY or WipeController.WIPE_RESET_PROTECTION_DATA, mockBinder.wipeFlags)
    }

    @Test
    fun `live wipeDevice without FRP passes silently flag only`() {
        val liveConfig = AppConfig(dryRunMode = false, includeFRPData = false)
        val result = wipeController.executeWipe(liveConfig)

        assertEquals(WipeResult.ACCEPTED, result)
        assertEquals(WipeController.WIPE_SILENTLY, mockBinder.wipeFlags)
    }

    @Test
    fun `live wipeDevice that is rejected is reported as REJECTED`() {
        mockBinder.wipeAccepted = false
        val liveConfig = AppConfig(dryRunMode = false)
        val result = wipeController.executeWipe(liveConfig)

        assertEquals(WipeResult.REJECTED, result)
        assertTrue(mockBinder.wipeCalled)
    }

    // --- enforceAirplaneMode tests ---

    @Test
    fun `enforceAirplaneMode sets airplane_mode_on to 1`() {
        val config = AppConfig(isEnabled = true)
        val result = policyEnforcer.enforceAirplaneMode(config)

        assertTrue(result)
        assertEquals("1", mockBinder.globalSettings["airplane_mode_on"])
    }

    @Test
    fun `enforceAirplaneMode returns false and does not set when config is disabled`() {
        val config = AppConfig(isEnabled = false)
        val result = policyEnforcer.enforceAirplaneMode(config)

        assertFalse(result)
        assertFalse(mockBinder.globalSettings.containsKey("airplane_mode_on"))
    }

    @Test
    fun `enforceAirplaneMode works in dry-run mode`() {
        val config = AppConfig(isEnabled = true, dryRunMode = true)
        val result = policyEnforcer.enforceAirplaneMode(config)

        assertTrue(result)
        assertEquals("1", mockBinder.globalSettings["airplane_mode_on"])
    }

    // --- Decoupling tests: debugging toggle must not affect airplane mode ---

    @Test
    fun `toggling blockDebuggingFeatures on does not set airplane_mode_on`() {
        val configWithBlock = AppConfig(blockDebuggingFeatures = true)
        policyEnforcer.enforceAllPolicies(configWithBlock)

        assertFalse(mockBinder.globalSettings.containsKey("airplane_mode_on"))
    }

    @Test
    fun `toggling blockDebuggingFeatures off does not set airplane_mode_on`() {
        val configWithoutBlock = AppConfig(blockDebuggingFeatures = false)
        policyEnforcer.enforceAllPolicies(configWithoutBlock)

        assertFalse(mockBinder.globalSettings.containsKey("airplane_mode_on"))
    }

    @Test
    fun `enforceAirplaneMode and enforceAllPolicies are independent`() {
        // Simulate the scenario: user toggles debugging block, then separately
        // airplane mode is enforced by the posture audit.
        val config = AppConfig(isEnabled = true, blockDebuggingFeatures = true)

        // Step 1: Debugging toggle calls enforceAllPolicies
        policyEnforcer.enforceAllPolicies(config)
        assertFalse(mockBinder.globalSettings.containsKey("airplane_mode_on"))

        // Step 2: PostureAudit calls enforceAirplaneMode
        val airplaneResult = policyEnforcer.enforceAirplaneMode(config)
        assertTrue(airplaneResult)
        assertEquals("1", mockBinder.globalSettings["airplane_mode_on"])

        // Debugging restrictions are still enforced
        assertTrue(mockBinder.userRestrictions.contains(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES))
        assertEquals("0", mockBinder.globalSettings["adb_enabled"])
    }
}
