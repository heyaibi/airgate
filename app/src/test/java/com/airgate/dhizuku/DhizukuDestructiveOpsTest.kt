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
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers every branch of [DhizukuDestructiveOps.wipeDevice]: the dry-run gate,
 * the wrapper-backed acceptance path, a throwing wrapper, the missing
 * device-owner path, and the API-33 selection for the platform wipe call.
 */
class DhizukuDestructiveOpsTest {

    private class RecordingWrapper : DhizukuBinderWrapper {
        var wipeCalled = false
        var wipeFlags = 0
        var wipeAccepted = true
        var wipeThrows = false

        override fun isPermissionGranted(): Boolean = true
        override fun bindUserService(componentName: ComponentName, connection: Any): Boolean = true
        override fun setGlobalSetting(admin: ComponentName, key: String, value: String): Boolean = true
        override fun addUserRestriction(admin: ComponentName, key: String): Boolean = true
        override fun clearUserRestriction(admin: ComponentName, key: String): Boolean = true
        override fun wipeDevice(flags: Int): Boolean {
            wipeCalled = true
            wipeFlags = flags
            if (wipeThrows) throw RuntimeException("binder failure")
            return wipeAccepted
        }
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.airgate"
    }

    private lateinit var context: DummyContext
    private lateinit var wrapper: RecordingWrapper
    private lateinit var ops: DhizukuDestructiveOps

    @Before
    fun setUp() {
        context = DummyContext()
        wrapper = RecordingWrapper()
        ops = opsWith(wrapper, sdkInt = 35)
    }

    private fun opsWith(wrapper: DhizukuBinderWrapper?, sdkInt: Int): DhizukuDestructiveOps {
        val connection = DhizukuConnection(context, wrapper)
        val bridge = DhizukuDpmBridge(context, connection, wrapper)
        return DhizukuDestructiveOps(bridge, sdkInt = sdkInt)
    }

    @Test
    fun `dry-run reports a simulation and never calls the destructive API`() {
        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = true))

        assertEquals(WipeResult.SIMULATED, result)
        assertFalse(wrapper.wipeCalled)
    }

    @Test
    fun `an accepted wipe is reported as ACCEPTED`() {
        wrapper.wipeAccepted = true

        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = false))

        assertEquals(WipeResult.ACCEPTED, result)
        assertTrue(wrapper.wipeCalled)
    }

    @Test
    fun `a refused wipe is reported as REJECTED`() {
        wrapper.wipeAccepted = false

        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = false))

        assertEquals(WipeResult.REJECTED, result)
        assertTrue(wrapper.wipeCalled)
    }

    @Test
    fun `a throwing wipe call is reported as REJECTED`() {
        wrapper.wipeThrows = true

        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = false))

        assertEquals(WipeResult.REJECTED, result)
        assertTrue(wrapper.wipeCalled)
    }

    @Test
    fun `wipe flags are forwarded to the destructive call`() {
        ops.wipeDevice(flags = 0x8009, config = AppConfig(dryRunMode = false))

        assertEquals(0x8009, wrapper.wipeFlags)
    }

    @Test
    fun `a wipe with no device-owner authority is reported as REJECTED`() {
        // No wrapper and no real Dhizuku service available in a JVM: wrappedDpm()
        // resolves to null, so the wipe must fail closed rather than claim success.
        val noAuthorityOps = opsWith(wrapper = null, sdkInt = 35)

        val result = noAuthorityOps.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = false))

        assertEquals(WipeResult.REJECTED, result)
    }

    @Test
    fun `API 34 and above select the device-owner wipeDevice API`() {
        val ops34 = opsWith(wrapper, sdkInt = 34)
        val ops35 = opsWith(wrapper, sdkInt = 35)
        val ops37 = opsWith(wrapper, sdkInt = 37)

        assertTrue(ops34.shouldUseWipeDevice(34))
        assertTrue(ops35.shouldUseWipeDevice(35))
        assertTrue(ops37.shouldUseWipeDevice(37))
    }

    @Test
    fun `below API 34 the wipe falls back to wipeData`() {
        val ops26 = opsWith(wrapper, sdkInt = 26)
        val ops32 = opsWith(wrapper, sdkInt = 32)
        val ops33 = opsWith(wrapper, sdkInt = 33)

        assertFalse(ops26.shouldUseWipeDevice(26))
        assertFalse(ops32.shouldUseWipeDevice(32))
        assertFalse(ops33.shouldUseWipeDevice(33))
    }
}
