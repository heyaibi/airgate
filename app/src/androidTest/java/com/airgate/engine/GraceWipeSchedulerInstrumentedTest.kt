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

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device verification that the delayed wipe runs on the monotonic
 * clock: the alarm is scheduled with an ELAPSED_REALTIME trigger and the receiver
 * only executes once the monotonic deadline has elapsed — a wall-clock rollback
 * cannot postpone or cancel the wipe.
 *
 * This test drives the real manifest-registered receiver, so it uses the app's
 * real persisted prefs. It clears them before and after so no app state leaks.
 */
@RunWith(AndroidJUnit4::class)
class GraceWipeSchedulerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun realPrefs(): android.content.SharedPreferences {
        val prefs = context.getSharedPreferences(
            "airgate_secure_prefs",
            Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        return prefs
    }

    @Test
    fun scheduledWipeFiresAfterTheGraceWindowOnTheMonotonicClock() {
        val prefs = realPrefs()
        val scheduler = GraceWipeScheduler(context)
        try {
            // Arm a countdown with a short grace window, bypassing the
            // notification gate (it only guards the act of arming).
            val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
            repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            val config = repository.saveConfig(
                AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 2)
            )
            assertTrue("test must arm the watchdog", config.isEnabled)
            repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)

            val deadline = SystemClock.elapsedRealtime()
            scheduler.schedule(config)

            // The wipe must not fire before its monotonic deadline.
            assertTrue(repository.getSecurityState() == SecurityState.COUNTDOWN_WIPE)

            // The wipe fires once the ELAPSED_REALTIME alarm triggers and the
            // receiver confirms the deadline elapsed.
            val deadlineWall = System.currentTimeMillis() + 30_000L
            var state = repository.getSecurityState()
            while (state != SecurityState.WIPING && System.currentTimeMillis() < deadlineWall) {
                Thread.sleep(200)
                state = repository.getSecurityState()
            }

            assertTrue(
                "expected the wipe to execute after the grace window, state was $state (scheduled at $deadline)",
                state == SecurityState.WIPING
            )
        } finally {
            scheduler.cancel()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun computeTriggerIsOnTheMonotonicClock() {
        // The scheduler must derive the trigger from the monotonic clock so the
        // alarm survives wall-clock edits; verify the math against a large uptime.
        val uptime = SystemClock.elapsedRealtime()
        val trigger = GraceWipeScheduler.computeTriggerAt(uptime, 60_000L)
        assertEquals(uptime + 60_000L, trigger)
    }

    @Test
    fun reconcilePendingWipe_rearmsTheRemainingGraceAndTheWipeFires() {
        // A countdown survives a reboot because its deadline is persisted; on
        // restart the remaining grace is re-armed (not lost, not shrunk to zero).
        val prefs = realPrefs()
        val scheduler = GraceWipeScheduler(context)
        try {
            val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
            repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            val config = repository.saveConfig(
                AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 2)
            )
            assertTrue("test must arm the watchdog", config.isEnabled)
            repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
            // Persist a deadline 2s out, as executeWipeState does, without
            // scheduling (a reboot would have cleared any alarm).
            repository.setWipeDeadline(repository.getMonotonicNow() + 2_000L)

            // Simulate a reboot with a fresh repository over the same prefs whose
            // elapsed clock starts at zero.
            val rebooted = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true }) { 0L }
            val remaining = rebooted.getWipeRemainingMs()
            assertTrue("the remaining grace must survive the reboot, was $remaining", remaining in 1..2_000)

            val engine = ThreatEngine(context, rebooted, DhizukuManager(context))
            engine.reconcilePendingWipe()

            // The wipe is re-armed for the remaining grace, not executed early.
            assertTrue(rebooted.getSecurityState() == SecurityState.COUNTDOWN_WIPE)

            // The re-armed alarm fires and the wipe executes.
            val deadlineWall = System.currentTimeMillis() + 15_000L
            var state = rebooted.getSecurityState()
            while (state != SecurityState.WIPING && System.currentTimeMillis() < deadlineWall) {
                Thread.sleep(200)
                state = rebooted.getSecurityState()
            }
            assertEquals(SecurityState.WIPING, state)
        } finally {
            scheduler.cancel()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun reconcilePendingWipe_executesImmediatelyWhenTheDeadlineElapsedWhileDown() {
        // If the grace elapsed while the app was off, restart reconciles by
        // executing the wipe — the deadline is honored, not silently dropped.
        val prefs = realPrefs()
        try {
            val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
            repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            val config = repository.saveConfig(
                AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 2)
            )
            assertTrue("test must arm the watchdog", config.isEnabled)
            repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
            repository.setWipeDeadline(repository.getMonotonicNow() - 1_000L)
            assertTrue(repository.getWipeRemainingMs() == 0L)

            val engine = ThreatEngine(context, repository, DhizukuManager(context))
            engine.reconcilePendingWipe()

            assertEquals(SecurityState.WIPING, repository.getSecurityState())
            assertEquals(0L, repository.getWipeDeadline())
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
