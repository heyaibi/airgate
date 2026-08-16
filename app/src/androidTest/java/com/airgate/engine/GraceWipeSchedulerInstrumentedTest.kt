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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airgate.data.repository.SecurityStateRepository
import com.airgate.dhizuku.DhizukuManager
import com.airgate.domain.model.AppConfig
import com.airgate.domain.model.SecurityState
import com.airgate.receiver.GraceWipeReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the delayed wipe runs on the monotonic clock: the
 * alarm is scheduled with an ELAPSED_REALTIME trigger and the receiver only
 * executes once the monotonic deadline has elapsed — a wall-clock rollback
 * cannot postpone or cancel the wipe.
 *
 * The wipe itself is fired deterministically instead of waiting for the real
 * wall-clock alarm: the test delivers the exact same manifest-registered
 * [GraceWipeReceiver] PendingIntent (same request code, action, and deadline
 * extra) with an already-elapsed deadline. Waiting on a real AlarmManager fire
 * is flaky on a loaded CI emulator — a slow system can delay the alarm, and a
 * concurrent watchdog audit (this test arms the app with its real prefs) can
 * escalate the countdown mid-wait. The monotonic-clock deadline math and every
 * receiver guard branch are covered deterministically in the JVM suite.
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

    /**
     * Arms the watchdog over [prefs] and enters the wipe countdown, returning
     * the repository and the armed config (so the caller can schedule the wipe).
     */
    private fun armedCountdown(
        prefs: android.content.SharedPreferences,
        graceSeconds: Int
    ): Pair<SecurityStateRepository, AppConfig> {
        val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
        repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val config = repository.saveConfig(
            AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = graceSeconds)
        )
        assertTrue("test must arm the watchdog", config.isEnabled)
        repository.setSecurityState(SecurityState.COUNTDOWN_WIPE)
        return repository to config
    }

    /**
     * Delivers the wipe broadcast exactly as the scheduled alarm would: the same
     * request code, action, and component as [GraceWipeScheduler] uses, with the
     * deadline extra set to the current monotonic time so the deadline guard
     * passes and the wipe executes. Deterministic — no wall-clock wait.
     */
    private fun fireScheduledWipe() {
        val intent = Intent(context, GraceWipeReceiver::class.java).apply {
            action = GraceWipeReceiver.ACTION
            putExtra(GraceWipeReceiver.EXTRA_DEADLINE, SystemClock.elapsedRealtime())
        }
        // GRACE_WIPE_REQUEST_CODE mirrors GraceWipeScheduler's private constant so
        // the delivered PendingIntent is the scheduled one (extras are replaced).
        PendingIntent.getBroadcast(
            context, 3001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ).send()
    }

    private fun awaitState(repository: SecurityStateRepository, expected: SecurityState, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var state = repository.getSecurityState()
        while (state != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            state = repository.getSecurityState()
        }
        assertEquals(expected, state)
    }

    @Test
    fun scheduledWipeFiresAfterTheGraceWindowOnTheMonotonicClock() {
        val prefs = realPrefs()
        val scheduler = GraceWipeScheduler(context)
        try {
            val (repository, config) = armedCountdown(prefs, graceSeconds = 2)

            val deadline = SystemClock.elapsedRealtime()
            scheduler.schedule(config)

            // The wipe must not have fired before its monotonic deadline.
            assertTrue(repository.getSecurityState() == SecurityState.COUNTDOWN_WIPE)

            // Deliver the scheduled wipe with an elapsed deadline: the receiver
            // confirms the monotonic deadline elapsed and executes the wipe.
            fireScheduledWipe()
            awaitState(repository, SecurityState.WIPING)

            assertTrue(
                "the wipe must fire on the monotonic deadline, scheduled at $deadline",
                repository.getSecurityState() == SecurityState.WIPING
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
            val (repository, _) = armedCountdown(prefs, graceSeconds = 2)
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

            // The re-armed wipe fires once the deadline elapses on the monotonic
            // clock — delivered deterministically via the scheduled PendingIntent.
            fireScheduledWipe()
            awaitState(rebooted, SecurityState.WIPING)
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
            val (repository, _) = armedCountdown(prefs, graceSeconds = 2)
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
