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

package com.airgate.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.SecurityState
import com.airgate.engine.GraceWipeScheduler
import com.airgate.engine.ThreatEngine

/**
 * Executes the scheduled wipe once its monotonic deadline has elapsed. The
 * deadline guard runs on [SystemClock.elapsedRealtime] (the clock is injectable
 * so the guard is pin-testable in a pure JVM): an attacker who rolls the wall
 * clock back cannot make an elapsed deadline look unreached, because the guard
 * never consults the wall clock.
 */
open class GraceWipeReceiver(
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val schedulerProvider: (Context) -> GraceWipeScheduler = { GraceWipeScheduler(it) },
    private val rearmLogger: (Long) -> Unit = { remaining ->
        Log.w(TAG, "Grace-wipe alarm fired early; re-arming for ${remaining}ms until the original deadline")
    }
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "GraceWipeReceiver"

        const val ACTION = "com.airgate.action.GRACE_WIPE"
        const val EXTRA_DEADLINE = "com.airgate.extra.WIPE_DEADLINE"

        /**
         * Whether the wipe must be skipped because its deadline has not elapsed.
         * The deadline and [nowMs] are both monotonic ([SystemClock.elapsedRealtime])
         * values, so a wall-clock rollback cannot make an elapsed deadline look
         * unreached. A zero deadline means "no deadline recorded" and never blocks.
         */
        fun shouldSkipWipe(deadline: Long, nowMs: Long): Boolean =
            deadline > 0L && nowMs < deadline
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L)
        // The wipe executes a Dhizuku binder transaction, which may block while the
        // Dhizuku server is slow or wedged. onReceive must never run it on the main
        // thread: goAsync keeps the broadcast alive while a background thread runs
        // the deadline guard and the wipe, then finishes the result.
        val pendingResult = goAsync()
        Thread {
            try {
                val repository = createRepository(context)
                executeIfDeadlineReached(context, repository, deadline, elapsedRealtimeProvider())
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /**
     * Executes the scheduled wipe only when the countdown is genuinely over: the
     * app is still armed, the state is still [SecurityState.COUNTDOWN_WIPE], and
     * the deadline has elapsed on [now] (the monotonic clock). All comparisons
     * use [now], never the wall clock.
     *
     * If the alarm fires before the deadline (early delivery), the one-shot alarm
     * is already spent, so instead of dropping the wipe the remaining delay is
     * re-armed on the monotonic clock: the absolute deadline never moves, and a
     * wipe that fires early can never be lost.
     */
    internal fun executeIfDeadlineReached(
        context: Context,
        repository: SecurityStateRepository,
        deadline: Long,
        now: Long
    ) {
        // A scheduled wipe is only valid if the app is still armed, the countdown is
        // still pending (owner never disarmed with the PIN), and the grace window has
        // actually elapsed. Guards against stale alarms wiping after disarm/re-arm.
        val config = repository.getConfig()
        if (!config.isEnabled) return
        if (repository.getSecurityState() != SecurityState.COUNTDOWN_WIPE) return
        if (shouldSkipWipe(deadline, now)) {
            if (!rearmRemainingDelay(context, deadline, now)) {
                // The re-arm could not be made exact: the remaining countdown can no
                // longer be guaranteed, so fail closed and wipe now rather than leave
                // a deadline the platform may never fire. The audit alarm records the
                // exact-alarm-loss origin so it is not mistaken for a normal wipe.
                executeWipeNow(context, repository, exactAlarmLost = true)
            }
            return
        }

        executeWipeNow(context, repository)
    }

    /**
     * The alarm fired before the deadline. The wipe must still happen, so re-arm
     * it for exactly the time remaining until the original deadline. A zero or
     * negative remaining delay means the deadline is actually due and needs no
     * re-arm.
     *
     * @return true when the remaining delay was armed exactly (or there was nothing
     *   left to re-arm); false when the precise re-arm could not be scheduled and
     *   the caller must fail closed.
     */
    internal fun rearmRemainingDelay(context: Context, deadline: Long, now: Long): Boolean {
        val remaining = deadline - now
        if (remaining <= 0L) return true
        rearmLogger(remaining)
        return schedulerProvider(context.applicationContext).scheduleDelay(remaining) ==
            GraceWipeScheduler.WipeScheduleResult.EXACT_SCHEDULED
    }

    private fun executeWipeNow(
        context: Context,
        repository: SecurityStateRepository,
        exactAlarmLost: Boolean = false
    ) {
        val dhizukuManager = DhizukuManager(context.applicationContext)
        val threatEngine = ThreatEngine(context.applicationContext, repository, dhizukuManager)
        threatEngine.executeWipeState(graceElapsed = true, exactAlarmLost = exactAlarmLost)
    }

    /**
     * Repository construction seam: lets instrumented tests inject a throwaway
     * repository over isolated prefs instead of the app's real persisted state.
     */
    internal open fun createRepository(context: Context): SecurityStateRepository =
        SecurityStateRepository(context.applicationContext)
}
