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
import android.os.Build
import android.os.SystemClock
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.domain.model.AppConfig
import com.airgate.receiver.GraceWipeReceiver

/**
 * Owns the AlarmManager scheduling of the delayed wipe. The wipe fires after the
 * configured grace window has elapsed and is cancelled via [cancel] when the owner
 * disarms with the PIN.
 *
 * The wipe deadline runs on the monotonic clock ([SystemClock.elapsedRealtime])
 * and is scheduled with an [AlarmManager.ELAPSED_REALTIME_WAKEUP] exact alarm, so
 * an attacker who rolls the wall clock back cannot make the countdown "not reached
 * yet" and drop the wipe. The receiver compares against the same clock.
 *
 * The deadline is only ever armed as an exact alarm. A precise wipe countdown is a
 * security guarantee, so an inexact [AlarmManager.setAndAllowWhileIdle] fallback is
 * never used: when exact scheduling is unavailable the scheduler reports
 * [WipeScheduleResult.EXACT_UNAVAILABLE] and schedules nothing, and the caller is
 * expected to fail closed (execute the wipe) instead of running a countdown whose
 * deadline the platform could silently push out.
 */
open class GraceWipeScheduler(
    private val context: Context,
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {

    enum class WipeScheduleResult {
        /** The wipe was armed as an exact alarm that will fire at the deadline. */
        EXACT_SCHEDULED,

        /** Exact scheduling is not possible right now; nothing was scheduled. */
        EXACT_UNAVAILABLE,

        /** Exact scheduling was attempted but the platform rejected it; nothing was scheduled. */
        SCHEDULING_FAILED
    }

    companion object {
        private const val GRACE_WIPE_REQUEST_CODE = 3001

        /** The absolute monotonic trigger for a wipe [delayMs] from [nowMs]. */
        fun computeTriggerAt(nowMs: Long, delayMs: Long): Long = nowMs + delayMs
    }

    /**
     * Whether exact-alarm scheduling is currently possible. The precise wipe
     * countdown is only ever armed through this capability, so every arming,
     * re-arm, and reconciliation path consults it and fails closed when false.
     */
    open fun canScheduleExactAlarms(): Boolean =
        SecurityStateRepository.canScheduleExactAlarms(context)

    /**
     * Schedules the actual wipe to fire after the configured grace window has elapsed.
     *
     * @return [WipeScheduleResult.EXACT_SCHEDULED] when the exact alarm was armed;
     *   anything else means nothing was scheduled and the caller must fail closed.
     */
    open fun schedule(config: AppConfig): WipeScheduleResult =
        scheduleDelay(config.graceWindowSeconds * 1000L)

    /**
     * Schedules the wipe to fire [delayMs] from now on the monotonic clock. Used
     * both for the initial grace window and when reconciling a countdown that
     * survived a reboot: only the remaining delay is re-armed, so the absolute
     * deadline never moves.
     *
     * @return [WipeScheduleResult.EXACT_SCHEDULED] when the exact alarm was armed;
     *   anything else means nothing was scheduled and the caller must fail closed.
     */
    open fun scheduleDelay(delayMs: Long): WipeScheduleResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return WipeScheduleResult.EXACT_UNAVAILABLE
        val triggerAt = computeTriggerAt(elapsedRealtimeProvider(), delayMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            return WipeScheduleResult.EXACT_UNAVAILABLE
        }
        val pendingIntent = graceWipePendingIntent(triggerAt)
        return runCatching { scheduleExact(alarmManager, triggerAt, pendingIntent) }
            .getOrElse { WipeScheduleResult.SCHEDULING_FAILED }
    }

    /**
     * Performs the exact-alarm scheduling call. Split out as an overridable seam
     * so tests can pin the failure path deterministically without the real
     * AlarmManager. Never falls back to an inexact alarm: a failed exact arm is
     * caught by [scheduleDelay] and reported as [WipeScheduleResult.SCHEDULING_FAILED],
     * scheduling nothing.
     */
    internal open fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ): WipeScheduleResult {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
        )
        return WipeScheduleResult.EXACT_SCHEDULED
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
