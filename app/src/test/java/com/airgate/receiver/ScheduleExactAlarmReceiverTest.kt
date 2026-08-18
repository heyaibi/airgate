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
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The exact-alarm grant receiver only reacts to the one protected system action
 * that means "the SCHEDULE_EXACT_ALARM access was granted", and responds by
 * starting the watchdog (whose start-up reconciliation re-arms any pending wipe
 * countdown with an exact alarm). Any other action is ignored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleExactAlarmReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private class RecordingReceiver : ScheduleExactAlarmReceiver() {
        val starts = mutableListOf<Context>()
        override fun startWatchdog(context: Context) {
            starts.add(context)
        }
    }

    @Test
    fun grantAction_startsTheWatchdog() {
        val receiver = RecordingReceiver()

        receiver.onReceive(
            context,
            Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        )

        assertEquals(1, receiver.starts.size)
    }

    @Test
    fun unrelatedAction_doesNotStartTheWatchdog() {
        val receiver = RecordingReceiver()

        receiver.onReceive(context, Intent("com.example.SOMETHING_ELSE"))

        assertTrue(receiver.starts.isEmpty())
    }
}
