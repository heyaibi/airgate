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

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airgate.data.crypto.PinManager
import com.airgate.domain.model.AppConfig
import com.airgate.testutil.crypto.AndroidKeyStoreRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * JVM (Robolectric) verification of the exact-alarm arming gate: the watchdog can
 * only be *newly* enabled while the app holds the SCHEDULE_EXACT_ALARM special
 * access ("Alarms & reminders" on Android 12+), because the precise wipe
 * countdown is armed as an exact alarm. Uses a throwaway prefs file so no real
 * app state is touched.
 *
 * The granted branch is exercised against the simulated AlarmManager state (the
 * same set [android.app.AlarmManager.canScheduleExactAlarms] consults). The denied
 * branch is exercised by injecting the same decision a revoked access produces.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExactAlarmArmingGateTest {

    @get:Rule
    val androidKeyStoreRule = AndroidKeyStoreRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Test
    fun arming_isAccepted_whenExactAlarmAccessIsGranted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val repository = repository(provider = { SecurityStateRepository.canScheduleExactAlarms(context) })

        val requested = repository.saveConfig(AppConfig(isEnabled = true))

        assertTrue("arming must be accepted while exact alarms are schedulable", requested.isEnabled)
        assertTrue(repository.getConfig().isEnabled)
    }

    @Test
    fun arming_isRefused_whenExactAlarmAccessIsDenied() {
        // Mirrors a revoked/denied "Alarms & reminders" access: the provider
        // returns false, so the enable request must be coerced back to disabled.
        val repository = repository(provider = { false })

        val requested = repository.saveConfig(AppConfig(isEnabled = true))

        assertFalse("arming must be refused without exact-alarm access", requested.isEnabled)
        assertFalse(repository.getConfig().isEnabled)
    }

    @Test
    fun disabling_isAlwaysAllowed_whenExactAlarmAccessIsDenied() {
        val repository = repository(provider = { false })
        repository.saveConfig(AppConfig(isEnabled = true, graceWindowSeconds = 60))

        val effective = repository.saveConfig(AppConfig(isEnabled = false, graceWindowSeconds = 60))

        assertFalse("disabling must never be blocked", effective.isEnabled)
    }

    @Test
    fun arming_aStaleEnabledInstall_staysEnabledWhenAccessIsRevoked() {
        // The gate is transition-only: an already-armed device is not disarmed by
        // a later revocation (the countdown reconciliation fails closed instead).
        val prefs = context.getSharedPreferences(
            "exact_alarm_gate_stale_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val armed = SecurityStateRepository(prefs, null, { true }, { true }, { true })
        armed.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        armed.saveConfig(AppConfig(isEnabled = true, graceWindowSeconds = 60))
        assertTrue(armed.getConfig().isEnabled)

        // A fresh repository over the same prefs, with exact-alarm access now gone.
        val withAccessGone = SecurityStateRepository(prefs, null, { true }, { true }, { false })
        val rePersisted = withAccessGone.saveConfig(AppConfig(isEnabled = true, graceWindowSeconds = 60))

        assertTrue(
            "an already-armed device must not be silently disarmed by a later revocation",
            rePersisted.isEnabled
        )
    }

    @Test
    fun canScheduleExactAlarms_reflectsTheRealAlarmManagerState() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val repository = SecurityStateRepository(context)

        assertTrue(
            "canScheduleExactAlarms must equal the real AlarmManager state (granted)",
            repository.canScheduleExactAlarms()
        )

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(
            "canScheduleExactAlarms must equal the real AlarmManager state (denied)",
            repository.canScheduleExactAlarms()
        )
    }

    @Test
    fun canScheduleExactAlarms_helperMirrorsTheAlarmManager() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(true, SecurityStateRepository.canScheduleExactAlarms(context))

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(false, SecurityStateRepository.canScheduleExactAlarms(context))
    }

    private fun repository(provider: () -> Boolean): SecurityStateRepository {
        val prefs = context.getSharedPreferences(
            "exact_alarm_arming_gate_${System.currentTimeMillis()}",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repository = SecurityStateRepository(prefs, null, { true }, { true }, provider)
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), PinManager.DEFAULT_ITERATIONS, PinManager.DEFAULT_ALGORITHM)
        return repository
    }
}
