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

package com.airgate.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.airgate.domain.model.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
/**
 * JVM (Robolectric) verification of the grace-wipe scheduler's exact-alarm
 * contract: the precise wipe deadline is only ever armed through an exact alarm,
 * and when exact scheduling is unavailable the scheduler arms nothing and reports
 * it — it never silently falls back to an inexact alarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraceWipeSchedulerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun shadowAlarmManager(): ShadowAlarmManager = shadowOf(alarmManager)

    private fun scheduler(elapsed: () -> Long = { 0L }) = GraceWipeScheduler(context, elapsed)

    private fun scheduledAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowAlarmManager().scheduledAlarms

    // --- canScheduleExactAlarms ---

    @Test
    fun canScheduleExactAlarms_returnsTrueWhenExactSchedulingIsAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertTrue(scheduler().canScheduleExactAlarms())
    }

    @Test
    fun canScheduleExactAlarms_returnsFalseWhenExactSchedulingIsDenied() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertTrue(!scheduler().canScheduleExactAlarms())
    }

    // --- scheduleDelay: exact scheduling available ---

    @Test
    fun scheduleDelay_armsAnExactAlarmAtTheMonotonicTrigger() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val now = 100_000L

        val result = scheduler { now }.scheduleDelay(60_000L)

        assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED, result)
        val alarms = scheduledAlarms()
        assertEquals(1, alarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarms[0].type)
        assertEquals(160_000L, alarms[0].triggerAtTime)
        assertTrue("the wipe deadline must run while idle", alarms[0].allowWhileIdle)
    }

    @Test
    fun scheduleDelay_usesTheSuppliedMonotonicClockForTheTrigger() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val uptime = 5_000_000_000L

        val result = scheduler { uptime }.scheduleDelay(1_000L)

        assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED, result)
        assertEquals(uptime + 1_000L, scheduledAlarms()[0].triggerAtTime)
    }

    // --- scheduleDelay: exact scheduling unavailable ---

    @Test
    fun scheduleDelay_armsNothingWhenExactSchedulingIsDenied() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = scheduler().scheduleDelay(60_000L)

        assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_UNAVAILABLE, result)
        assertTrue(
            "an inexact fallback must never be scheduled",
            scheduledAlarms().isEmpty()
        )
    }

    @Test
    fun scheduleDelay_returnsUnavailableWhenAlarmManagerIsMissing() {
        val noAlarmContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.ALARM_SERVICE) null else super.getSystemService(name)
        }

        val result = GraceWipeScheduler(noAlarmContext, { 0L }).scheduleDelay(60_000L)

        assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_UNAVAILABLE, result)
    }

    // --- scheduleDelay: exact scheduling call fails ---

    @Test
    fun scheduleDelay_reportsSchedulingFailedWhenTheExactCallThrows() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val failingScheduler = object : GraceWipeScheduler(context, { 0L }) {
            override fun scheduleExact(
                alarmManager: AlarmManager,
                triggerAt: Long,
                pendingIntent: PendingIntent
            ): GraceWipeScheduler.WipeScheduleResult = throw RuntimeException("boom")
        }

        val result = failingScheduler.scheduleDelay(60_000L)

        assertEquals(GraceWipeScheduler.WipeScheduleResult.SCHEDULING_FAILED, result)
        assertTrue("a failed exact arm must not fall back to anything", scheduledAlarms().isEmpty())
    }

    // --- schedule(config) ---

    @Test
    fun schedule_armsTheConfiguredGraceWindow() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = scheduler { 50_000L }

        val result = scheduler.schedule(AppConfig(graceWindowSeconds = 30))

        assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED, result)
        val alarms = scheduledAlarms()
        assertEquals(1, alarms.size)
        assertEquals(50_000L + 30_000L, alarms[0].triggerAtTime)
    }

    @Test
    fun schedule_reportsUnavailableWhenExactSchedulingIsDenied() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = scheduler().schedule(AppConfig(graceWindowSeconds = 30))

        assertEquals(GraceWipeScheduler.WipeScheduleResult.EXACT_UNAVAILABLE, result)
        assertTrue(scheduledAlarms().isEmpty())
    }

    // --- cancel ---

    @Test
    fun cancel_removesTheScheduledWipeAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = scheduler()
        scheduler.scheduleDelay(60_000L)
        assertEquals(1, scheduledAlarms().size)

        scheduler.cancel()

        assertTrue(scheduledAlarms().isEmpty())
    }
}
