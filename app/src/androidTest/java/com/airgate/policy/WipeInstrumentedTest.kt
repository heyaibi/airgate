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

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.domain.model.WipeResult
import com.airgate.engine.ThreatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the wipe contract.
 *
 * The emulator has no Dhizuku device-owner authority, so a live wipe attempt
 * exercises the real platform path and must fail closed (REJECTED, never
 * WIPING); a dry-run wipe must report the simulation and drive the WIPING
 * screen. Both prove the app never claims an erasure the platform did not
 * accept.
 *
 * All tests use a throwaway SharedPreferences store so no real app state is
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class WipeInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun throwawayRepository(): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "wipe_instrumented_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        return repository
    }

    @Test
    fun liveWipeWithoutDeviceOwnerAuthority_isRejectedNotFabricated() {
        val repository = throwawayRepository()
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = false, graceWindowSeconds = 0))
        val engine = ThreatEngine(context, repository, DhizukuManager(context))

        engine.executeWipeState()

        // The real platform path cannot accept the wipe here, so the device must
        // never be shown as WIPING while its data is still present.
        assertNotEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(SecurityState.ALARM_ACTIVE, repository.getSecurityState())
    }

    @Test
    fun liveWipeResult_isRejectedOnDevice() {
        val repository = throwawayRepository()
        val controller = WipeController(context, DhizukuManager(context))

        val result = controller.executeWipe(AppConfig(dryRunMode = false))

        assertEquals(WipeResult.REJECTED, result)
    }

    @Test
    fun dryRunWipe_entersTheSimulationState() {
        val repository = throwawayRepository()
        repository.saveConfig(AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 0))
        val engine = ThreatEngine(context, repository, DhizukuManager(context))

        engine.executeWipeState()

        // A dry-run wipe is a truthful simulation: the WIPING state drives the
        // SimulatedWipeScreen without any destructive call having run.
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
    }

    @Test
    fun dryRunWipeResult_isSimulatedOnDevice() {
        val repository = throwawayRepository()
        val controller = WipeController(context, DhizukuManager(context))

        val result = controller.executeWipe(AppConfig(dryRunMode = true))

        assertEquals(WipeResult.SIMULATED, result)
    }
}
