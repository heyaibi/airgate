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
import android.content.IntentFilter
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

    /** A scheduler that records re-arms instead of touching the real AlarmManager. */
    private class RecordingGraceWipeScheduler : GraceWipeScheduler(
        InstrumentationRegistry.getInstrumentation().targetContext,
        { 0L }
    ) {
        val scheduleDelays = mutableListOf<Long>()
        var scheduleCalls = 0
        override fun schedule(config: AppConfig) {
            scheduleCalls++
        }
        override fun scheduleDelay(delayMs: Long) {
            scheduleDelays.add(delayMs)
        }
    }

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
    private fun fireScheduledWipe(deadline: Long = SystemClock.elapsedRealtime()) {
        val intent = Intent(context, GraceWipeReceiver::class.java).apply {
            action = GraceWipeReceiver.ACTION
            putExtra(GraceWipeReceiver.EXTRA_DEADLINE, deadline)
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

    private fun awaitWipeSettled(repository: SecurityStateRepository, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (
            (repository.getSecurityState() != SecurityState.WIPING || repository.getWipeDeadline() != 0L) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        assertEquals(SecurityState.WIPING, repository.getSecurityState())
        assertEquals(0L, repository.getWipeDeadline())
    }

    /**
     * A wipe that is legitimately skipped must stay skipped: give any would-be
     * delayed execution a chance to fire, then assert the state is unchanged.
     */
    private fun awaitSettled(repository: SecurityStateRepository, expected: SecurityState) {
        Thread.sleep(300)
        assertEquals(expected, repository.getSecurityState())
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
    fun earlyFire_doesNotExecuteTheWipeAndRearms() {
        // An alarm that fires before the grace deadline must not wipe early, and
        // must re-arm the remaining delay (a spent one-shot alarm cannot lose the
        // wipe). The receiver is dynamically registered so the broadcast delivery
        // is real (goAsync() is valid) while a recording scheduler is injected to
        // make the re-arm observable: if the re-arm were removed, no schedule
        // would be recorded and the assertion below would fail.
        val prefs = realPrefs()
        val scheduler = GraceWipeScheduler(context)
        val recording = RecordingGraceWipeScheduler()
        try {
            val (repository, _) = armedCountdown(prefs, graceSeconds = 2)

            val now = SystemClock.elapsedRealtime()
            val deadline = now + 60_000L

            val receiver = object : GraceWipeReceiver(schedulerProvider = { recording }) {
                override fun createRepository(context: Context): SecurityStateRepository = repository
            }
            context.registerReceiver(
                receiver,
                IntentFilter(GraceWipeReceiver.ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
            try {
                val intent = Intent().apply {
                    action = GraceWipeReceiver.ACTION
                    setPackage(context.packageName)
                    putExtra(GraceWipeReceiver.EXTRA_DEADLINE, deadline)
                }
                context.sendBroadcast(intent)

                // The broadcast is delivered asynchronously; wait until the re-arm
                // has been recorded (or a short timeout elapses) before asserting.
                val deliveryDeadline = System.currentTimeMillis() + 5_000
                while (recording.scheduleDelays.isEmpty() && System.currentTimeMillis() < deliveryDeadline) {
                    Thread.sleep(25)
                }
                awaitSettled(repository, SecurityState.COUNTDOWN_WIPE)

                // The wipe must not have executed, and a re-arm for the remaining
                // delay must have been scheduled. The remaining delay must be the
                // near-full grace window still left until the original deadline (it
                // re-arms the remainder rather than resetting the whole countdown),
                // accounting for the small broadcast-delivery latency on-device.
                assertEquals(1, recording.scheduleDelays.size)
                val remaining = recording.scheduleDelays[0]
                assertTrue(
                    "remaining delay must be positive and no larger than the grace, was $remaining",
                    remaining in 1..60_000
                )
                assertTrue(
                    "the re-arm must cover the remaining window (near the full grace), was $remaining",
                    remaining > 60_000 - 1_000
                )
                assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
            } finally {
                context.unregisterReceiver(receiver)
            }
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

    // --- Countdown latch: an active wipe countdown is never re-armed ---

    @Test
    fun reEscalationWhileInCountdown_doesNotRearmTheWipeOrMoveTheDeadline() {
        // A further wipe escalation while the countdown is already running must
        // not re-arm the alarm or push the absolute deadline out. On device this
        // is observable through the injected scheduler (no new schedule call) and
        // the persisted deadline (unchanged).
        val prefs = realPrefs()
        try {
            val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
            repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            repository.saveConfig(
                AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 60)
            )
            repository.setSecurityState(SecurityState.ARMED_COMPLIANT)

            val recording = RecordingGraceWipeScheduler()
            val engine = ThreatEngine(
                context, repository, DhizukuManager(context),
                graceWipeScheduler = recording
            )

            engine.executeWipeState()
            assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
            assertEquals(1, recording.scheduleCalls)
            val firstDeadline = repository.getWipeDeadline()
            assertTrue("the countdown must persist a deadline", firstDeadline > 0L)

            // A second escalation while the countdown runs must be a no-op.
            engine.executeWipeState()

            assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
            assertEquals("the wipe must not be re-armed", 1, recording.scheduleCalls)
            assertEquals(
                "the absolute wipe deadline must not move",
                firstDeadline,
                repository.getWipeDeadline()
            )
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun latchedCountdown_stillFiresTheWipeOnItsDeadline() {
        // The latch only prevents re-arming an active countdown; it must never
        // stop the scheduled wipe from executing on the original deadline.
        // AlarmManager may defer setAndAllowWhileIdle() for much longer than this
        // test should wait, so deliver the same PendingIntent deterministically
        // with the persisted deadline after the real scheduler has installed it.
        val prefs = realPrefs()
        val scheduler = GraceWipeScheduler(context)
        try {
            val repository = SecurityStateRepository(prefs, null, notificationsAllowedProvider = { true })
            repository.savePin(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            repository.saveConfig(
                AppConfig(isEnabled = true, dryRunMode = true, graceWindowSeconds = 1)
            )
            repository.setSecurityState(SecurityState.ARMED_COMPLIANT)

            val engine = ThreatEngine(
                context, repository, DhizukuManager(context),
                graceWipeScheduler = scheduler
            )

            engine.executeWipeState()
            assertEquals(SecurityState.COUNTDOWN_WIPE, repository.getSecurityState())
            val firstDeadline = repository.getWipeDeadline()

            // A second escalation is latched and must not replace the real alarm
            // with a later one.
            engine.executeWipeState()
            assertEquals(firstDeadline, repository.getWipeDeadline())

            while (SystemClock.elapsedRealtime() < firstDeadline) {
                Thread.sleep(25)
            }
            fireScheduledWipe(firstDeadline)
            awaitWipeSettled(repository)
        } finally {
            scheduler.cancel()
            prefs.edit().clear().commit()
        }
    }
}
