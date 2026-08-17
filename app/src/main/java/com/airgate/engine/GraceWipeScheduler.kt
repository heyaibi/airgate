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
import android.content.Intent
import android.os.SystemClock
import com.airgate.domain.model.AppConfig
import com.airgate.receiver.GraceWipeReceiver

/**
 * Owns the AlarmManager scheduling of the delayed wipe. The wipe fires after the
 * configured grace window has elapsed and is cancelled via [cancel] when the owner
 * disarms with the PIN.
 *
 * The wipe deadline runs on the monotonic clock ([SystemClock.elapsedRealtime])
 * and is scheduled with an [AlarmManager.ELAPSED_REALTIME_WAKEUP] alarm, so an
 * attacker who rolls the wall clock back cannot make the countdown "not reached
 * yet" and drop the wipe. The receiver compares against the same clock.
 */
open class GraceWipeScheduler(
    private val context: Context,
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {

    companion object {
        private const val GRACE_WIPE_REQUEST_CODE = 3001

        /** The absolute monotonic trigger for a wipe [delayMs] from [nowMs]. */
        fun computeTriggerAt(nowMs: Long, delayMs: Long): Long = nowMs + delayMs
    }

    /**
     * Schedules the actual wipe to fire after the configured grace window has elapsed.
     */
    open fun schedule(config: AppConfig) {
        scheduleDelay(config.graceWindowSeconds * 1000L)
    }

    /**
     * Schedules the wipe to fire [delayMs] from now on the monotonic clock. Used
     * both for the initial grace window and when reconciling a countdown that
     * survived a reboot: only the remaining delay is re-armed, so the absolute
     * deadline never moves.
     */
    open fun scheduleDelay(delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = computeTriggerAt(elapsedRealtimeProvider(), delayMs)
        val pendingIntent = graceWipePendingIntent(triggerAt)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: Exception) {
            // Fall back to a non-exact alarm if exact-alarm scheduling is unavailable
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent) }
        }
    }

    open fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(graceWipePendingIntent(0L))
        graceWipePendingIntent(0L).cancel()
    }

    private fun graceWipePendingIntent(triggerAtMillis: Long): PendingIntent {
        val intent = Intent(context, GraceWipeReceiver::class.java).apply {
            action = GraceWipeReceiver.ACTION
            putExtra(GraceWipeReceiver.EXTRA_DEADLINE, triggerAtMillis)
        }
        return PendingIntent.getBroadcast(
            context, GRACE_WIPE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
