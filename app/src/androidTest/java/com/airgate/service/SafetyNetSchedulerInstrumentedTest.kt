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

package com.airgate.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.receiver.SafetyNetReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that [SafetyNetScheduler] interacts correctly with the
 * real [android.app.AlarmManager] and [PendingIntent]:
 *
 * - The alarm is scheduled when enabled and not already pending.
 * - Repeated [SafetyNetScheduler.schedule] calls do not replace the existing alarm.
 * - The alarm is removed by [SafetyNetScheduler.cancel].
 * - The alarm persists across simulated service restarts.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNetSchedulerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("airgate_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fullyCancelAlarm()
    }

    @After
    fun tearDown() {
        fullyCancelAlarm()
        prefs.edit().clear().commit()
    }

    /**
     * Cancels both the AlarmManager alarm and the lingering PendingIntent.
     * SafetyNetScheduler.cancel() only calls alarmManager.cancel() which removes
     * the scheduled alarm but leaves the PendingIntent registered in the system —
     * PendingIntent.getBroadcast with FLAG_NO_CREATE still finds it. This helper
     * also calls PendingIntent.cancel() to fully clean up between tests.
     */
    private fun fullyCancelAlarm() {
        SafetyNetScheduler.cancel(context)
        pendingIntentSnapshot()?.cancel()
    }

    @Test
    fun schedule_setsAlarmOnRealDevice() {
        armWatchdog()

        assertFalse("alarm must not be pending before schedule", SafetyNetScheduler.checkIsScheduled(context))

        SafetyNetScheduler.schedule(context)

        assertTrue("alarm must be pending after schedule", SafetyNetScheduler.checkIsScheduled(context))
    }

    @Test
    fun schedule_idempotentOnRealDevice() {
        armWatchdog()

        SafetyNetScheduler.schedule(context)
        assertTrue(SafetyNetScheduler.checkIsScheduled(context))
        val piBefore = pendingIntentSnapshot()

        SafetyNetScheduler.schedule(context)
        SafetyNetScheduler.schedule(context)

        val piAfter = pendingIntentSnapshot()
        assertNotNull(piAfter)
        assertEquals(
            "PendingIntent must not change on repeated schedule",
            piBefore.hashCode(), piAfter.hashCode()
        )
    }

    @Test
    fun cancel_removesAlarmOnRealDevice() {
        armWatchdog()

        SafetyNetScheduler.schedule(context)
        assertTrue(SafetyNetScheduler.checkIsScheduled(context))

        fullyCancelAlarm()

        assertFalse("alarm must not be pending after cancel", SafetyNetScheduler.checkIsScheduled(context))
    }

    @Test
    fun schedule_noOpWhenDisabledOnRealDevice() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repo.saveConfig(AppConfig(isEnabled = false, safetyNetIntervalMinutes = 5))

        SafetyNetScheduler.schedule(context)

        assertFalse("alarm must not be pending when disabled", SafetyNetScheduler.checkIsScheduled(context))
    }

    @Test
    fun alarmPersistsAcrossServiceRestart() {
        armWatchdog()

        SafetyNetScheduler.schedule(context)
        assertTrue(SafetyNetScheduler.checkIsScheduled(context))

        SafetyNetScheduler.schedule(context)
        SafetyNetScheduler.schedule(context)

        assertTrue(
            "alarm must still be pending after simulated service restarts",
            SafetyNetScheduler.checkIsScheduled(context)
        )
    }

    @Test
    fun schedule_resumesAfterCancelOnRealDevice() {
        armWatchdog()

        SafetyNetScheduler.schedule(context)
        assertTrue(SafetyNetScheduler.checkIsScheduled(context))

        fullyCancelAlarm()
        assertFalse(SafetyNetScheduler.checkIsScheduled(context))

        SafetyNetScheduler.schedule(context)
        assertTrue("alarm must be resumable after cancel", SafetyNetScheduler.checkIsScheduled(context))
    }

    @Test
    fun schedule_usesCorrectPendingIntentParameters() {
        armWatchdog()

        SafetyNetScheduler.schedule(context)

        val intent = Intent(context, SafetyNetReceiver::class.java).apply {
            action = SafetyNetReceiver.ACTION
        }
        val pi = PendingIntent.getBroadcast(
            context, 4001, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("PendingIntent must exist with correct parameters", pi)
    }

    @Test
    fun multipleScheduleCancelCycles() {
        armWatchdog()

        repeat(5) {
            SafetyNetScheduler.schedule(context)
            assertTrue("alarm must be pending after schedule cycle $it", SafetyNetScheduler.checkIsScheduled(context))
            fullyCancelAlarm()
            assertFalse("alarm must not be pending after cancel cycle $it", SafetyNetScheduler.checkIsScheduled(context))
        }

        SafetyNetScheduler.schedule(context)
        assertTrue("alarm must be pending after final schedule", SafetyNetScheduler.checkIsScheduled(context))
    }

    private fun armWatchdog() {
        val repo = SecurityStateRepository(prefs)
        repo.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        repo.saveConfig(AppConfig(isEnabled = true, safetyNetIntervalMinutes = 5))
    }

    private fun pendingIntentSnapshot(): PendingIntent? {
        val intent = Intent(context, SafetyNetReceiver::class.java).apply {
            action = SafetyNetReceiver.ACTION
        }
        return PendingIntent.getBroadcast(
            context, 4001, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
