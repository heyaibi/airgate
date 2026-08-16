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

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.dhizuku.ShadowDevicePolicyManagerForWipe.WipeCall
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.WipeResult
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Robolectric verification of the API 34+ device-owner wipe path: the literal
 * `DevicePolicyManager.wipeDevice(flags)` invocation is exercised against a
 * shadow, asserting the ACCEPTED-after-success and REJECTED-after-throw branches
 * that the JVM suite can only reach through the test-wrapper seam. The shadowed
 * DPM is injected through [DhizukuDpmBridge.wrappedDpm]'s resolution, and each
 * branch asserts that the platform `wipeDevice` call — not `wipeData` — was the
 * invocation made.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowDevicePolicyManagerForWipe::class], sdk = [34, 36])
class DhizukuPlatformWipeApi34Test {

    private lateinit var shadow: ShadowDevicePolicyManagerForWipe
    private lateinit var ops: DhizukuDestructiveOps

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadow = Shadow.extract(dpm)
        val connection = DhizukuConnection(context, null)
        val bridge = DhizukuDpmBridge(context, connection, null, dpm)
        ops = DhizukuDestructiveOps(bridge, sdkInt = Build.VERSION.SDK_INT)
    }

    @Test
    fun `a successful wipeDevice platform call is reported as ACCEPTED`() {
        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = false))

        assertEquals(WipeResult.ACCEPTED, result)
        assertEquals(0x8, shadow.lastWipeFlags)
        assertEquals(WipeCall.WIPE_DEVICE, shadow.lastWipeCall)
    }

    @Test
    fun `a throwing wipeDevice platform call is reported as REJECTED`() {
        shadow.wipeThrows = true

        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = false))

        assertEquals(WipeResult.REJECTED, result)
        assertEquals(0x8, shadow.lastWipeFlags)
        assertEquals(WipeCall.WIPE_DEVICE, shadow.lastWipeCall)
    }

    @Test
    fun `dry-run never invokes the platform wipe`() {
        val result = ops.wipeDevice(flags = 0x8, config = AppConfig(dryRunMode = true))

        assertEquals(WipeResult.SIMULATED, result)
        assertEquals(-1, shadow.lastWipeFlags)
        assertEquals(null, shadow.lastWipeCall)
    }
}