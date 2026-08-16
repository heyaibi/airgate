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
import com.airgate.service.PostureAudit
import com.airgate.service.SafetyNetScheduler

/**
 * Fires on each AlarmManager tick of the periodic posture audit.
 * Re-arms the next alarm (fresh config read each time) and runs the
 * full audit off the main thread, since it performs binder/file/query work.
 */
class SafetyNetReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.airgate.action.SAFETY_NET_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                SafetyNetScheduler.schedule(appContext)
                PostureAudit(appContext).executeCheck()
            } catch (e: Exception) {
                // Never let an audit failure kill the alarm loop
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
