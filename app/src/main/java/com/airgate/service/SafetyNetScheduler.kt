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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.receiver.SafetyNetReceiver
import kotlin.math.max

/**
 * Schedules the periodic SafetyNet / posture audit via AlarmManager.
 * A one-shot exact alarm is re-armed on every fire so config changes
 * (interval, enabled state) take effect on the next cycle without
 * touching the scheduled alarm from a service restart.
 */
object SafetyNetScheduler {

    private const val REQUEST_CODE = 4001
    private const val MIN_INTERVAL_MS = 30_000L

    fun schedule(context: Context) {
        val repository = SecurityStateRepository(context.applicationContext)
        val config = repository.getConfig()
        if (!config.isEnabled) return

        val intervalMs = max(config.safetyNetIntervalMinutes * 60_000L, MIN_INTERVAL_MS)
        val triggerAt = System.currentTimeMillis() + intervalMs

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntent(context)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: Exception) {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SafetyNetReceiver::class.java).apply {
            action = SafetyNetReceiver.ACTION
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
