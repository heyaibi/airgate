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
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.SecurityState
import com.airgate.engine.ThreatEngine

/**
 * Executes the scheduled wipe once its monotonic deadline has elapsed. The
 * deadline guard runs on [SystemClock.elapsedRealtime] (the clock is injectable
 * so the guard is pin-testable in a pure JVM): an attacker who rolls the wall
 * clock back cannot make an elapsed deadline look unreached, because the guard
 * never consults the wall clock.
 */
open class GraceWipeReceiver(
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : BroadcastReceiver() {

    companion object {
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
        val repository = createRepository(context)
        executeIfDeadlineReached(context, repository, deadline, elapsedRealtimeProvider())
    }

    /**
     * Executes the scheduled wipe only when the countdown is genuinely over: the
     * app is still armed, the state is still [SecurityState.COUNTDOWN_WIPE], and
     * the deadline has elapsed on [now] (the monotonic clock). All comparisons
     * use [now], never the wall clock.
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
        if (shouldSkipWipe(deadline, now)) return

        val dhizukuManager = DhizukuManager(context.applicationContext)
        val threatEngine = ThreatEngine(context.applicationContext, repository, dhizukuManager)
        threatEngine.executeWipeState(graceElapsed = true)
    }

    /**
     * Repository construction seam: lets instrumented tests inject a throwaway
     * repository over isolated prefs instead of the app's real persisted state.
     */
    internal open fun createRepository(context: Context): SecurityStateRepository =
        SecurityStateRepository(context.applicationContext)
}
