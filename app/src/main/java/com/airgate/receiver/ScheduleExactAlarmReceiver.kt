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

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.airgate.service.WatchdogService

/**
 * Reacts to the system granting the SCHEDULE_EXACT_ALARM special access.
 *
 * The system deletes every exact alarm and kills the app process when the access
 * is revoked, and it sends no broadcast on revocation — the loss is only noticed
 * on the next process start via the persisted wipe-deadline reconciliation. It
 * does send [AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED]
 * when the access is granted, which is the one moment the app is alive again and
 * can re-arm a countdown that lost its alarm while the access was revoked.
 *
 * This receiver only listens to a protected system broadcast that ordinary apps
 * cannot spoof, so it stays non-exported like the other app-internal receivers.
 */
open class ScheduleExactAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        // The service's start-up reconciliation re-arms any pending wipe countdown
        // for the remaining grace with an exact alarm (or fails closed to the wipe
        // if the access was already revoked again). It also re-checks the capability
        // itself, exactly as the platform requires after this broadcast.
        startWatchdog(context)
    }

    /** Seam for tests; production starts the watchdog service. */
    internal open fun startWatchdog(context: Context) {
        WatchdogService.startService(context)
    }
}
